package com.pmgt.ai.common.model;

import com.pmgt.ai.common.config.AiSettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 每模型统一管理：<b>超时 / 在飞上限（信号量）/ 幂等重试 / 指标</b>（P0-1、P0-2/P0-5、P0-6）。
 *
 * <p>为什么把四件事收在一处（而不是各客户端各写一份）：它们共享同一份事实——
 * "这个请求属于哪个模型、上限是多少、能不能重试"。散着写必然出现
 * "OCR 有并发上限、embedding 没有"这种今天的状态（《AI模型调用与任务队列评估》§2.1）。
 *
 * <h2>不变式（改这里之前先读）</h2>
 * <ol>
 *   <li><b>只对幂等调用重试</b>：{@code chat} 不幂等（会重复消耗 4 个副本的稀缺算力，
 *       而且让已经等了 150s 的用户再等一轮）——见 {@link Kind#idempotent()} 与
 *       {@link #callInteractive} 与 {@link #callIdempotent} 的分工；</li>
 *   <li><b>等不到 permit 就快速失败</b>（交互式 1.5s / 批量 60s，可配），
 *       不把 Tomcat 线程挂到用户超时为止；</li>
 *   <li><b>密钥永不进指标/异常</b>：本类只碰模型名与计数，不碰任何凭据。</li>
 * </ol>
 *
 * <h2>指标口径（P0-6，读法写在 {@code ai-backend/README.md}）</h2>
 * <table border="1">
 *   <caption>每模型 5 项</caption>
 *   <tr><th>指标</th><th>类型</th><th>口径</th></tr>
 *   <tr><td>{@code ai.model.requests{model,outcome}}</td><td>Counter</td><td><b>尝试次数</b>（含重试；QPS = 该计数的速率；真实调用数 = attempts − {@code ai.model.retries}）</td></tr>
 *   <tr><td>{@code ai.model.latency{model}}</td><td>Timer</td><td>P50/P95/P99（{@code publishPercentiles}，不依赖配置）</td></tr>
 *   <tr><td>{@code ai.model.requests{outcome=error}} / {@code ai.model.rejected}</td><td>Counter</td><td>错误率 = error / 总数；rejected = 等不到 permit</td></tr>
 *   <tr><td>{@code ai.model.inflight{model}}</td><td>Gauge</td><td>在途数（已用 permit）</td></tr>
 *   <tr><td>{@code ai.model.queue.depth{model}}</td><td>Gauge</td><td>队列深度（等 permit 的线程数）</td></tr>
 * </table>
 */
@Component
public class ModelRegistry {

    /** 四类平台模型（与 {@code AiSettings} 里四组配置一一对应）。 */
    public enum Kind {
        CHAT("chat", false),
        OCR("ocr", true),
        EMBED("embed", true),
        RERANK("rerank", true);

        private final String key;
        private final boolean idempotent;

        Kind(String key, boolean idempotent) {
            this.key = key;
            this.idempotent = idempotent;
        }

        public String key() {
            return key;
        }

        /**
         * 是否幂等：只有它允许自动重试。
         *
         * <p>{@code chat} 不幂等是刻意的——重试会重复消耗 GPU，并让等待翻倍；
         * 正确做法是"明确失败 + 可手动重试"（评估 §7）。
         */
        public boolean idempotent() {
            return idempotent;
        }
    }

    private final AiSettings settings;
    private final MeterRegistry registry;

    /**
     * 本线程当前已持有的 permit（按模型计数）——用于<b>可重入</b>调用。
     *
     * <p>为什么需要：{@link Semaphore} 不可重入，而调用链天然会嵌套
     * （批量编排 {@code EmbedBatcher} → 出口 {@code VecClient.embedTexts}）。
     * 没有这层，8 个 embed 线程各持 1 个 permit 再去抢第 2 个就会永久自锁。
     * 内层调用因此不再取 permit，也不重复计数（指标只记最外层一次）。
     */
    private static final ThreadLocal<Map<Kind, Integer>> HELD =
            ThreadLocal.withInitial(() -> new EnumMap<>(Kind.class));    private final Map<Kind, Semaphore> semaphores = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> permits = new EnumMap<>(Kind.class);
    private final Map<Kind, Timer> latency = new EnumMap<>(Kind.class);
    private final Map<Kind, Counter> ok = new EnumMap<>(Kind.class);
    private final Map<Kind, Counter> error = new EnumMap<>(Kind.class);
    private final Map<Kind, Counter> rejected = new EnumMap<>(Kind.class);
    private final Map<Kind, Counter> retries = new EnumMap<>(Kind.class);

    /** 进程启动时刻（QPS 的分母；用"启动至今均值"而不是瞬时速率，避免抖动误导）。 */
    private final long startedAtMillis = System.currentTimeMillis();

    /** H1 守卫的违规计数（>0 即"有人把 L1/L2 的获取顺序写反了"，必须改代码而不是调参）。 */
    private final java.util.concurrent.atomic.AtomicLong h1Violations =
            new java.util.concurrent.atomic.AtomicLong();

    /** H1 守卫的静态开关：单元测试"只测某一层"时可以关掉（见 {@link #setH1GuardEnabled(boolean)}）。 */
    private static volatile boolean H1_GUARD_ENABLED = true;

    /** 生产装配：有 actuator 时注入真实 {@link MeterRegistry}，没有时自建进程内简单注册表。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelRegistry(AiSettings settings, ObjectProvider<MeterRegistry> registries) {
        this(settings, registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    public ModelRegistry(AiSettings settings, MeterRegistry registry) {
        this.settings = settings;
        this.registry = registry == null ? new SimpleMeterRegistry() : registry;
        for (Kind kind : Kind.values()) {
            int limit = concurrency(kind);
            permits.put(kind, limit);
            semaphores.put(kind, new Semaphore(limit, true));   // 公平：先到先服务，避免批量饿死交互式
            String tag = kind.key();
            latency.put(kind, Timer.builder("ai.model.latency")
                    .tag("model", tag)
                    .description("每模型调用耗时（P50/P95/P99 见 /actuator/metrics/ai.model.latency）")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(this.registry));
            ok.put(kind, Counter.builder("ai.model.requests").tag("model", tag).tag("outcome", "ok")
                    .register(this.registry));
            error.put(kind, Counter.builder("ai.model.requests").tag("model", tag).tag("outcome", "error")
                    .register(this.registry));
            rejected.put(kind, Counter.builder("ai.model.rejected").tag("model", tag)
                    .description("等不到 permit 被拒绝的次数（模型繁忙）").register(this.registry));
            retries.put(kind, Counter.builder("ai.model.retries").tag("model", tag)
                    .description("幂等调用的重试次数").register(this.registry));
            Semaphore semaphore = semaphores.get(kind);
            Gauge.builder("ai.model.inflight", semaphore, s -> limit - s.availablePermits())
                    .tag("model", tag).description("在途调用数").register(this.registry);
            Gauge.builder("ai.model.queue.depth", semaphore, Semaphore::getQueueLength)
                    .tag("model", tag).description("等待 permit 的调用数").register(this.registry);
            Gauge.builder("ai.model.concurrency.limit", semaphore, s -> limit)
                    .tag("model", tag).description("在飞上限").register(this.registry);
        }
    }

    /** 离线/测试装配（不依赖 Spring，也不需要 actuator）：指标落在进程内简单注册表上。 */
    public static ModelRegistry offline(AiSettings settings) {
        return new ModelRegistry(settings, new SimpleMeterRegistry());
    }

    // ══════════════════════════════════════════════════════════════
    // 配置派生
    // ══════════════════════════════════════════════════════════════

    /** 该模型的在飞上限（OCR 沿用既有的 {@code OCR_CONCURRENCY}，不再新开一个键）。 */
    public int concurrency(Kind kind) {
        AiSettings.Limits limits = settings.getLimits();
        return switch (kind) {
            case CHAT -> Math.max(1, limits.getChatConcurrency());
            case OCR -> Math.max(1, settings.getOcr().getConcurrency());
            case EMBED -> Math.max(1, limits.getEmbedConcurrency());
            case RERANK -> Math.max(1, limits.getRerankConcurrency());
        };
    }

    /**
     * 该模型的超时（秒）。<b>OCR 取 {@code ai.ocr.timeout-seconds}（600）</b>，
     * 其余取 {@code ai.limits.*-timeout-seconds}（chat 150 / embed 30 / rerank 20）。
     */
    public double timeoutSeconds(Kind kind) {
        AiSettings.Limits limits = settings.getLimits();
        return switch (kind) {
            case CHAT -> limits.getChatTimeoutSeconds();
            case OCR -> settings.getOcr().getTimeoutSeconds();
            case EMBED -> limits.getEmbedTimeoutSeconds();
            case RERANK -> limits.getRerankTimeoutSeconds();
        };
    }

    public String modelName(Kind kind) {
        return switch (kind) {
            case CHAT -> settings.getLlm().getModel();
            case OCR -> settings.getOcr().getModel();
            case EMBED -> settings.getVec().getEmbedModel();
            case RERANK -> settings.getVec().getRerankModel();
        };
    }

    public int availablePermits(Kind kind) {
        return semaphores.get(kind).availablePermits();
    }

    /** 交互式调用等 permit 的时长（毫秒）。 */
    public long interactiveAcquireMs() {
        return Math.max(0, settings.getLimits().getInteractiveAcquireMs());
    }

    /** 批量调用等 permit 的时长（毫秒）。 */
    public long batchAcquireMs() {
        return Math.max(0, settings.getLimits().getBatchAcquireMs());
    }

    public int queueDepth(Kind kind) {
        return semaphores.get(kind).getQueueLength();
    }

    /** 生产可用的 permit 数（OCR 的批量通道会借用它做"交互式保底"判断）。 */
    public int limitOf(Kind kind) {
        return permits.get(kind);
    }

    /** H1 守卫的违规计数（自检用；**正常恒为 0**，>0 说明有代码把 L1/L2 顺序写反了）。 */
    public long h1Violations() {
        return h1Violations.get();
    }

    /**
     * H1 守卫开关（<b>只给单元测试用</b>）。
     *
     * <p>为什么需要它：L2 的信号量行为（在飞上限、重试、繁忙语义）是**独立于 L1** 的正确性，
     * 那些测试不该被迫先建一个 {@code PipelineGate}；而 H1 本身的判据由
     * {@code PipelineGateTest} 用"顺序反了必须立刻失败"正面覆盖。
     * 生产恒为 {@code true}（默认），没有任何配置项能关掉它。
     */
    public static void setH1GuardEnabled(boolean enabled) {
        H1_GUARD_ENABLED = enabled;
    }

    public static boolean h1GuardEnabled() {
        return H1_GUARD_ENABLED;
    }

    // ══════════════════════════════════════════════════════════════
    // 调用入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 交互式调用（人手在等）：等 permit 的时间短（{@link AiSettings.Limits#getInteractiveAcquireMs()}），
     * 且<b>不重试</b>（只用于不幂等的 chat；重排虽幂等，但它在问答路径上，重试会让用户多等一轮——
     * 走 {@link #callIdempotent} 并传交互式等待时长的调用方自行决定）。
     */
    public <T> T callInteractive(Kind kind, Supplier<T> body) {
        return callOnce(kind, interactiveAcquireMs(), body);
    }

    /**
     * 幂等调用（OCR / embedding / 检索）：等 permit + <b>按配置重试</b>
     * （仅对连接错误 / 超时 / 5xx / 429；4xx 与业务错误不重试）。
     *
     * @param acquireMs 等 permit 的时长（交互式传 {@code interactiveAcquireMs}，批量传 {@code batchAcquireMs}）
     */
    public <T> T callIdempotent(Kind kind, long acquireMs, Supplier<T> body) {
        int maxAttempts = Math.max(1, settings.getLimits().getRetryMax() + 1);
        RuntimeException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return callOnce(kind, acquireMs, body);
            } catch (ModelBusyException e) {
                // 繁忙不是上游故障：重试只会把队列压得更深，立刻如实告诉调用方
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= maxAttempts - 1 || !retryable(e)) {
                    throw e;
                }
                retries.get(kind).increment();
                sleep(backoffMillis(attempt));
            }
        }
        throw last == null ? new IllegalStateException("重试循环异常退出") : last;
    }

    /** 取一次 permit 执行；计时与成败计数都在这里，保证"所有调用都被计入"。 */
    public <T> T callOnce(Kind kind, long acquireMs, Supplier<T> body) {
        // ── H1 守卫（防死锁的"可检不变式"）────────────────────────────
        // 顺序必须全局统一：**先拿 L1 业务 permit，再拿 L2 模型 permit**（设计 §5.1）。
        // 允许"先 L2 再等 L1"就会形成环形等待：批量任务持着 embed permit 去等 L1 批量槽位，
        // 而槽位被同样在等 embed permit 的交互式占着。这里把不变式变成一条**断言**：
        // 已经持有 L2 permit 的调用（可重入的嵌套调用）不受影响，其余的必须已持 L1。
        // 违反时**立刻失败**（比静默死锁好排查太多），并在消息里说明正确的顺序。
        Map<Kind, Integer> held = HELD.get();
        int depth = held.getOrDefault(kind, 0);
        if (depth == 0 && H1_GUARD_ENABLED
                && com.pmgt.ai.common.gate.PipelineGate.currentAdmission() == null) {
            h1Violations.incrementAndGet();
            throw new IllegalStateException("违反 L1→L2 获取顺序（H1）：模型 " + kind.key()
                    + " 的调用发生在本线程持有 L1 业务 permit 之前。"
                    + "正确写法：先 PipelineGate.admit(...) 拿到 L1 permit（任务期间持有，见 H2），"
                    + "再在本方法里取 L2 permit；反之会与「持 L2 等 L1」的任务形成环形等待（死锁）。"
                    + "若这是无需 L1 的旁路调用（如 /health 探活），请显式用 withL1Bypass(...) 包裹。");
        }

        // 同一线程内的嵌套调用直接放行：java.util.concurrent.Semaphore **不可重入**，
        // 若内层再取一次 permit，8 个 embed 线程各持 1 个再抢第 2 个就会自锁（死锁）。
        // 典型嵌套：EmbedBatcher（批量编排）→ VecClient.embedTexts（HTTP 出口）。
        if (depth > 0) {
            held.put(kind, depth + 1);
            try {
                return body.get();
            } finally {
                held.put(kind, depth);
                if (depth == 0) {
                    HELD.remove();
                }
            }
        }

        Semaphore semaphore = semaphores.get(kind);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(Math.max(0, acquireMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException(kind.key(), "模型 " + kind.key() + " 等待槽位被中断");
        }
        if (!acquired) {
            rejected.get(kind).increment();
            throw new ModelBusyException(kind.key(), busyMessage(kind));
        }
        Timer.Sample sample = Timer.start(registry);
        held.put(kind, 1);
        try {
            T value = body.get();
            ok.get(kind).increment();
            return value;
        } catch (RuntimeException e) {
            error.get(kind).increment();
            throw e;
        } finally {
            sample.stop(latency.get(kind));
            HELD.remove();
            semaphore.release();
        }
    }

    /** 繁忙文案：把"上限 + 在飞 + 排队"一次说清，运维不用猜。 */
    public String busyMessage(Kind kind) {
        return "模型 " + kind.key() + "（" + modelName(kind) + "）繁忙：在飞上限 "
                + limitOf(kind) + " 已满，当前排队 " + queueDepth(kind)
                + " 个请求；请稍后重试（不静默排队到超时）。";
    }

    // ══════════════════════════════════════════════════════════════
    // 指标快照（/health?with_metrics=true，仓库风格的自检读法）
    // ══════════════════════════════════════════════════════════════

    /** 每模型 5 项指标的快照（数值口径见类注释）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            Timer timer = latency.get(kind);
            long count = timer.count();
            long errors = (long) error.get(kind).count();
            long rejectedCount = (long) rejected.get(kind).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model_name", modelName(kind));
            item.put("requests", count);
            item.put("errors", errors);
            item.put("rejected", rejectedCount);
            item.put("error_rate", count == 0 ? 0.0 : round4((double) errors / count));
            item.put("retries", (long) retries.get(kind).count());
            item.put("qps_since_start", totalTimeSeconds() == 0.0
                    ? 0.0 : round4(count / totalTimeSeconds()));
            item.put("p50_ms", percentileMs(timer, 0.5));
            item.put("p95_ms", percentileMs(timer, 0.95));
            item.put("p99_ms", percentileMs(timer, 0.99));
            item.put("inflight", limitOf(kind) - availablePermits(kind));
            item.put("queue_depth", queueDepth(kind));
            item.put("limit", limitOf(kind));
            item.put("timeout_seconds", timeoutSeconds(kind));
            out.put(kind.key(), item);
        }
        return out;
    }

    /** 进程启动至今的秒数（QPS 的分母；用"启动至今均值"而不是瞬时速率，避免抖动误导）。 */
    private double totalTimeSeconds() {
        return Math.max(0.001, (System.currentTimeMillis() - startedAtMillis) / 1000.0);
    }

    private static Object percentileMs(Timer timer, double percentile) {
        for (var value : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(value.percentile() - percentile) < 1e-9) {
                return round4(value.value(TimeUnit.MILLISECONDS));
            }
        }
        return null;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ══════════════════════════════════════════════════════════════
    // 重试策略
    // ══════════════════════════════════════════════════════════════

    /**
     * 是否值得重试：<b>只认"重试有意义"的失败</b>。
     *
     * <p>可重试：连接类异常、超时、HTTP 5xx / 429 / 408。
     * 不重试：HTTP 4xx（400 结构错、401 密钥错、404 路径错——重试一百次也一样）、
     * 中断（用户已放弃）、以及任何我们自己的业务错误（如"上游响应里没有向量数据"）。
     *
     * <p>判断依据是**异常类型 + 消息前缀**（各客户端把上游错误统一成
     * {@code "HTTP 502: ..."} / {@code "ConnectException: ..."} 这类可识别的形状，
     * 见 {@code VecClient}/{@code PlatformOcrClient}/{@code OpenSearchIndex}）。
     */
    public static boolean retryable(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.InterruptedIOException
                    || current instanceof java.net.SocketException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage();
            if (message.contains("HTTP 5") || message.contains("HTTP 429") || message.contains("HTTP 408")) {
                return true;
            }
            if (message.contains("HTTP 4")) {
                return false;
            }
            if (message.contains("Timeout") || message.contains("timeout")
                    || message.contains("timed out") || message.contains("ConnectException")
                    || message.contains("集群不可达") || message.contains("Connection refused")
                    || message.contains("Connection reset")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 退避：{@code backoffMs × multiplier^attempt} + 抖动（避免多路重试同时打上去）。 */
    long backoffMillis(int attempt) {
        AiSettings.Limits limits = settings.getLimits();
        double base = limits.getRetryBackoffMs()
                * Math.pow(Math.max(1.0, limits.getRetryBackoffMultiplier()), Math.max(0, attempt));
        long jitter = limits.getRetryJitterMs() <= 0 ? 0
                : (long) (Math.random() * limits.getRetryJitterMs());
        return Math.max(0, (long) base) + jitter;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("retry", "重试等待被中断");
        }
    }

    /** 供日志/自检：四模型的配置摘要（<b>只有数字与模型名，没有凭据</b>）。 */
    public List<Map<String, Object>> configSummary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", kind.key());
            item.put("name", modelName(kind));
            item.put("timeout_seconds", timeoutSeconds(kind));
            item.put("concurrency", limitOf(kind));
            item.put("idempotent", kind.idempotent());
            out.add(item);
        }
        return out;
    }
}
