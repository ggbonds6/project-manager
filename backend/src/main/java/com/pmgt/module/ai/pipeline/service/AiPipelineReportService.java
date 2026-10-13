package com.pmgt.module.ai.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.AiPipelineStages;
import com.pmgt.module.ai.pipeline.dto.ArtifactAppendRequest;
import com.pmgt.module.ai.pipeline.dto.ArtifactVO;
import com.pmgt.module.ai.pipeline.dto.EventReportRequest;
import com.pmgt.module.ai.pipeline.dto.EventVO;
import com.pmgt.module.ai.pipeline.dto.FinishRequest;
import com.pmgt.module.ai.pipeline.dto.FinishVO;
import com.pmgt.module.ai.pipeline.dto.StageReportRequest;
import com.pmgt.module.ai.pipeline.dto.StageReportVO;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineArtifactMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineStageMapper;
import com.pmgt.module.ai.service.AiAttachmentIndexWriter;
import com.pmgt.module.ai.service.AiScopeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 服务回写落库（契约 4~7）：阶段上报 / 产物追加 / 事件审计 / 终态。
 *
 * <h2>三张子表的写入语义（都与任务表相反，写错了很难发现）</h2>
 * <table>
 *   <tr><th>表</th><th>语义</th><th>为什么</th></tr>
 *   <tr><td>阶段</td><td>同 {@code (run_id, stage_key)} <b>更新同一行</b> + {@code attempts+1}</td>
 *       <td>前端阶段条不能因为重试长出重复节点</td></tr>
 *   <tr><td>产物</td><td><b>只 INSERT</b>，{@code artifact_rev} 递增</td>
 *       <td>两次解析差异对比（历史版本必须留得住）</td></tr>
 *   <tr><td>事件</td><td><b>只 INSERT</b>，不改任务状态</td>
 *       <td>审计只增不减；状态只由 claim/release/finish 改</td></tr>
 * </table>
 */
@Service
public class AiPipelineReportService {

    private final AiPipelineRunLookup lookup;
    private final AiPipelineStageMapper stageMapper;
    private final AiPipelineArtifactMapper artifactMapper;
    private final AttachmentAiTaskMapper taskMapper;
    private final AiPipelineEventWriter events;
    private final AiPipelineProperties props;
    private final AiAttachmentIndexWriter indexWriter;
    private final ObjectMapper objectMapper;

    public AiPipelineReportService(AiPipelineRunLookup lookup,
                                   AiPipelineStageMapper stageMapper,
                                   AiPipelineArtifactMapper artifactMapper,
                                   AttachmentAiTaskMapper taskMapper,
                                   AiPipelineEventWriter events,
                                   AiPipelineProperties props,
                                   AiAttachmentIndexWriter indexWriter,
                                   ObjectMapper objectMapper) {
        this.lookup = lookup;
        this.stageMapper = stageMapper;
        this.artifactMapper = artifactMapper;
        this.taskMapper = taskMapper;
        this.events = events;
        this.props = props;
        this.indexWriter = indexWriter;
        this.objectMapper = objectMapper;
    }

    // ══════════════════════════════════════════════════════════════════
    // 4) 阶段上报（幂等 upsert）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 阶段上报。
     *
     * <p>幂等规则（契约原文）：同 {@code (run_id, stage_key)} 更新同一行并 {@code attempts+1}。
     * 因此第一次上报 {@code attempts=1}，第二次同键上报 {@code attempts=2}，行数始终为 1。
     *
     * @throws AiPipelineException 400 阶段键/状态非法、非法阶段迁移；404 run 不存在
     */
    @Transactional
    public StageReportVO reportStage(String runId, String stageKeyRaw, StageReportRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        String stageKey = AiPipelineStages.normalize(stageKeyRaw);
        if (stageKey == null) {
            throw AiPipelineException.badRequest("stage_key 非法：" + stageKeyRaw + "；合法值："
                    + String.join("/", AiPipelineStages.ALL_STAGE_KEYS)
                    + "（前五个 PARSE..INDEX 是解析入库阶段，RETRIEVE/GENERATE 是在线阶段）");
        }
        if (request == null || request.getStatus() == null || request.getStatus().isBlank()) {
            throw AiPipelineException.badRequest("status 不能为空；合法值："
                    + String.join("/", AiPipelineStateMachine.STAGE_STATES));
        }
        AiPipelineStages.Stage stage = AiPipelineStages.of(stageKey);
        LocalDateTime now = LocalDateTime.now();

        AiPipelineStage existing = stageMapper.selectOne(new LambdaQueryWrapper<AiPipelineStage>()
                .eq(AiPipelineStage::getRunId, runId)
                .eq(AiPipelineStage::getStageKey, stageKey));
        String fromStatus = existing == null ? null : existing.getStatus();
        String status = AiPipelineStateMachine.requireStageTransition(fromStatus, request.getStatus());

        AiPipelineStage row = existing == null ? new AiPipelineStage() : existing;
        row.setRunId(runId);
        row.setTaskId(task.getId());
        row.setProjectId(task.getProjectId());
        row.setAttachmentId(task.getAttachmentId());
        row.setDocId(firstText(request.getDocId(), existing == null ? null : existing.getDocId(),
                task.getDocId()));
        row.setStageKey(stageKey);
        row.setStageName(firstText(request.getStageName(), stage.name()));
        row.setSeq(stage.seq());
        row.setStatus(status);
        if (AiPipelineStage.RUNNING.equals(status) && row.getStartedAt() == null) {
            row.setStartedAt(now);
        }
        if (isStageTerminal(status)) {
            row.setFinishedAt(now);
            if (row.getStartedAt() == null) {
                // 只上报了终态（没报 RUNNING）：把开始时间补成"结束前 0 秒"而不是留空，
                // 否则前端算不出"这一步花了多久"，只能显示"—"
                row.setStartedAt(now);
            }
        }
        row.setElapsedMs(request.getElapsedMs() == null
                ? deriveStageElapsedMs(row)
                : request.getElapsedMs());
        row.setInputDigest(firstText(request.getInputDigest(), existing == null ? null : existing.getInputDigest()));
        row.setOutputDigest(firstText(request.getOutputDigest(), existing == null ? null : existing.getOutputDigest()));
        row.setItemCount(request.getItemCount() == null
                ? (existing == null ? 0 : existing.getItemCount())
                : request.getItemCount());
        row.setItemFailed(request.getItemFailed() == null
                ? (existing == null ? 0 : existing.getItemFailed())
                : request.getItemFailed());
        row.setCountersJson(countersJson(request.getCounters(), existing == null ? null : existing.getCountersJson()));
        row.setErrorMsg(truncate(firstText(request.getErrorMsg(), existing == null ? null : existing.getErrorMsg()), 1000));
        row.setAttempts((existing == null || existing.getAttempts() == null ? 0 : existing.getAttempts()) + 1);

        if (existing == null) {
            stageMapper.insert(row);
        } else {
            stageMapper.updateById(row);
        }

        // 任务行的"当前阶段"列同步（前端任务列表行要显示"卡在哪一步"）
        syncTaskStage(task, stage, request, now);

        StageReportVO vo = new StageReportVO();
        vo.setRunId(runId);
        vo.setStageKey(stageKey);
        vo.setStageName(row.getStageName());
        vo.setSeq(row.getSeq());
        vo.setStatus(row.getStatus());
        vo.setAttempts(row.getAttempts());
        vo.setItemCount(row.getItemCount());
        vo.setItemFailed(row.getItemFailed());
        vo.setElapsedMs(row.getElapsedMs());
        return vo;
    }

    /**
     * 同步任务行的阶段展示列（{@code stage_key/stage/stage_done/stage_total}）。
     *
     * <p>刻意<b>不动</b>任务状态：状态只由 claim/release/finish 改（谁写状态必须是单一路径，
     * 否则"阶段说在跑、任务说 DONE"这种自相矛盾迟早出现）。
     * 用条件 UPDATE + {@code status='RUNNING'} 限定：任务已经进终态时不要再被阶段上报改显示。
     */
    private void syncTaskStage(AttachmentAiTask task, AiPipelineStages.Stage stage,
                               StageReportRequest request, LocalDateTime now) {
        if (task.getId() == null) {
            return;
        }
        taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, task.getId())
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .set(AttachmentAiTask::getStageKey, stage.key())
                .set(AttachmentAiTask::getStage, stage.name())
                .set(AttachmentAiTask::getStageDone, request.getItemCount() == null ? 0 : request.getItemCount())
                .set(AttachmentAiTask::getStageTotal, 0)
                .set(AttachmentAiTask::getUpdateTime, now));
    }

    // ══════════════════════════════════════════════════════════════════
    // 5) 产物追加（只 INSERT + artifact_rev 自增）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 追加产物。
     *
     * <p>{@code artifact_rev} = 同 {@code (run_id, artifact_type)} 的当前最大值 + 1（从 1 起）。
     *
     * @throws AiPipelineException 400 产物类型非法；404 run 不存在
     */
    @Transactional
    public ArtifactVO appendArtifact(String runId, ArtifactAppendRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        if (request == null || request.getArtifactType() == null || request.getArtifactType().isBlank()) {
            throw AiPipelineException.badRequest("artifact_type 不能为空；合法值："
                    + String.join("/", AiPipelineStages.ARTIFACT_TYPES));
        }
        String artifactType = AiPipelineStages.normalizeArtifactType(request.getArtifactType());
        if (artifactType == null) {
            throw AiPipelineException.badRequest("artifact_type 非法：" + request.getArtifactType()
                    + "；合法值：" + String.join("/", AiPipelineStages.ARTIFACT_TYPES));
        }
        String stageKey = AiPipelineStages.stageOfArtifact(artifactType);

        String content = renderContent(request.getContent(), request.getNote());
        int maxChars = props.effectiveMaxArtifactContentChars();
        long realChars = request.getCharCount() != null ? request.getCharCount()
                : (content == null ? 0L : content.length());
        String stored = content == null ? null : truncate(content, maxChars);

        Integer maxRev = currentMaxRev(runId, artifactType);
        AiPipelineArtifact artifact = new AiPipelineArtifact();
        artifact.setRunId(runId);
        artifact.setTaskId(task.getId());
        artifact.setProjectId(task.getProjectId());
        artifact.setAttachmentId(task.getAttachmentId());
        artifact.setStageKey(stageKey);
        artifact.setArtifactType(artifactType);
        artifact.setArtifactRev(maxRev + 1);
        artifact.setObjectKey(truncate(request.getObjectKey(), 255));
        artifact.setContentJson(stored);
        artifact.setCharCount(realChars);
        artifact.setItemCount(request.getItemCount() == null ? 0 : request.getItemCount());
        artifact.setSha1(truncate(request.getSha1(), 64));
        artifactMapper.insert(artifact);

        ArtifactVO vo = new ArtifactVO();
        vo.setId(artifact.getId());
        vo.setRunId(runId);
        vo.setStageKey(stageKey);
        vo.setArtifactType(artifactType);
        vo.setArtifactRev(artifact.getArtifactRev());
        vo.setObjectKey(artifact.getObjectKey());
        vo.setCharCount(artifact.getCharCount());
        vo.setItemCount(artifact.getItemCount());
        vo.setSha1(artifact.getSha1());
        return vo;
    }

    /** 同 run 同类型的当前最大版本号（无记录返回 0 ⇒ 新行 rev = 1）。 */
    private Integer currentMaxRev(String runId, String artifactType) {
        List<AiPipelineArtifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<AiPipelineArtifact>()
                .select(AiPipelineArtifact::getArtifactRev)
                .eq(AiPipelineArtifact::getRunId, runId)
                .eq(AiPipelineArtifact::getArtifactType, artifactType)
                .orderByDesc(AiPipelineArtifact::getArtifactRev));
        if (rows.isEmpty() || rows.get(0).getArtifactRev() == null) {
            return 0;
        }
        return rows.get(0).getArtifactRev();
    }

    /**
     * 产物内容 → 落库字符串。
     *
     * <p>{@code note} 没有独立列（V15 无该列，本批不新增），并入 {@code content_json}：
     * 只有 note 时存 {@code {"_note":"…"}}；两者都有时存 {@code {"_note":"…","_content":<content>}}。
     * 这是"不丢信息"与"不伪造列"之间的取舍，读方认这两个下划线键即可。
     */
    private String renderContent(JsonNode content, String note) {
        String noteText = trim(note);
        if (noteText == null || noteText.isBlank()) {
            if (content == null || content.isNull()) {
                return null;
            }
            return content.isTextual() ? content.asText() : content.toString();
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("_note", noteText);
        if (content != null && !content.isNull()) {
            wrapper.set("_content", content);
        }
        return wrapper.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    // 6) 事件审计（只追加，不改状态）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 记一条状态迁移审计事件（<b>不改任务状态</b>）。
     *
     * @throws AiPipelineException 400 状态值非法；404 run 不存在
     */
    @Transactional
    public EventVO reportEvent(String runId, EventReportRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        if (request == null || request.getToStatus() == null || request.getToStatus().isBlank()) {
            throw AiPipelineException.badRequest("to_status 不能为空；合法值："
                    + String.join("/", AiPipelineStateMachine.TASK_STATES));
        }
        // 只校验"是不是合法状态值"，不校验迁移合法性：审计要如实记录已经发生的事（见 EventReportRequest 注释）
        String to = AiPipelineStateMachine.requireTaskState(request.getToStatus(), "to_status");
        String from = request.getFromStatus() == null || request.getFromStatus().isBlank()
                ? null
                : AiPipelineStateMachine.requireTaskState(request.getFromStatus(), "from_status");
        String stageKey = request.getStageKey() == null ? null : AiPipelineStages.normalize(request.getStageKey());
        if (request.getStageKey() != null && stageKey == null) {
            throw AiPipelineException.badRequest("stage_key 非法：" + request.getStageKey()
                    + "；合法值：" + String.join("/", AiPipelineStages.ALL_STAGE_KEYS));
        }
        AiPipelineEvent event = events.record(runId, task.getId(), task.getProjectId(), stageKey,
                from, to, request.getReason(), request.getOperator());
        EventVO vo = new EventVO();
        vo.setId(event.getId());
        vo.setRunId(runId);
        vo.setStageKey(stageKey);
        vo.setFromStatus(from);
        vo.setToStatus(to);
        vo.setReason(event.getReason());
        vo.setOperator(event.getOperator());
        vo.setCreateTime(AiPipelineRunLookup.format(event.getCreateTime()));
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════
    // 7) finish：终态（任务终态以主系统为准）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 终态回调。
     *
     * <p>三道关卡，缺一不可：
     * <ol>
     *   <li><b>合法终态</b>：只接受 DONE/PARTIAL/FAILED/CANCELLED/DEAD（400）；</li>
     *   <li><b>合法迁移</b>（状态机，400 并列出允许的目标状态）；</li>
     *   <li><b>并发保护</b>：条件 UPDATE（{@code WHERE status=原状态}），受影响行数 ≠ 1 ⇒ 409。</li>
     * </ol>
     *
     * @throws AiPipelineException 400 非法终态/非法迁移；404 run 不存在；409 状态已被他人改写
     */
    @Transactional
    public FinishVO finish(String runId, FinishRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        if (request == null || request.getStatus() == null || request.getStatus().isBlank()) {
            throw AiPipelineException.badRequest("status 不能为空；终态只能是 "
                    + String.join("/", List.of(AttachmentAiTask.DONE, AttachmentAiTask.PARTIAL,
                    AttachmentAiTask.FAILED, AttachmentAiTask.CANCELLED, AttachmentAiTask.DEAD)));
        }
        String to = AiPipelineStateMachine.requireTaskState(request.getStatus(), "status");
        if (!AiPipelineStateMachine.TASK_TERMINAL_STATES.contains(to)) {
            throw AiPipelineException.badRequest("status 非法：" + request.getStatus()
                    + "（finish 只接受终态）；终态只能是 "
                    + String.join("/", List.of(AttachmentAiTask.DONE, AttachmentAiTask.PARTIAL,
                    AttachmentAiTask.FAILED, AttachmentAiTask.CANCELLED, AttachmentAiTask.DEAD))
                    + "。运行中的状态由 claim 与阶段上报体现，不能通过 finish 写");
        }
        String from = task.getStatus();
        AiPipelineStateMachine.requireTaskTransition(from, to);
        if (to.equals(from)) {
            // 终态重复上报（AI 侧重试）：幂等返回，不再写库、不再记重复事件——
            // 否则事件流里会堆满一模一样的 AI_DONE，看着像"跑了三次"
            return finishResult(task, to, task.getDocId(), null, task.getElapsedMs(),
                    "该 run 已经是 " + to + "，本次重复上报被幂等忽略");
        }

        LocalDateTime now = LocalDateTime.now();
        String docId = firstText(request.getDocId(), task.getDocId());
        Long elapsedMs = elapsedMs(task, now);

        int affected = taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, task.getId())
                .eq(AttachmentAiTask::getStatus, from)
                .set(AttachmentAiTask::getStatus, to)
                .set(AttachmentAiTask::getErrorMsg, truncate(request.getErrorMsg(), 500))
                // elapsed_ms 在 V15 是 NOT NULL DEFAULT 0：算不出来（create_time 缺失）时
                // **不要**写 NULL（会直接 SQL 报错），而是不动这一列
                .set(elapsedMs != null, AttachmentAiTask::getElapsedMs, elapsedMs)
                .set(AttachmentAiTask::getClaimedBy, null)
                .set(AttachmentAiTask::getClaimExpiresAt, null)
                .set(request.getPipelineRev() != null, AttachmentAiTask::getPipelineRev,
                        truncate(request.getPipelineRev(), 128))
                .set(request.getCleanRev() != null, AttachmentAiTask::getCleanRev, request.getCleanRev())
                .set(docId != null, AttachmentAiTask::getDocId, docId)
                .set(AttachmentAiTask::getUpdateTime, now));
        if (affected != 1) {
            throw AiPipelineException.conflict("终态未落库：" + runId + " 的状态刚刚被其它写入者改掉"
                    + "（提交前是 " + from + "）。请重新查询 run 详情后再决定："
                    + "若已是终态就无需再报；若不是，请按当前状态重新走 claim/finish 流程");
        }

        String reason = switch (to) {
            case AttachmentAiTask.DONE -> "AI_DONE";
            case AttachmentAiTask.PARTIAL -> "AI_PARTIAL:" + (request.getErrorMsg() == null
                    ? "有失败条目但文档可用" : request.getErrorMsg());
            case AttachmentAiTask.CANCELLED -> "CANCEL";
            case AttachmentAiTask.DEAD -> "RETRY_EXHAUSTED";
            default -> "AI_FAILED:" + (request.getErrorMsg() == null ? "-" : request.getErrorMsg());
        };
        events.record(runId, task.getId(), task.getProjectId(), null, from, to, reason, null);

        String attachmentStatus = null;
        String note = null;
        switch (to) {
            case AttachmentAiTask.DONE, AttachmentAiTask.PARTIAL -> {
                if (docId != null) {
                    attachmentStatus = AiScopeResolver.STATUS_READY;
                    indexWriter.write(task.getAttachmentId(), AiScopeResolver.STATUS_READY, docId, now);
                } else {
                    // 不假装成功：没有 doc_id 就没法问答，附件索引状态保持"解析中"等对账回填
                    attachmentStatus = AiScopeResolver.STATUS_PARSING;
                    note = "任务已 " + to + "，但主系统还没有 doc_id（finish 未带、对账也尚未回填）："
                            + "附件索引状态保持「解析中」，待对账拿到 doc_id 后才会变成「已可检索」。"
                            + "若 AI 侧已知 doc_id，请在下一次 finish/阶段上报里带上 doc_id。";
                }
            }
            case AttachmentAiTask.FAILED, AttachmentAiTask.CANCELLED, AttachmentAiTask.DEAD -> {
                attachmentStatus = AiScopeResolver.STATUS_FAILED;
                indexWriter.write(task.getAttachmentId(), AiScopeResolver.STATUS_FAILED, null, null);
            }
            default -> {
                // 不可达：上面的终态白名单已收口
            }
        }
        return finishResult(task, to, docId, attachmentStatus, elapsedMs, note);
    }

    private FinishVO finishResult(AttachmentAiTask task, String status, String docId,
                                  String attachmentStatus, Long elapsedMs, String note) {
        FinishVO vo = new FinishVO();
        vo.setRunId(AiPipelineKeys.runIdOf(task.getId()));
        vo.setTaskId(task.getId());
        vo.setAttachmentId(task.getAttachmentId());
        vo.setStatus(status);
        vo.setDocId(docId);
        vo.setAttachmentIndexStatus(attachmentStatus);
        // 用调用方刚算出来的值：任务对象是提交前的快照，它的 elapsed_ms 还没被这次 UPDATE 刷新
        vo.setElapsedMs(elapsedMs);
        vo.setNote(note);
        return vo;
    }

    /** 任务端到端耗时（毫秒）：从任务创建算起；创建时间缺失则留空而不是编一个 0。 */
    private static Long elapsedMs(AttachmentAiTask task, LocalDateTime now) {
        if (task.getCreateTime() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getCreateTime(), now).toMillis());
    }

    /** 阶段耗时兜底：只上报终态时用 started_at→now 推。 */
    private static Long deriveStageElapsedMs(AiPipelineStage row) {
        if (row.getStartedAt() == null) {
            return 0L;
        }
        LocalDateTime end = row.getFinishedAt() == null ? LocalDateTime.now() : row.getFinishedAt();
        return Math.max(0L, Duration.between(row.getStartedAt(), end).toMillis());
    }

    private String countersJson(JsonNode counters, String fallback) {
        if (counters == null || counters.isNull()) {
            return fallback;
        }
        String json = counters.isTextual() ? counters.asText() : counters.toString();
        return truncate(json, 2000);
    }

    private static boolean isStageTerminal(String status) {
        return AiPipelineStage.DONE.equals(status) || AiPipelineStage.PARTIAL.equals(status)
                || AiPipelineStage.SKIPPED.equals(status) || AiPipelineStage.FAILED.equals(status)
                || AiPipelineStage.CANCELLED.equals(status);
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
