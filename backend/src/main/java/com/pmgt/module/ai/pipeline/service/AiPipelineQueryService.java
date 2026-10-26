package com.pmgt.module.ai.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.AiPipelineStages;
import com.pmgt.module.ai.pipeline.dto.ArtifactContentVO;
import com.pmgt.module.ai.pipeline.dto.ArtifactVO;
import com.pmgt.module.ai.pipeline.dto.EventVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunDetailVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunPageVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunVO;
import com.pmgt.module.ai.pipeline.dto.PipelineStageVO;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineArtifactMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineStageMapper;
import com.pmgt.module.ai.service.AiScopeResolver;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任务流读取：详情 / 列表 / 产物（前端任务流视图与「{@code [n]} → 切片」溯源的读侧）。
 *
 * <h2>投影兜底（契约 10，只读）</h2>
 * <p>AI 侧回调可能丢（网络抖动、进程被 kill、旧版 AI 服务根本不上报）。主系统<b>不去猜</b>
 * 它到底在不在跑，只做一件确定的事：<b>某阶段还是 {@code RUNNING} 且很久没有任何更新</b>时，
 * 在<b>展示层</b>把它标成 {@code STALE}，并附一句 {@code projection_note}。
 * <b>绝不改数据库状态</b>——理由见 {@code AiPipelineQueryServiceTest}：一旦读接口会写库，
 * 那么"刷新页面"与"任务真的失败"就再也分不清了。
 *
 * <h2>列表为什么不带 content</h2>
 * <p>与设计文档 §4.9.3 的分层一致：列表只给表头（阶段/计数/耗时/失败原因），
 * 产物内容另取（{@code /pipeline/artifacts/{artifact_type}}）。产物摘要单条最大 4000 字符，
 * 一个 run 有 4~6 条，全塞进列表就是几万字符的无用负载。
 */
@Service
public class AiPipelineQueryService {

    /** 列表单页上限（与仓库其它接口的 200 口径一致）。 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * **阶段级独有**的状态：只出现在 {@code ai_pipeline_stage.status}，任务级没有。
     * 收到它们说明调用方把两套枚举拿混了，报错要直接点明（否则只会看到"status 非法"）。
     */
    private static final Set<String> STAGE_ONLY_STATES =
            Set.of(AiPipelineStage.PENDING, AiPipelineStage.SKIPPED);

    private final AttachmentAiTaskMapper taskMapper;
    private final AiPipelineStageMapper stageMapper;
    private final AiPipelineArtifactMapper artifactMapper;
    private final AiPipelineEventMapper eventMapper;
    private final AiPipelineProperties props;
    private final AiScopeResolver scopeResolver;
    private final ObjectMapper objectMapper;

    public AiPipelineQueryService(AttachmentAiTaskMapper taskMapper,
                                  AiPipelineStageMapper stageMapper,
                                  AiPipelineArtifactMapper artifactMapper,
                                  AiPipelineEventMapper eventMapper,
                                  AiPipelineProperties props,
                                  AiScopeResolver scopeResolver,
                                  ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.stageMapper = stageMapper;
        this.artifactMapper = artifactMapper;
        this.eventMapper = eventMapper;
        this.props = props;
        this.scopeResolver = scopeResolver;
        this.objectMapper = objectMapper;
    }

    // ══════════════════════════════════════════════════════════════════
    // 8) 详情
    // ══════════════════════════════════════════════════════════════════

    /** 按 run_id 取详情（AI 侧与前端共用）。 */
    public PipelineRunDetailVO detailByRunId(String runId) {
        Long taskId = AiPipelineKeys.taskIdOf(runId);
        if (taskId == null) {
            throw AiPipelineException.badRequest("run_id 非法：" + runId
                    + "；本系统的 run_id 形状为 run-<taskId>（如 run-12）");
        }
        return detailByTaskId(taskId, runId);
    }

    /** 按任务 id 取详情（前端任务行用）。 */
    public PipelineRunDetailVO detailByTaskId(Long taskId, String runIdForMessage) {
        AttachmentAiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw AiPipelineException.notFound("流水线运行不存在：" + runIdForMessage);
        }
        return detail(task);
    }

    /**
     * 按附件取<b>最近一次</b>运行的详情（附件列表/文档列表点进来的入口）。
     *
     * @throws AiPipelineException 404 该附件没有任何解析任务
     */
    public PipelineRunDetailVO detailByAttachmentId(Long attachmentId) {
        AttachmentAiTask task = latestTaskOf(attachmentId);
        if (task == null) {
            throw AiPipelineException.notFound("附件 " + attachmentId + " 还没有解析任务："
                    + "它在主系统里从未被触发过解析（不是「正在解析」）");
        }
        return detail(task);
    }

    /**
     * 列表（任务流视图的表格）。
     *
     * <p>{@code status} 支持**逗号分隔多值**（如 {@code QUEUED,RUNNING} = "进行中"），
     * 单值保持与改动前完全一致。这个多值是必须的：任务级"进行中"本来就包含两个状态，
     * 前端以前为了凑出这个语义只能发两次请求（且发的是阶段级的 {@code PENDING} ⇒ 400）。
     *
     * <p>只认**任务级** 7 状态（{@link AiPipelineStateMachine#TASK_STATES}）；收到阶段级独有的
     * {@code PENDING}/{@code SKIPPED} 时，报错会**直接点明"这是阶段级状态，任务级请用 QUEUED/RUNNING"**
     * ——这条信息比"status 非法"有用得多：调用方一次就能改对。
     */
    public PipelineRunPageVO list(Long attachmentId, String status, int page, int size) {
        List<String> statuses = parseStatuses(status);
        List<AttachmentAiTask> all = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .eq(attachmentId != null, AttachmentAiTask::getAttachmentId, attachmentId)
                .in(!statuses.isEmpty(), AttachmentAiTask::getStatus, statuses)
                .orderByDesc(AttachmentAiTask::getId));
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Map<Long, String> projectNames = scopeResolver.projectNames(
                all.stream().map(AttachmentAiTask::getProjectId).distinct().toList());
        List<PipelineRunVO> records = new ArrayList<>();
        int from = (p - 1) * s;
        for (int i = from; i < Math.min(from + s, all.size()); i++) {
            records.add(summary(all.get(i), projectNames));
        }
        return PipelineRunPageVO.of(all.size(), p, s, records);
    }

    /**
     * 解析 {@code status} 查询参数：逗号分隔 → 去重后的任务级状态列表（空 = 不过滤）。
     *
     * <p><b>为什么必须区分"任务级"与"阶段级"</b>：两级是两套枚举，任务级是
     * {@code QUEUED/RUNNING/DONE/PARTIAL/FAILED/CANCELLED/DEAD}（{@code attachment_ai_task.status}），
     * 阶段级是 {@code PENDING/RUNNING/DONE/PARTIAL/SKIPPED/FAILED/CANCELLED}（{@code ai_pipeline_stage.status}）。
     * 前端曾把阶段级的 {@code PENDING} 当"排队中"发给本接口 ⇒ 400，而报错只说"status 非法"，
     * 调用方很难自己想到是"枚举拿错了级"。所以这里对 {@code PENDING}/{@code SKIPPED}
     * 单独给一句"这是阶段级状态，任务级请用 QUEUED/RUNNING"。
     */
    static List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : status.split(",")) {
            String s = part.trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            if (STAGE_ONLY_STATES.contains(s)) {
                throw AiPipelineException.badRequest("status 非法：" + s + "——" + s + " 是【阶段级】状态"
                        + "（ai_pipeline_stage.status），任务级没有它；按任务级筛选「进行中」请用 "
                        + "QUEUED,RUNNING。任务级合法值：" + String.join("/", AiPipelineStateMachine.TASK_STATES));
            }
            if (!AiPipelineStateMachine.TASK_STATES.contains(s)) {
                throw AiPipelineException.badRequest("status 非法：" + s + "；任务级合法值："
                        + String.join("/", AiPipelineStateMachine.TASK_STATES)
                        + "（可用逗号分隔多值，如 QUEUED,RUNNING）");
            }
            out.add(s);
        }
        if (out.isEmpty()) {
            throw AiPipelineException.badRequest("status 不能为空；任务级合法值："
                    + String.join("/", AiPipelineStateMachine.TASK_STATES));
        }
        return new ArrayList<>(out);
    }

    // ══════════════════════════════════════════════════════════════════
    // 产物读取（「[n] → chunk_key 清单」的入口）
    // ══════════════════════════════════════════════════════════════════

    /** 某 run 的产物摘要列表（可按类型过滤）。 */
    public List<ArtifactVO> artifacts(Long taskId, String artifactType) {
        AttachmentAiTask task = requireTask(taskId);
        String type = null;
        if (artifactType != null && !artifactType.isBlank()) {
            type = AiPipelineStages.normalizeArtifactType(artifactType);
            if (type == null) {
                throw AiPipelineException.badRequest("artifact_type 非法：" + artifactType
                        + "；合法值：" + String.join("/", AiPipelineStages.ARTIFACT_TYPES));
            }
        }
        List<AiPipelineArtifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<AiPipelineArtifact>()
                .eq(AiPipelineArtifact::getRunId, AiPipelineKeys.runIdOf(task.getId()))
                .eq(type != null, AiPipelineArtifact::getArtifactType, type)
                .orderByAsc(AiPipelineArtifact::getArtifactType)
                .orderByDesc(AiPipelineArtifact::getArtifactRev));
        return rows.stream().map(this::toArtifactVO).toList();
    }

    /**
     * 单条产物的<b>内容</b>（{@code ?rev=} 指定版本，缺省取最新版）。
     *
     * <p>它是溯源链第 4 跳的入口：{@code chunk_manifest} 的 content 里就是切片清单
     * （含 {@code chunk_key}）。主系统<b>不解释</b>这份 JSON 的形状，只原样取出并说明是否截断。
     */
    public ArtifactContentVO artifactContent(Long taskId, String artifactType, Integer rev) {
        AttachmentAiTask task = requireTask(taskId);
        if (artifactType == null || artifactType.isBlank()) {
            throw AiPipelineException.badRequest("artifact_type 不能为空；合法值："
                    + String.join("/", AiPipelineStages.ARTIFACT_TYPES));
        }
        String type = AiPipelineStages.normalizeArtifactType(artifactType);
        if (type == null) {
            throw AiPipelineException.badRequest("artifact_type 非法：" + artifactType
                    + "；合法值：" + String.join("/", AiPipelineStages.ARTIFACT_TYPES));
        }
        List<AiPipelineArtifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<AiPipelineArtifact>()
                .eq(AiPipelineArtifact::getRunId, AiPipelineKeys.runIdOf(task.getId()))
                .eq(AiPipelineArtifact::getArtifactType, type)
                .eq(rev != null, AiPipelineArtifact::getArtifactRev, rev)
                .orderByDesc(AiPipelineArtifact::getArtifactRev));
        if (rows.isEmpty()) {
            throw AiPipelineException.notFound("该运行没有 " + type + " 产物"
                    + (rev == null ? "" : "（rev=" + rev + "）")
                    + "：AI 侧可能还没上报这一阶段（或这个版本号不存在）");
        }
        AiPipelineArtifact row = rows.get(0);
        ArtifactContentVO vo = new ArtifactContentVO();
        vo.setRunId(row.getRunId());
        vo.setTaskId(row.getTaskId());
        vo.setAttachmentId(row.getAttachmentId());
        vo.setArtifactType(row.getArtifactType());
        vo.setArtifactRev(row.getArtifactRev());
        vo.setStageKey(row.getStageKey());
        vo.setContent(contentNode(row.getContentJson()));
        vo.setContentChars(row.getContentJson() == null ? 0 : row.getContentJson().length());
        vo.setCharCount(row.getCharCount());
        boolean truncated = row.getCharCount() != null && row.getContentJson() != null
                && row.getCharCount() > row.getContentJson().length();
        vo.setTruncated(truncated);
        vo.setObjectKey(row.getObjectKey());
        vo.setItemCount(row.getItemCount());
        vo.setSha1(row.getSha1());
        vo.setCreateTime(AiPipelineRunLookup.format(row.getCreateTime()));
        vo.setNote(truncated
                ? "内容超过主系统摘要上限（" + props.effectiveMaxArtifactContentChars()
                + " 字符）已被截断，char_count 是全文真实长度；全文在 AI 服务侧 object_key 指向的对象里"
                : null);
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部：详情组装 + 投影兜底
    // ══════════════════════════════════════════════════════════════════

    private AttachmentAiTask requireTask(Long taskId) {
        AttachmentAiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw AiPipelineException.notFound("解析任务不存在：" + taskId);
        }
        return task;
    }

    private AttachmentAiTask latestTaskOf(Long attachmentId) {
        List<AttachmentAiTask> rows = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getAttachmentId, attachmentId)
                .orderByDesc(AttachmentAiTask::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 组装详情（含投影兜底与产物/事件列表）。 */
    public PipelineRunDetailVO detail(AttachmentAiTask task) {
        String runId = AiPipelineKeys.runIdOf(task.getId());
        Map<Long, String> projectNames = scopeResolver.projectNames(
                task.getProjectId() == null ? List.of() : List.of(task.getProjectId()));

        PipelineRunDetailVO vo = new PipelineRunDetailVO();
        fill(vo, task, projectNames);

        List<AiPipelineStage> stages = stageMapper.selectList(new LambdaQueryWrapper<AiPipelineStage>()
                .eq(AiPipelineStage::getRunId, runId)
                .orderByAsc(AiPipelineStage::getSeq));
        List<AiPipelineStage> sorted = new ArrayList<>(stages);
        sorted.sort(java.util.Comparator.comparingInt(s -> AiPipelineStages.seqOf(s.getStageKey())));

        LocalDateTime now = LocalDateTime.now();
        int staleAfter = props.effectiveStaleRunningSeconds();
        LocalDateTime threshold = now.minusSeconds(staleAfter);
        List<String> staleKeys = new ArrayList<>();
        List<PipelineStageVO> stageVOs = new ArrayList<>();
        for (AiPipelineStage stage : sorted) {
            PipelineStageVO stageVO = toStageVO(stage);
            if (isStale(stage, threshold)) {
                stageVO.setStale(true);
                stageVO.setDisplayStatus("STALE");
                staleKeys.add(stage.getStageKey());
            }
            stageVOs.add(stageVO);
        }
        vo.setStages(stageVOs);
        vo.setStaleAfterSeconds(staleAfter);
        vo.setStaleStageKeys(staleKeys);
        if (!staleKeys.isEmpty()) {
            vo.setProjectionNote("阶段 " + String.join("/", staleKeys)
                    + " 仍为 RUNNING 且已超过 " + staleAfter + " 秒没有任何上报（阈值可配 "
                    + "pm.ai.pipeline.stale-running-seconds）。这可能是 AI 侧已中断（进程被杀/回调丢失）。"
                    + "本提示只是主系统的<b>只读投影</b>，数据库里的阶段状态没有被修改；"
                    + "需要人工确认时请同时看 AI 服务侧的任务日志。");
        }

        List<AiPipelineArtifact> artifacts = artifactMapper.selectList(new LambdaQueryWrapper<AiPipelineArtifact>()
                .eq(AiPipelineArtifact::getRunId, runId)
                .orderByAsc(AiPipelineArtifact::getStageKey)
                .orderByDesc(AiPipelineArtifact::getArtifactRev));
        vo.setArtifacts(artifacts.stream().map(this::toArtifactVO).toList());

        List<AiPipelineEvent> eventRows = eventMapper.selectList(new LambdaQueryWrapper<AiPipelineEvent>()
                .eq(AiPipelineEvent::getRunId, runId)
                .orderByAsc(AiPipelineEvent::getId));
        vo.setEvents(eventRows.stream().map(this::toEventVO).toList());
        return vo;
    }

    /** 概要（列表行）。 */
    public PipelineRunVO summary(AttachmentAiTask task, Map<Long, String> projectNames) {
        PipelineRunVO vo = new PipelineRunVO();
        fill(vo, task, projectNames);
        return vo;
    }

    private void fill(PipelineRunVO vo, AttachmentAiTask task, Map<Long, String> projectNames) {
        vo.setRunId(AiPipelineKeys.runIdOf(task.getId()));
        vo.setTaskId(task.getId());
        vo.setAttachmentId(task.getAttachmentId());
        vo.setProjectId(task.getProjectId());
        vo.setProjectName(task.getProjectId() == null ? null : projectNames.get(task.getProjectId()));
        vo.setFilename(task.getFilename());
        vo.setStatus(task.getStatus());
        vo.setTerminal(task.terminal());
        vo.setTaskType(task.getTaskType());
        vo.setPriority(task.getPriority());
        vo.setDocId(task.getDocId());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setAttempts(task.getAttempts());
        vo.setElapsedMs(task.getElapsedMs());
        vo.setStageKey(task.getStageKey());
        vo.setStage(task.getStage());
        vo.setStageDone(task.getStageDone());
        vo.setStageTotal(task.getStageTotal());
        vo.setClaimedBy(task.getClaimedBy());
        vo.setClaimExpiresAt(AiPipelineRunLookup.format(task.getClaimExpiresAt()));
        vo.setContentHash(task.getContentHash());
        vo.setPipelineRev(task.getPipelineRev());
        vo.setCleanRev(task.getCleanRev());
        vo.setTaskKey(AiPipelineKeys.taskKey(task.getContentHash(), task.getPipelineRev()));
        vo.setCreatedAt(AiPipelineRunLookup.format(task.getCreateTime()));
        vo.setUpdatedAt(AiPipelineRunLookup.format(task.getUpdateTime()));
    }

    /**
     * 投影判定：阶段仍是 {@code RUNNING}，且"最后一次动静"早于阈值。
     *
     * <p>"最后一次动静"按 {@code update_time → started_at → create_time} 依次退化取：
     * 只上报过 RUNNING 的阶段行也有 {@code update_time}（每次上报都会刷），
     * 所以正常路径下判据是稳定的；即使三列全空（理论不可达），也<b>不</b>判 STALE——
     * 宁可漏报一次提示，也不要把一个刚创建还没上报的行说成"卡住了"。
     */
    private static boolean isStale(AiPipelineStage stage, LocalDateTime threshold) {
        if (!AiPipelineStage.RUNNING.equals(stage.getStatus())) {
            return false;
        }
        LocalDateTime lastTouch = stage.getUpdateTime() != null ? stage.getUpdateTime()
                : (stage.getStartedAt() != null ? stage.getStartedAt() : stage.getCreateTime());
        return lastTouch != null && lastTouch.isBefore(threshold);
    }

    private PipelineStageVO toStageVO(AiPipelineStage stage) {
        PipelineStageVO vo = new PipelineStageVO();
        vo.setId(stage.getId());
        vo.setRunId(stage.getRunId());
        vo.setStageKey(stage.getStageKey());
        vo.setStageName(stage.getStageName() != null ? stage.getStageName()
                : AiPipelineStages.nameOf(stage.getStageKey()));
        vo.setSeq(stage.getSeq() != null ? stage.getSeq() : AiPipelineStages.seqOf(stage.getStageKey()));
        vo.setStatus(stage.getStatus());
        vo.setDisplayStatus(stage.getStatus());
        vo.setStale(false);
        vo.setElapsedMs(stage.getElapsedMs());
        vo.setItemCount(stage.getItemCount());
        vo.setItemFailed(stage.getItemFailed());
        vo.setCountersJson(stage.getCountersJson());
        vo.setCounters(parseJson(stage.getCountersJson()));
        vo.setInputDigest(stage.getInputDigest());
        vo.setOutputDigest(stage.getOutputDigest());
        vo.setErrorMsg(stage.getErrorMsg());
        vo.setAttempts(stage.getAttempts());
        vo.setStartedAt(AiPipelineRunLookup.format(stage.getStartedAt()));
        vo.setFinishedAt(AiPipelineRunLookup.format(stage.getFinishedAt()));
        vo.setUpdateTime(AiPipelineRunLookup.format(stage.getUpdateTime()));
        return vo;
    }

    private ArtifactVO toArtifactVO(AiPipelineArtifact row) {
        ArtifactVO vo = new ArtifactVO();
        vo.setId(row.getId());
        vo.setRunId(row.getRunId());
        vo.setStageKey(row.getStageKey());
        vo.setArtifactType(row.getArtifactType());
        vo.setArtifactRev(row.getArtifactRev());
        vo.setObjectKey(row.getObjectKey());
        vo.setContent(contentNode(row.getContentJson()));
        vo.setContentChars(row.getContentJson() == null ? 0 : row.getContentJson().length());
        vo.setCharCount(row.getCharCount());
        vo.setItemCount(row.getItemCount());
        vo.setSha1(row.getSha1());
        vo.setCreateTime(AiPipelineRunLookup.format(row.getCreateTime()));
        return vo;
    }

    /**
     * 落库摘要 → JSON 节点。
     *
     * <p>为什么解析成对象而不是把原字符串丢给前端：前端产物抽屉是"键值对"渲染
     * （`ContentKV` 递归展开对象/数组），给字符串就只能显示一整行转义 JSON——
     * 信息没丢，但没法读。解析失败时退回字符串（<b>绝不返回 null 把内容吞掉</b>）。
     */
    private JsonNode contentNode(String contentJson) {
        if (contentJson == null) {
            return null;
        }
        JsonNode parsed = parseJson(contentJson);
        return parsed != null ? parsed : objectMapper.getNodeFactory().textNode(contentJson);
    }

    private EventVO toEventVO(AiPipelineEvent row) {
        EventVO vo = new EventVO();
        vo.setId(row.getId());
        vo.setRunId(row.getRunId());
        vo.setStageKey(row.getStageKey());
        vo.setFromStatus(row.getFromStatus());
        vo.setToStatus(row.getToStatus());
        vo.setReason(row.getReason());
        vo.setOperator(row.getOperator());
        vo.setCreateTime(AiPipelineRunLookup.format(row.getCreateTime()));
        return vo;
    }

    /** 计数明细原文 → JSON 对象；解析不了就返回 null（原文仍在 counters_json 里，不丢信息）。 */
    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
