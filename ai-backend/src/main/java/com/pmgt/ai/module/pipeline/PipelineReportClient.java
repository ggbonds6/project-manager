package com.pmgt.ai.module.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主系统流水线接口的 HTTP 出口（契约冻结在设计 §4.4.3，前缀 {@code /api/ai/pipeline/**}）。
 *
 * <h2>七条调用（AI 侧要用到的全部）</h2>
 * <table border="1">
 *   <caption>逐条与契约对应</caption>
 *   <tr><th>方法</th><th>路径</th><th>用途</th></tr>
 *   <tr><td>POST</td><td>{@code /claim}</td><td>领取任务（AI 不再自建持久队列）</td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/heartbeat}</td><td>30s 续约；<b>409 ⇒ 立即停止该任务并放回</b></td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/release}</td><td>放回队列</td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/stages/{stageKey}}</td><td>阶段上报（<b>幂等</b>，attempts+1）</td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/artifacts}</td><td>产物上报（<b>只 INSERT</b>）</td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/events}</td><td>状态迁移事件</td></tr>
 *   <tr><td>POST</td><td>{@code /runs/{runId}/finish}</td><td>终态（<b>以主系统为准</b>）</td></tr>
 * </table>
 *
 * <h2>三条纪律（写在这儿，别在下游各处重复）</h2>
 * <ol>
 *   <li><b>令牌只在这里出现</b>：{@code X-AI-Service-Token} 取自 {@code AI_SERVICE_TOKEN}，
 *       <b>绝不写进日志、异常、指标标签或测试断言</b>（与 {@code OPENSEARCH_PASSWORD} 同一条纪律）。
 *       出错时只报状态码与响应摘要，且响应摘要先做长度截断 + 去掉可能的令牌回显。</li>
 *   <li><b>best-effort 不阻断主流程</b>：所有方法返回 {@code boolean}（是否送达），
 *       失败只 WARN + 计数 {@code ai.pipeline.report_failed}；调用方不因上报失败而改变解析结果。</li>
 *   <li><b>终态要重试</b>：{@link #finish} 内部按 {@code ai.pipeline.finish-retries} 重试，
 *       因为终态丢失会让任务永远停在 {@code RUNNING}（主系统是事实源，丢了就是脏状态）。</li>
 * </ol>
 */
@Slf4j
@Component
public class PipelineReportClient {

    /** 上报失败的计数（best-effort 的可见性；>0 说明主系统侧有问题，但不影响解析）。 */
    public static final String METRIC_FAILED = "ai.pipeline.report_failed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettings settings;
    private final MeterRegistry registry;
    private final Counter failed;
    private final Counter sent;
    private final Map<String, Timer> latency = new ConcurrentHashMap<>();
    private final Map<String, Counter> failedByCall = new ConcurrentHashMap<>();

    /**
     * HTTP 出口：<b>单例共享</b>的 {@link HttpClient}（与 {@code OpenSearchIndex} 同一结论——
     * 每次请求现建客户端会让连接池与 keep-alive 完全不生效）。
     * 显式 {@code HTTP_1_1}（口径与 OpenSearch 出口一致）、{@code followRedirects(NEVER)}。
     * 构造它<b>不发起任何请求</b>（未启用上报时不碰网络）。
     */
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public PipelineReportClient(AiSettings settings,
                                org.springframework.beans.factory.ObjectProvider<MeterRegistry> registries) {
        this(settings, registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    public PipelineReportClient(AiSettings settings, MeterRegistry registry) {
        this.settings = settings;
        this.registry = registry == null ? new SimpleMeterRegistry() : registry;
        this.failed = Counter.builder(METRIC_FAILED)
                .description("阶段/产物/事件上报失败次数（best-effort：失败只 WARN，不影响解析结果；"
                        + "持续 >0 说明主系统接口或令牌有问题）")
                .register(this.registry);
        this.sent = Counter.builder("ai.pipeline.report_sent")
                .description("上报成功次数（与 " + METRIC_FAILED + " 配对看送达率）")
                .register(this.registry);
        Duration connect = Duration.ofMillis(Math.max(200,
                (long) (Math.min(2.0, timeoutSeconds()) * 1000)));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connect)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    // 契约七条
    // ══════════════════════════════════════════════════════════════

    /**
     * 领取任务（契约 1）。成功返回 {@link ClaimedRun}，失败（含未配置上报）返回 {@code null}。
     *
     * <p>请求体：{@code {task_type?, limit?, claimed_by, lease_seconds?}}；
     * 空任务（主系统没有可领的任务）返回 {@code null}——那是<b>正常情况</b>，不是错误。
     */
    public ClaimedRun claim(String taskType, Integer limit, String claimedBy, Integer leaseSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (taskType != null && !taskType.isBlank()) {
            body.put("task_type", taskType);
        }
        if (limit != null) {
            body.put("limit", limit);
        }
        body.put("claimed_by", claimedBy == null || claimedBy.isBlank() ? localNodeId() : claimedBy);
        body.put("lease_seconds", leaseSeconds == null ? pipeline().getLeaseSeconds() : leaseSeconds);

        JsonNode data = post("/claim", body, "claim", false);
        if (data == null || data.isNull() || data.isMissingNode()) {
            return null;
        }
        return new ClaimedRun(
                text(data, "run_id"),
                data.path("task_id").isMissingNode() ? null : data.path("task_id").asLong(),
                data.path("attachment_id").isMissingNode() ? null : data.path("attachment_id").asLong(),
                text(data, "task_key"),
                text(data, "pipeline_token"),
                text(data, "lease_expires_at"));
    }

    /** 心跳续约（契约 2）。<b>返回 {@code CONFLICT} 表示租约已被别人拿走 ⇒ 调用方必须立即停止该任务并放回。</b> */
    public Result heartbeat(String runId) {
        return postExchange("/runs/" + runId + "/heartbeat", Map.of(), "heartbeat", false).result();
    }

    /** 放回队列（契约 3）。 */
    public boolean release(String runId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        return post("/runs/" + runId + "/release", body, "release", false) != null;
    }

    /** 阶段上报（契约 4，<b>幂等</b>：同 run+stage 更新同一行、attempts+1）。 */
    public boolean stage(String runId, StageKey stage, StageReport report) {
        return post("/runs/" + runId + "/stages/" + stage.key(), report.toBody(), "stage", false) != null;
    }

    /** 产物上报（契约 5，<b>只 INSERT</b>：同一阶段重跑生成新行、artifact_rev 递增）。 */
    public boolean artifact(String runId, Map<String, Object> body) {
        return post("/runs/" + runId + "/artifacts", body, "artifact", false) != null;
    }

    /** 状态迁移事件（契约 6）。 */
    public boolean event(String runId, String fromStatus, String toStatus, String reason, String operator) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_status", fromStatus);
        body.put("to_status", toStatus);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        if (operator != null && !operator.isBlank()) {
            body.put("operator", operator);
        }
        return post("/runs/" + runId + "/events", body, "event", false) != null;
    }

    /**
     * 终态上报（契约 7）。<b>会重试</b>（{@code ai.pipeline.finish-retries}，默认 2 ⇒ 共 3 次尝试）：
     * 终态丢失会让任务永远停在 {@code RUNNING}，而终态<b>以主系统为准</b>。
     */
    public boolean finish(String runId, String status, String errorMsg, String pipelineRev, Integer cleanRev) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (errorMsg != null && !errorMsg.isBlank()) {
            body.put("error_msg", errorMsg);
        }
        if (pipelineRev != null && !pipelineRev.isBlank()) {
            body.put("pipeline_rev", pipelineRev);
        }
        if (cleanRev != null) {
            body.put("clean_rev", cleanRev);
        }
        int attempts = Math.max(1, pipeline().getFinishRetries() + 1);
        long backoff = Math.max(0, pipeline().getRetryBackoffMs());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (post("/runs/" + runId + "/finish", body, "finish", true) != null) {
                return true;
            }
            if (attempt < attempts) {
                log.warn("终态上报失败，将重试（第 {}/{} 次）：run_id={} status={}", attempt, attempts, runId, status);
                sleep(backoff * attempt);
            }
        }
        log.error("终态上报最终失败（已重试 {} 次）：run_id={} status={}；主系统侧该任务可能停在 RUNNING，"
                + "需要按 run_id 人工核对", attempts, runId, status);
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 内部：HTTP + 拆信封 + 指标
    // ══════════════════════════════════════════════════════════════

    /** 上报结果（心跳要看状态码 409，所以不能只返回 boolean）。 */
    public record Result(boolean ok, int status, String detail) {

        public boolean conflict() {
            return status == 409;
        }
    }

    /**
     * 一次上报的完整结果：状态 + 拆过信封的 {@code data}。
     *
     * <p>为什么不把 {@code data} 存在字段里再读：上报调用可能来自<b>多个线程</b>
     * （解析线程、重建线程、心跳线程），共享字段会让 A 拿到 B 的响应 ⇒ 领错任务。
     * 返回值里带着走就没有这个类别的 bug。
     */
    private record Exchange(Result result, JsonNode data) {
    }

    /** 领取到的任务（契约 1 的返回体）。 */
    public record ClaimedRun(String runId, Long taskId, Long attachmentId, String taskKey,
                             String pipelineToken, String leaseExpiresAt) {
    }

    private JsonNode post(String path, Map<String, Object> body, String call, boolean terminal) {
        return postExchange(path, body, call, terminal).data();
    }

    private Exchange postExchange(String path, Map<String, Object> body, String call, boolean terminal) {
        AiSettings.Pipeline pipeline = pipeline();
        if (!enabled(pipeline)) {
            // 未配置 = 不上报（而不是"上报失败"）。刻意不计数：否则未接入的环境里指标会一直涨。
            return new Exchange(new Result(false, 0, "上报未启用（缺 ai.pipeline.base-url 或 AI_SERVICE_TOKEN）"),
                    null);
        }
        String url = baseUrl(pipeline) + "/api/ai/pipeline" + path;
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body == null ? Map.of() : body);
        } catch (Exception e) {
            return new Exchange(fail(call, 0, "请求体序列化失败：" + e.getClass().getSimpleName(), terminal), null);
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(200, (long) (timeoutSeconds() * 1000))))
                    .header("Content-Type", "application/json")
                    // ⚠️ 令牌只在这一行出现；下方所有日志/异常都不带它
                    .header("X-AI-Service-Token", pipeline.getServiceToken() == null
                            ? "" : pipeline.getServiceToken())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            timer(call).record(elapsedMs(started), java.util.concurrent.TimeUnit.MILLISECONDS);
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                sent.increment();
                return new Exchange(new Result(true, code, ""), unwrap(response.body()));
            }
            return new Exchange(fail(call, code, "HTTP " + code + "：" + safe(response.body()), terminal), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exchange(fail(call, 0, "被中断", terminal), null);
        } catch (Exception e) {
            return new Exchange(fail(call, 0, e.getClass().getSimpleName() + "：" + safe(e.getMessage()), terminal),
                    null);
        }
    }

    private Result fail(String call, int status, String detail, boolean terminal) {
        failed.increment();
        failedByCall.computeIfAbsent(call, key -> Counter.builder(METRIC_FAILED + ".by_call")
                .tag("call", key)
                .description("按调用名的上报失败数（claim/heartbeat/release/stage/artifact/event/finish）")
                .register(registry)).increment();
        if (terminal) {
            // 终态失败由 finish 的重试循环负责"升级为 ERROR"，这里只记 WARN 避免重复刷屏
            log.warn("终态上报未送达（{}/finish）：{}", call, detail);
        } else {
            log.warn("流水线上报失败（best-effort，不影响解析）：call={} {}", call, detail);
        }
        return new Result(false, status, detail);
    }

    /**
     * 拆 {@code {code,message,data}} 信封（契约冻结的响应体形状）。
     *
     * <p>兼容两种形态：① 标准信封（取 {@code data}）；② 直接返回裸对象（老接口风格）——
     * 后者原样返回，这样"主系统先上、AI 后上"或反过来都不会互相打挂。
     */
    private JsonNode unwrap(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.isObject() && root.has("data")) {
                return root.path("data");
            }
            return root;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private String baseUrl(AiSettings.Pipeline pipeline) {
        String url = pipeline.getBaseUrl() == null ? "" : pipeline.getBaseUrl().strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private boolean enabled(AiSettings.Pipeline pipeline) {
        return pipeline.isEnabled()
                && pipeline.getServiceToken() != null && !pipeline.getServiceToken().isBlank()
                && pipeline.getBaseUrl() != null && !pipeline.getBaseUrl().isBlank();
    }

    /** 上报是否可用（自检用：只报"配没配"，绝不含令牌本体）。 */
    public boolean enabled() {
        return enabled(pipeline());
    }

    private AiSettings.Pipeline pipeline() {
        AiSettings.Pipeline pipeline = settings.getPipeline();
        return pipeline == null ? new AiSettings.Pipeline() : pipeline;
    }

    private double timeoutSeconds() {
        return Math.max(0.2, pipeline().getTimeoutSeconds());
    }

    private Timer timer(String call) {
        return latency.computeIfAbsent(call, key -> Timer.builder("ai.pipeline.report_latency")
                .tag("call", key)
                .description("上报主系统的耗时（P50/P95/P99）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    /** 响应摘要：限制长度，杜绝"把整页 HTML/令牌回显进日志"。 */
    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 领养者标识：主机名 + 进程 id（与主系统 {@code claimed_by} 的口径一致）。 */
    public static String localNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "/" + ProcessHandle.current().pid();
    }

    /** 自检摘要（**不含令牌**）。 */
    public Map<String, Object> configSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        AiSettings.Pipeline pipeline = pipeline();
        out.put("enabled", enabled(pipeline));
        out.put("base_url_configured", pipeline.getBaseUrl() != null && !pipeline.getBaseUrl().isBlank());
        out.put("service_token_configured",
                pipeline.getServiceToken() != null && !pipeline.getServiceToken().isBlank());
        out.put("timeout_seconds", timeoutSeconds());
        out.put("finish_retries", pipeline.getFinishRetries());
        out.put("heartbeat_seconds", pipeline.getHeartbeatSeconds());
        out.put("lease_seconds", pipeline.getLeaseSeconds());
        out.put("chunk_key_format", pipeline.getChunkKeyFormat());
        out.put("clean_rev", pipeline.getCleanRev());
        out.put("chunk_rev", pipeline.getChunkRev());
        out.put("parse_rev", pipeline.getParseRev());
        out.put("pipeline_rev", PipelineRev.pipelineRev());
        out.put("node_id", localNodeId());
        out.put("sent", (long) sent.count());
        out.put("failed", (long) failed.count());
        return out;
    }
}
