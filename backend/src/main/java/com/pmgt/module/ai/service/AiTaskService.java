package com.pmgt.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.module.ai.client.AiJson;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiAttachmentStatusVO;
import com.pmgt.module.ai.dto.AiPageVO;
import com.pmgt.module.ai.dto.AiTaskVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 解析任务服务：触发解析 / 重试 / 列表 / 详情 / 附件状态（§9 #4~#8）。
 *
 * <h2>为什么状态要「回源对账」</h2>
 * 解析是 AI 服务在后台跑的，主系统这一行只是<b>影子记录</b>：不主动去问一次，
 * 任务会永远停在 RUNNING。所以列表/详情在读之前先对「未结束」的任务做一次批量对账
 * （拉 AI 服务 {@code GET /upload-tasks} 一次，按 ai_task_id 匹配），
 * 对账成功后回写状态与 attachment.ai_index_status。
 *
 * <h2>AI 服务不可用时怎么办</h2>
 * 读接口（列表/详情/状态）<b>不抛错</b>，用本地缓存的状态作答：这是刻意的取舍——
 * 「解析任务的历史」是主系统自己的记录，AI 服务挂了不该让用户连历史都看不到。
 * 而<b>写动作</b>（触发解析/重试）没有 AI 服务就毫无意义，一律明确报错，
 * 不做「假装排队成功」的降级。
 */
@Service
public class AiTaskService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiProperties props;
    private final AiServiceClient ai;
    private final AttachmentAiTaskMapper taskMapper;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage attachmentStorage;
    private final AiScopeResolver scopeResolver;
    private final OperationLogService operationLogService;

    public AiTaskService(AiProperties props,
                         AiServiceClient ai,
                         AttachmentAiTaskMapper taskMapper,
                         AttachmentMapper attachmentMapper,
                         AttachmentStorage attachmentStorage,
                         AiScopeResolver scopeResolver,
                         OperationLogService operationLogService) {
        this.props = props;
        this.ai = ai;
        this.taskMapper = taskMapper;
        this.attachmentMapper = attachmentMapper;
        this.attachmentStorage = attachmentStorage;
        this.scopeResolver = scopeResolver;
        this.operationLogService = operationLogService;
    }

    // ══════════════════════════════════════════════════════════════════
    // 触发解析 / 重试（写）
    // ══════════════════════════════════════════════════════════════════

    /**
     * §9 #7 触发解析：把附件送去 AI 服务解析入库，返回本系统的任务 id。
     *
     * <p>不做「附件已 READY 就直接返回旧的」这种"省事"逻辑：
     * 用户点解析的语义就是"重新解析一遍"（比如改了 OCR DPI、或上次解析结果不对），
     * 悄悄跳过会让用户以为解析发生了。
     */
    @Transactional
    public AttachmentAiTask parse(Long attachmentId) {
        return submit(attachmentId, "PARSE", false, null);
    }

    /** §9 #6 重试：失败/取消的任务重新跑一遍（新任务行，保留历史）。 */
    @Transactional
    public AttachmentAiTask retry(Long taskId) {
        AttachmentAiTask old = taskMapper.selectById(taskId);
        if (old == null) {
            throw new BizException(404, "解析任务不存在");
        }
        if (old.pending()) {
            throw new BizException(400, "任务仍在进行中（" + old.getStatus() + "），无需重试");
        }
        return submit(old.getAttachmentId(), "RETRY", false, null);
    }

    /**
     * 上传成功后的<b>自动</b>触发（方案 §3.3）。
     *
     * <p>与手动 {@link #parse} 的两点差异，都是为了让"自动"这件事保持安静：
     * <ol>
     *   <li><b>已有未结束任务时静默跳过</b>（不抛 400）：自动触发是后台行为，没有用户在等这个异常；
     *       抛出只会变成日志噪音，跳过才是正确语义；</li>
     *   <li><b>显式带触发人</b>：后台线程拿不到 AuthContext，不带就会写出一条「操作人为空」的留痕。</li>
     * </ol>
     *
     * <p>异常<b>不在这里吞</b>：由调用方（{@code AiUploadAutoParseTrigger}）负责吞掉并记日志。
     * 这样单测可以直接断言「AI 不可达时确实抛了、且任务行被标成 FAILED」，
     * 而不是只能断言"什么都没发生"。
     */
    @Transactional
    public AttachmentAiTask autoParse(Long attachmentId, Long operatorUserId) {
        return submit(attachmentId, "AUTO_PARSE", true, operatorUserId);
    }

    private AttachmentAiTask submit(Long attachmentId, String action, boolean skipIfPending, Long operatorUserId) {
        requireEnabled();
        Attachment att = scopeResolver.requireAccessible(attachmentId);

        // 已有未结束的任务时不重复提交：两份后台解析同时写同一个 doc_id 会互相覆盖
        List<AttachmentAiTask> pending = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getAttachmentId, attachmentId)
                .in(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING)
                .orderByDesc(AttachmentAiTask::getId));
        if (!pending.isEmpty()) {
            if (skipIfPending) {
                log.info("[ai] 附件 {} 已有解析任务 {} 在进行中，自动触发跳过",
                        attachmentId, pending.get(0).getId());
                return pending.get(0);
            }
            throw new BizException(400, "该附件已有解析任务在进行中（任务 " + pending.get(0).getId() + "）");
        }

        AttachmentAiTask task = new AttachmentAiTask();
        task.setProjectId(scopeResolver.projectIdOf(att));
        task.setAttachmentId(att.getId());
        task.setFilename(att.getFileName());
        task.setStatus(AttachmentAiTask.QUEUED);
        task.setProgress(0);
        taskMapper.insert(task);

        // 先把附件标记为「解析中」：前端附件列表立刻能看到标签变化，
        // 不必等第一次对账（对账最短也要等用户下次轮询）
        updateAttachmentIndex(att.getId(), AiScopeResolver.STATUS_PARSING, null, null);

        try (InputStream in = attachmentStorage.open(att.getFilePath())) {
            Map<String, Object> submitted = ai.submitParse(
                    att.getFileName(), AiServiceClient.contentTypeOf(att.getFileName()), in);
            task.setAiTaskId(AiJson.nullableText(submitted, "task_id"));
            task.setStatus(translateStatus(AiJson.text(submitted, "status")));
            task.setProgress((int) Math.round(AiJson.doubleValue(submitted, "percent", 0.0)));
            // doc_id 一般要等解析完成才有；有的实现会先给占位，有就存下来
            task.setDocId(AiJson.nullableText(submitted, "doc_id"));
            taskMapper.updateById(task);
            logTrigger(task.getProjectId(), action,
                    "触发 AI 解析附件「" + att.getFileName() + "」(taskId=" + task.getId() + ")", operatorUserId);
            return task;
        } catch (RuntimeException e) {
            // 提交失败必须落状态：否则任务永远停在 QUEUED，用户看不到失败原因也没法重试
            markSubmitFailed(task, att, e);
            throw e;
        } catch (Exception e) {
            // 打不开附件文件（被删/存储不可达）也走同一条失败路径
            markSubmitFailed(task, att, e);
            throw new BizException(500, "读取附件内容失败：" + e.getMessage());
        }
    }

    /**
     * 写解析触发的操作留痕。
     *
     * <p>手动触发走 {@code AuthContext}（原行为不变）；自动触发发生在后台线程，
     * 必须把上传者 id 显式带进来，否则日志的操作人为空、审计查不到"谁传的这份附件"。
     */
    private void logTrigger(Long projectId, String action, String detail, Long operatorUserId) {
        if (operatorUserId == null) {
            operationLogService.log("PROJECT", projectId, "AI_" + action, detail);
            return;
        }
        operationLogService.log("PROJECT", projectId, "AI_" + action, detail,
                operatorUserId, "系统（上传后自动解析）");
    }

    private void markSubmitFailed(AttachmentAiTask task, Attachment att, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        task.setStatus(AttachmentAiTask.FAILED);
        task.setProgress(0);
        task.setErrorMsg(AiJson.truncate(msg, 500));
        taskMapper.updateById(task);
        updateAttachmentIndex(att.getId(), AiScopeResolver.STATUS_FAILED, null, null);
        log.warn("[ai] 提交解析失败 attachmentId={} reason={}", att.getId(), msg);
    }

    // ══════════════════════════════════════════════════════════════════
    // 查询（读）
    // ══════════════════════════════════════════════════════════════════

    /** §9 #4 任务列表（按项目 / 状态过滤 + 分页 + 回源对账）。 */
    public AiPageVO<AiTaskVO> list(String status, Long projectId, int page, int size) {
        syncPendingTasks();

        LambdaQueryWrapper<AttachmentAiTask> qw = new LambdaQueryWrapper<AttachmentAiTask>()
                .orderByDesc(AttachmentAiTask::getId);
        if (StringUtils.hasText(status)) {
            qw.eq(AttachmentAiTask::getStatus, status.trim().toUpperCase());
        }
        if (projectId != null) {
            qw.eq(AttachmentAiTask::getProjectId, projectId);
        }
        List<AttachmentAiTask> all = taskMapper.selectList(qw);
        // 一次批量补齐项目名：任务表冗余了 project_id 但没有项目名，
        // 前端任务队列要显示「所属项目」，逐条查库就是 N+1
        Map<Long, String> projectNames = scopeResolver.projectNames(
                all.stream().map(AttachmentAiTask::getProjectId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        return AiPageVO.of(all.size(),
                slice(all, page, size).stream().map(t -> toVO(t, projectNames)).toList());
    }

    /** §9 #5 任务详情（回源对账该任务）。 */
    public AiTaskVO get(Long taskId) {
        AttachmentAiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(404, "解析任务不存在");
        }
        syncOne(task);
        Map<Long, String> projectNames = scopeResolver.projectNames(
                task.getProjectId() == null ? List.of() : List.of(task.getProjectId()));
        return toVO(task, projectNames);
    }

    /**
     * §9 #8 附件解析状态（批量，上限 200，逗号分隔由 controller 解析）。
     *
     * <p>进度只在「解析中」时给出：结束的任务给 progress=100/null 都会让人误以为还在跑，
     * 所以非解析中一律 null（前端按状态渲染标签，不需要进度）。
     */
    public List<AiAttachmentStatusVO> attachmentStatus(List<Long> attachmentIds) {
        List<Attachment> attachments = scopeResolver.requireAccessibleAll(attachmentIds);
        Map<Long, AttachmentAiTask> latest = scopeResolver.latestTaskByAttachment(
                attachments.stream().map(Attachment::getId).toList());

        List<AiAttachmentStatusVO> out = new ArrayList<>();
        for (Attachment a : attachments) {
            AiAttachmentStatusVO vo = new AiAttachmentStatusVO();
            vo.setAttachmentId(a.getId());
            vo.setIndexStatus(a.getAiIndexStatus());
            vo.setDocId(a.getAiDocId());
            AttachmentAiTask task = latest.get(a.getId());
            if (task != null) {
                if (task.pending()) {
                    vo.setProgress(task.getProgress());
                }
                if (AiScopeResolver.STATUS_FAILED.equals(a.getAiIndexStatus())) {
                    vo.setError(task.getErrorMsg());
                }
            }
            out.add(vo);
        }
        return out;
    }

    /** 附件 id → 最新一条解析任务（文档列表补失败原因用）。 */
    public Map<Long, AttachmentAiTask> latestTaskByAttachment(List<Long> attachmentIds) {
        return scopeResolver.latestTaskByAttachment(attachmentIds);
    }

    /**
     * 未结束任务的回源对账（一次拉取 AI 服务任务列表 + 一次批量回写）。
     *
     * <p>失败只记日志不抛错：读接口要的是「尽量新的状态」，拿不到就用旧的。
     */
    public void syncPendingTasks() {
        if (!props.isEnabled()) {
            return;
        }
        List<AttachmentAiTask> pending = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .in(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING)
                .isNotNull(AttachmentAiTask::getAiTaskId));
        if (pending.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> remote;
        try {
            remote = new LinkedHashMap<>();
            for (Map<String, Object> item : ai.listTasks()) {
                String id = AiJson.text(item, "task_id");
                if (!id.isBlank()) {
                    remote.put(id, item);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[ai] 任务对账跳过（AI 服务不可用）：{}", e.getMessage());
            return;
        }
        for (AttachmentAiTask task : pending) {
            applyRemote(task, remote.get(task.getAiTaskId()));
        }
    }

    /** 单任务对账（详情接口用；只查一个任务，失败同样不抛错）。 */
    private void syncOne(AttachmentAiTask task) {
        if (!props.isEnabled() || !task.pending() || !StringUtils.hasText(task.getAiTaskId())) {
            return;
        }
        try {
            applyRemote(task, ai.getTask(task.getAiTaskId()));
        } catch (RuntimeException e) {
            log.warn("[ai] 任务 {} 对账跳过：{}", task.getId(), e.getMessage());
        }
    }

    /** 把 AI 服务的一条任务状态写回本地（并同步附件索引状态）。 */
    private void applyRemote(AttachmentAiTask task, Map<String, Object> remote) {
        if (remote == null || remote.isEmpty()) {
            // AI 服务侧已经没有这条任务（它只保留最近 100 条）：不能永远挂在 RUNNING
            if (task.pending()) {
                task.setStatus(AttachmentAiTask.FAILED);
                task.setErrorMsg("AI 服务已无该任务记录（可能已重启或记录被清理），请重试解析");
                taskMapper.updateById(task);
                updateAttachmentIndex(task.getAttachmentId(), AiScopeResolver.STATUS_FAILED, null, null);
            }
            return;
        }
        String status = translateStatus(AiJson.text(remote, "status"));
        int progress = (int) Math.round(AiJson.doubleValue(remote, "percent", task.getProgress() == null ? 0 : task.getProgress()));
        String docId = AiJson.nullableText(remote, "doc_id");
        String error = AiJson.nullableText(remote, "error");

        boolean changed = !Objects.equals(status, task.getStatus())
                || !Objects.equals(progress, task.getProgress())
                || !Objects.equals(docId, task.getDocId())
                || !Objects.equals(error, task.getErrorMsg());
        if (!changed) {
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setDocId(docId);
        task.setErrorMsg(AiJson.truncate(error, 500));
        taskMapper.updateById(task);

        // 状态 → 附件索引状态：DONE/READY 才把 doc_id 落下来（前端据此判断"能不能问答"）
        switch (status) {
            case AttachmentAiTask.DONE -> updateAttachmentIndex(task.getAttachmentId(),
                    docId == null ? AiScopeResolver.STATUS_FAILED : AiScopeResolver.STATUS_READY,
                    docId, LocalDateTime.now());
            case AttachmentAiTask.FAILED -> updateAttachmentIndex(task.getAttachmentId(),
                    AiScopeResolver.STATUS_FAILED, null, null);
            case AttachmentAiTask.RUNNING -> updateAttachmentIndex(task.getAttachmentId(),
                    AiScopeResolver.STATUS_PARSING, null, null);
            default -> {
                // QUEUED 不动附件状态：此刻前端应显示"未解析"，等真正开始跑再变
            }
        }
    }

    /** 回写 attachment 的 AI 索引列（用 LambdaUpdateWrapper 以便把 doc_id 置回 NULL）。 */
    private void updateAttachmentIndex(Long attachmentId, String status, String docId, LocalDateTime indexedAt) {
        if (attachmentId == null) {
            return;
        }
        Attachment update = new Attachment();
        update.setId(attachmentId);
        update.setAiIndexStatus(status);
        update.setAiDocId(docId);
        update.setAiIndexedAt(indexedAt);
        // updateById 忽略 null：doc_id/indexed_at 需要能"清空"，
        // 所以当它们为 null 时用 wrapper 显式置 NULL
        attachmentMapper.updateById(update);
        if (docId == null) {
            attachmentMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Attachment>()
                            .eq(Attachment::getId, attachmentId)
                            .set(Attachment::getAiDocId, null));
        }
        if (indexedAt == null) {
            attachmentMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Attachment>()
                            .eq(Attachment::getId, attachmentId)
                            .set(Attachment::getAiIndexedAt, null));
        }
    }

    private AiTaskVO toVO(AttachmentAiTask t) {
        return toVO(t, Map.of());
    }

    /** @param projectNames 预取的项目名（批量查一次，避免列表里 N+1 查库） */
    private AiTaskVO toVO(AttachmentAiTask t, Map<Long, String> projectNames) {
        AiTaskVO vo = new AiTaskVO();
        vo.setTaskId(t.getId());
        vo.setAttachmentId(t.getAttachmentId());
        vo.setFilename(t.getFilename());
        vo.setProjectId(t.getProjectId());
        vo.setProjectName(t.getProjectId() == null ? null : projectNames.get(t.getProjectId()));
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setDocId(t.getDocId());
        vo.setError(t.getErrorMsg());
        vo.setCreatedAt(t.getCreateTime() == null ? null : TS.format(t.getCreateTime()));
        vo.setUpdatedAt(t.getUpdateTime() == null ? null : TS.format(t.getUpdateTime()));
        return vo;
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new BizException(503, props.disabledReason() + "，无法触发解析");
        }
    }

    /**
     * AI 服务任务状态 → §9 枚举。
     *
     * <p>{@code PARSING → RUNNING}（前端枚举里没有 PARSING）、
     * {@code CANCELLED → FAILED}（P0 前端只有四个状态，取消不是独立标签）；
     * 未知值一律当 QUEUED（宁可显示"排队中"也不要显示一个前端不认识的状态导致标签空白）。
     */
    public static String translateStatus(String aiStatus) {
        if (aiStatus == null) {
            return AttachmentAiTask.QUEUED;
        }
        return switch (aiStatus.trim().toUpperCase()) {
            case "PARSING", "RUNNING" -> AttachmentAiTask.RUNNING;
            case "DONE" -> AttachmentAiTask.DONE;
            case "FAILED", "CANCELLED" -> AttachmentAiTask.FAILED;
            default -> AttachmentAiTask.QUEUED;
        };
    }

    /** 取第 page 页（1 起）；越界返回空列表而不是报错（列表页刷新时常见）。 */
    private static <T> List<T> slice(List<T> all, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 200);
        int from = (p - 1) * s;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + s, all.size()));
    }

    /**
     * 服务启动时把残留的「排队中/解析中」任务标失败。
     *
     * <p>与 AI 服务自己的重启策略一致：解析线程已经随进程消失，任务不可能再有结果，
     * 留着只会让前端一直转圈。由 {@code AiTaskRecoveryRunner} 调用，
     * 不在这里用 {@code @PostConstruct}：构造期写库会在测试/上下文初始化阶段触发不必要的 SQL。
     */
    public void markInterruptedAsFailed() {
        List<AttachmentAiTask> stale = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .in(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING));
        for (AttachmentAiTask t : stale) {
            t.setStatus(AttachmentAiTask.FAILED);
            t.setErrorMsg("主系统重启，解析任务状态无法确认，请重试");
            taskMapper.updateById(t);
            updateAttachmentIndex(t.getAttachmentId(), AiScopeResolver.STATUS_FAILED, null, null);
        }
        if (!stale.isEmpty()) {
            log.info("[ai] 已将 {} 条残留解析任务标记为失败（服务重启）", stale.size());
        }
    }
}
