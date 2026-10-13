package com.pmgt.ai.module.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次流水线运行的<b>贯穿上下文</b>：{@code run_id} + {@code pipeline_token} + 幂等键 + 版本。
 *
 * <p>为什么要有这个对象（而不是到处传三个字符串）：
 * <ol>
 *   <li><b>贯穿</b>：{@code run_id} 必须从解析入口一路带到 5 个阶段边界与终态上报；散着传必然某一处漏传，
 *       表现是"阶段行少一条"，而少的那条恰恰是最需要排障的那次；</li>
 *   <li><b>排障</b>：构造时把 {@code run_id} 写进日志 MDC（{@code ai.pipeline.mdc=true} 时），
 *       一次任务的所有日志就都能按 run 过滤（设计 §4 的可观测要求）；</li>
 *   <li><b>可空语义收口</b>：{@link PipelineReporter#open} 返回的上下文<b>可能是不上报的</b>
 *       （未配置 {@code AI_SERVICE_TOKEN}），此时所有 {@code report*} 方法都是空操作——
 *       调用点因此只需要一次 null 判断，不用在每个阶段边界重复"上报开没开"。</li>
 * </ol>
 *
 * <p>{@code pipeline_token}：<b>绝不写日志</b>（它是主系统给的短时令牌）。
 * 本类只持有它、只在 HTTP 头里用，不进 {@link #toString()}、不进 MDC、不进异常消息。
 */
@Slf4j
public final class RunContext implements AutoCloseable {

    /** MDC 的键名（排障用：按 run_id 过滤一次任务的全部日志）。 */
    public static final String MDC_RUN_ID = "ai.run_id";
    public static final String MDC_TASK_ID = "ai.task_id";

    private final PipelineReporter reporter;
    private final PipelineReportClient client;
    private final String runId;
    private final String pipelineToken;
    private final String taskKey;
    private final String projectId;
    private final PipelineRev.Revisions revisions;
    private final boolean active;

    private final Map<StageKey, Long> stageStartedNanos = new LinkedHashMap<>();
    private final java.util.Set<StageKey> finishedStages = java.util.EnumSet.noneOf(StageKey.class);
    private boolean terminalReported;

    RunContext(PipelineReporter reporter, PipelineReportClient client, String runId, String pipelineToken,
               String taskKey, String projectId, PipelineRev.Revisions revisions, boolean active) {
        this.reporter = reporter;
        this.client = client;
        this.runId = runId;
        this.pipelineToken = pipelineToken;
        this.taskKey = taskKey;
        this.projectId = projectId;
        this.revisions = revisions == null ? PipelineRev.current() : revisions;
        this.active = active;
        if (active && reporter.mdcEnabled()) {
            MDC.put(MDC_RUN_ID, runId == null ? "" : runId);
            if (taskKey != null) {
                MDC.put(MDC_TASK_ID, taskKey);
            }
        }
    }

    /** 是否真的会上报（false = 未配置上报，所有 report* 是空操作）。 */
    public boolean active() {
        return active;
    }

    public String runId() {
        return runId;
    }

    /** 主系统给的短时令牌。<b>只应传给 HTTP 头</b>，任何日志/异常/响应都不得包含它。 */
    public String pipelineToken() {
        return pipelineToken;
    }

    /** 任务级幂等键 {@code sha1(内容 sha1 + pipeline_rev)}。 */
    public String taskKey() {
        return taskKey;
    }

    public String projectId() {
        return projectId;
    }

    public PipelineRev.Revisions revisions() {
        return revisions;
    }

    public String pipelineRev() {
        return revisions.pipelineRev();
    }

    // ── 阶段边界：RUNNING → 终态 ─────────────────────────────────

    /**
     * 阶段开始：上报 {@code RUNNING}（并记录本地起始时刻，供 {@code elapsed_ms} 用）。
     *
     * <p>上报失败<b>不影响</b>主流程（best-effort），本地计时照常。
     */
    public void stageStarted(StageKey stage, String inputDigest) {
        stageStartedNanos.put(stage, System.nanoTime());
        report(stage, StageReport.running(inputDigest));
    }

    /** 阶段结束：上报 {@code DONE}/{@code PARTIAL}（{@code item_failed > 0} 时是 PARTIAL）。 */
    public void stageDone(StageKey stage, int itemCount, int itemFailed, Map<String, Object> counters,
                          String outputDigest) {
        report(stage, StageReport.done(itemCount, itemFailed, elapsedMsOf(stage), counters, outputDigest));
    }

    /** 阶段跳过（<b>必须给原因</b>；本批的 CLEAN 就走这里）。 */
    public void stageSkipped(StageKey stage, String reason) {
        report(stage, StageReport.skipped(reason));
    }

    /** 阶段失败。 */
    public void stageFailed(StageKey stage, String errorMsg) {
        report(stage, StageReport.failed(elapsedMsOf(stage), errorMsg));
    }

    /** 阶段被取消。 */
    public void stageCancelled(StageKey stage, String reason) {
        report(stage, StageReport.cancelled(elapsedMsOf(stage), reason));
    }

    private long elapsedMsOf(StageKey stage) {
        Long started = stageStartedNanos.get(stage);
        return started == null ? 0L : Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private void report(StageKey stage, StageReport report) {
        if (!active) {
            return;
        }
        if (report.status().terminal()) {
            if (!finishedStages.add(stage)) {
                // 同一阶段重复收尾：仍然上报（主系统按 run+stage 幂等更新、attempts+1），
                // 但不重复刷日志——重复收尾通常是"取消 + finally"两条路径都到了。
                log.debug("阶段 {} 重复收尾（主系统幂等，attempts+1）：run_id={}", stage.key(), runId);
            }
        }
        client.stage(runId, stage, report);
    }

    // ── 产物 / 事件 / 终态 ───────────────────────────────────────

    /** 产物上报（只 INSERT）：失败只 WARN，不影响主流程。 */
    public void artifact(ArtifactType type, String content, String objectKey, Integer charCount, String sha1,
                         Integer itemCount, String note) {
        if (!active) {
            return;
        }
        client.artifact(runId, type.body(content, objectKey, charCount, sha1, itemCount, note));
    }

    /** 状态迁移事件。 */
    public void event(String fromStatus, String toStatus, String reason, String operator) {
        if (!active) {
            return;
        }
        client.event(runId, fromStatus, toStatus, reason, operator);
    }

    /**
     * 终态上报（{@code DONE|PARTIAL|FAILED|CANCELLED|DEAD}，<b>以主系统为准</b>）。
     *
     * <p><b>重复调用是幂等的</b>（本地只发一次）——因为失败路径与 finally 路径可能都会调到它，
     * 而"终态以主系统为准"意味着多发一次只会让主系统多做一次幂等更新。
     * 但<b>终态上报失败会重试</b>（见 {@code PipelineReportClient.finish}）。
     */
    public void finish(String status, String errorMsg) {
        if (!active || terminalReported) {
            return;
        }
        terminalReported = true;
        client.finish(runId, status, errorMsg, pipelineRev(), revisions.cleanRev());
    }

    /** 是否已经报过终态（调用方可用它避免"取消后又报 DONE"这类矛盾）。 */
    public boolean terminalReported() {
        return terminalReported;
    }

    @Override
    public void close() {
        if (reporter.mdcEnabled()) {
            MDC.remove(MDC_RUN_ID);
            MDC.remove(MDC_TASK_ID);
        }
    }

    /** <b>刻意不含 pipeline_token</b>（令牌不进日志/异常/自检）。 */
    @Override
    public String toString() {
        return "RunContext{run_id=" + runId + ", task_key=" + taskKey + ", project=" + projectId
                + ", active=" + active + ", pipeline_rev=" + pipelineRev() + "}";
    }
}
