package com.pmgt.ai.module.pipeline;

import com.pmgt.ai.common.config.AiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 阶段上报的门面（设计 §4 的 AI 侧入口）：给解析/重建链路发一个 {@link RunContext}，
 * 由它承担"五阶段边界上报 + 产物 + 事件 + 终态"。
 *
 * <h2>为什么是门面而不是让调用方直接用客户端</h2>
 * <ol>
 *   <li><b>上报开关只判一次</b>：{@code ai.pipeline.enabled} + 地址 + 令牌任一缺失 ⇒ 返回一个
 *       {@link RunContext#active()} 为 false 的上下文，所有上报变成空操作。
 *       调用点因此不需要在每个阶段边界重复判断（漏判的后果是"没配也外呼"，或"配了却静默不上报"）；</li>
 *   <li><b>run_id 生成规则只在一处</b>：主系统给的 {@code run_id} 优先（{@code /claim} 路径），
 *       没有时用 {@code upload-<taskId>} 这类<b>可读前缀 + 随机后缀</b>——
 *       前缀让运维一眼看出这是 AI 侧自建的上报标识（本批 {@code /claim} 尚未接入）；</li>
 *   <li><b>幂等键口径唯一</b>：{@code task_key = sha1(内容 sha1 + pipeline_rev)}（设计 §3.3.3），
 *       由这里一处算，避免各调用点各写一套（那正是"同键不同值"的来源）。</li>
 * </ol>
 *
 * <h2>上报失败绝不影响主流程</h2>
 * <p>所有上报都是 best-effort：失败只 WARN + 计数 {@code ai.pipeline.report_failed}；
 * <b>只有终态</b>（{@code /finish}）会重试（见 {@link PipelineReportClient#finish}）。
 * 这条纪律的理由：阶段行缺失只是"任务流少一格"，而解析本身失败是用户可见的功能故障——
 * 不能因为观测手段坏了就把功能一起打挂。
 */
@Slf4j
@Component
public class PipelineReporter {

    /** 本批尚无清洗阶段（G1 属 P1-α）——CLEAN 阶段如实上报 SKIPPED，原因就用这句话。 */
    public static final String CLEAN_SKIPPED_REASON =
            "本批尚未实现清洗阶段（G1 属 P1-α）：切片输入为解析后的原始文本，clean_rev=0 表示未经清洗。"
                    + "启用清洗后本阶段将上报 DONE/PARTIAL 并写入 clean_diff 产物。";

    private final AiSettings settings;
    private final PipelineReportClient client;

    public PipelineReporter(AiSettings settings, PipelineReportClient client) {
        this.settings = settings;
        this.client = client;
    }

    /**
     * 开一次运行上下文（<b>必须在解析任务开始时调用一次</b>，后续全程复用同一个实例）。
     *
     * @param runId        主系统给的 run_id；{@code null}/空 ⇒ 用 {@code prefix + 随机} 自建
     * @param pipelineToken 主系统给的短时令牌（可为 {@code null}；**不进日志**）
     * @param contentSha1  原始内容 sha1（用于 {@code task_key}；可为 {@code null}）
     * @param projectId    项目 id（公平调度与过滤维度；可为 {@code null}）
     */
    public RunContext open(String runId, String pipelineToken, String contentSha1, String projectId) {
        String effectiveRunId = runId == null || runId.isBlank()
                ? "upload-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : runId.strip();
        PipelineRev.Revisions revisions = PipelineRev.current();
        String taskKey = contentSha1 == null || contentSha1.isBlank() ? null : PipelineRev.taskKey(contentSha1);
        return new RunContext(this, client, effectiveRunId, pipelineToken, taskKey, projectId, revisions,
                client.enabled());
    }

    /** 供 {@link RunContext} 读（MDC 开关）。 */
    boolean mdcEnabled() {
        AiSettings.Pipeline pipeline = settings.getPipeline();
        return pipeline == null || pipeline.isMdc();
    }

    /** 上报是否启用（自检用；**只报"配没配"**）。 */
    public boolean enabled() {
        return client.enabled();
    }

    /** 客户端（claim/heartbeat/release 这三条不由 {@link RunContext} 承担）。 */
    public PipelineReportClient client() {
        return client;
    }

    /** 诚实失败原因登记：本批 CLEAN 阶段的固定措辞（集中一处，避免各处措辞漂移）。 */
    public static String cleanSkippedReason() {
        return CLEAN_SKIPPED_REASON;
    }

    /** 自检摘要（含上报开关与版本口径，**不含令牌**）。 */
    public Map<String, Object> configSummary() {
        Map<String, Object> out = new LinkedHashMap<>(client.configSummary());
        out.put("reporter_enabled", enabled());
        out.put("clean_stage", "SKIPPED（" + CLEAN_SKIPPED_REASON.substring(0, 24) + "…）");
        out.put("stages", java.util.Arrays.stream(StageKey.reported()).map(StageKey::key).toList());
        return out;
    }
}
