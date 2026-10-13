package com.pmgt.ai.common.model;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.retrieval.VecClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每模型统一层（{@link ModelRegistry}）：分级超时、信号量在飞上限、幂等重试、指标口径。
 *
 * <p>全部离线（没有网络、没有真模型）：要钉的是**并发与重试的语义**，
 * 而不是某个上游的行为。并发用 {@link CyclicBarrier} 让"必须真有 N 个在飞"成为可判定事实。
 */
class ModelRegistryTest {

    /**
     * 本测试类只测 <b>L2</b>（每模型信号量）本身的语义，不建 L1 闸门，因此显式关掉 H1 顺序守卫。
     * H1 的判据（"先拿 L2 再等 L1"必须立刻失败、"先 L1 后 L2"必须跑通）由 {@code PipelineGateTest}
     * 正面覆盖；生产恒为开启（见 {@code ModelRegistry.H1_GUARD_ENABLED}）。
     */
    @org.junit.jupiter.api.BeforeAll
    static void l2OnlyTestsDisableH1Guard() {
        ModelRegistry.setH1GuardEnabled(false);
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreH1Guard() {
        ModelRegistry.setH1GuardEnabled(true);
    }

    private static AiSettings settings() {
        AiSettings settings = new AiSettings();
        settings.getLimits().setRetryBackoffMs(1);      // 测试不等待真实退避（1s → 4s）
        settings.getLimits().setRetryJitterMs(0);
        settings.getLimits().setInteractiveAcquireMs(50);
        settings.getLimits().setBatchAcquireMs(2000);
        return settings;
    }

    // ── P0-1：分级超时 ────────────────────────────────────────────

    @Test
    @DisplayName("超时按模型分级：chat 150 / embed 30 / rerank 20 / ocr 600，且都 < 调用方 180")
    void timeoutsArePerModel() {
        ModelRegistry models = ModelRegistry.offline(new AiSettings());

        assertEquals(150.0, models.timeoutSeconds(ModelRegistry.Kind.CHAT));
        assertEquals(30.0, models.timeoutSeconds(ModelRegistry.Kind.EMBED));
        assertEquals(20.0, models.timeoutSeconds(ModelRegistry.Kind.RERANK));
        assertEquals(600.0, models.timeoutSeconds(ModelRegistry.Kind.OCR));

        double caller = new AiSettings().getLimits().getCallerTimeoutSeconds();
        assertEquals(180.0, caller);
        for (ModelRegistry.Kind kind : List.of(ModelRegistry.Kind.CHAT, ModelRegistry.Kind.EMBED,
                ModelRegistry.Kind.RERANK)) {
            assertTrue(models.timeoutSeconds(kind) < caller,
                    kind + " 的超时必须 < 调用方预算，否则用户已超时、槽位仍被占用");
        }
    }

    @Test
    @DisplayName("在飞上限：chat 4 / ocr 12（沿用 OCR_CONCURRENCY）/ embed 8 / rerank 2")
    void concurrencyPerModel() {
        ModelRegistry models = ModelRegistry.offline(new AiSettings());
        assertEquals(4, models.concurrency(ModelRegistry.Kind.CHAT));
        assertEquals(12, models.concurrency(ModelRegistry.Kind.OCR));
        assertEquals(8, models.concurrency(ModelRegistry.Kind.EMBED));
        assertEquals(2, models.concurrency(ModelRegistry.Kind.RERANK));
    }

    @Test
    @DisplayName("只有 OCR/embedding/rerank 幂等可重试；chat 不幂等（永不自动重试）")
    void onlyIdempotentModelsAreRetryable() {
        assertFalse(ModelRegistry.Kind.CHAT.idempotent());
        assertTrue(ModelRegistry.Kind.OCR.idempotent());
        assertTrue(ModelRegistry.Kind.EMBED.idempotent());
        assertTrue(ModelRegistry.Kind.RERANK.idempotent());
    }

    // ── P0-5：信号量确实限制并发（最大在途数）──────────────────────

    @Test
    @DisplayName("信号量限制并发：embed 上限 2 时，8 个并发调用最多只有 2 个在飞")
    void semaphoreCapsInflightAtConfiguredLimit() throws Exception {
        AiSettings settings = settings();
        settings.getLimits().setEmbedConcurrency(2);
        ModelRegistry models = ModelRegistry.offline(settings);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        // 屏障 2：只有"真的有 2 个在飞"才能通过；若信号量放开到 3+，maxInFlight 断言会先失败
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> models.callIdempotent(ModelRegistry.Kind.EMBED, 5000, () -> {
                    int now = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(now, Math::max);
                    try {
                        barrier.await(3, TimeUnit.SECONDS);
                        return now;
                    } catch (Exception e) {
                        throw new VecClient.VecException("屏障超时（并发没跑起来）：" + e.getMessage());
                    } finally {
                        inFlight.decrementAndGet();
                    }
                })));
            }
            for (Future<Integer> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, maxInFlight.get(), "embed 在飞上限必须是 2（既不能少到没有并发，也不能多）");
        assertEquals(2, models.availablePermits(ModelRegistry.Kind.EMBED), "调用结束后 permit 必须全部归还");
        assertEquals(0, models.queueDepth(ModelRegistry.Kind.EMBED));
    }

    @Test
    @DisplayName("等不到 permit：明确'模型繁忙'快速失败（不排队到用户超时），并被计入 rejected")
    void busyFailsFastWithClearMessage() throws Exception {
        AiSettings settings = settings();
        settings.getLimits().setRerankConcurrency(1);
        ModelRegistry models = ModelRegistry.offline(settings);

        CountDownLatch hold = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> occupying = pool.submit(() -> models.callIdempotent(ModelRegistry.Kind.RERANK, 1000, () -> {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            // 等第一个确实拿到 permit
            long deadline = System.currentTimeMillis() + 3000;
            while (models.availablePermits(ModelRegistry.Kind.RERANK) != 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            ModelBusyException error = assertThrows(ModelBusyException.class,
                    () -> models.callInteractive(ModelRegistry.Kind.RERANK, () -> "不该执行"));
            assertTrue(error.getMessage().contains("繁忙"), error.getMessage());
            assertTrue(error.getMessage().contains("上限"), error.getMessage());
            assertEquals("rerank", error.getModel());
            Map<String, Object> snapshot = models.snapshot();
            @SuppressWarnings("unchecked")
            Map<String, Object> rerank = (Map<String, Object>) snapshot.get("rerank");
            assertEquals(1L, rerank.get("rejected"));
            hold.countDown();
            occupying.get(5, TimeUnit.SECONDS);
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("可重入：同线程嵌套调用不会自锁（Semaphore 不可重入，这里必须放行内层）")
    void nestedCallOnSameThreadDoesNotDeadlock() {
        AiSettings settings = settings();
        settings.getLimits().setEmbedConcurrency(1);
        ModelRegistry models = ModelRegistry.offline(settings);

        String value = models.callIdempotent(ModelRegistry.Kind.EMBED, 1000,
                () -> models.callIdempotent(ModelRegistry.Kind.EMBED, 1000, () -> "ok"));
        assertEquals("ok", value);
        assertEquals(1, models.availablePermits(ModelRegistry.Kind.EMBED));
    }

    // ── P0-2：幂等重试 / 不盲目重试 ───────────────────────────────

    @Test
    @DisplayName("幂等调用：5xx 按配置重试（2 次）后成功，并计入 ai.model.retries")
    void idempotentCallRetriesOn5xx() {
        AiSettings settings = settings();
        settings.getLimits().setRetryMax(2);
        ModelRegistry models = ModelRegistry.offline(settings);

        AtomicInteger attempts = new AtomicInteger();
        String value = models.callIdempotent(ModelRegistry.Kind.EMBED, 1000, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new VecClient.VecException("HTTP 502: embeddings upstream error");
            }
            return "ok";
        });

        assertEquals("ok", value);
        assertEquals(3, attempts.get(), "1 次原始 + 2 次重试");
        @SuppressWarnings("unchecked")
        Map<String, Object> embed = (Map<String, Object>) models.snapshot().get("embed");
        assertEquals(2L, embed.get("retries"));
        // 口径说明：requests 计的是**尝试次数**（含重试），所以这里 3 次；其中 2 次失败计入 error_rate。
        // 真要区分"调用次数"，看 ai.model.retries（额外的尝试）与之相减即可。
        assertEquals(3L, embed.get("requests"));
        assertEquals(2L, embed.get("errors"), "失败的两次尝试要计入错误率");
    }

    @Test
    @DisplayName("4xx 不重试（结构/鉴权/路径错，重试一百次也一样）")
    void clientErrorsAreNotRetried() {
        ModelRegistry models = ModelRegistry.offline(settings());
        AtomicInteger attempts = new AtomicInteger();
        VecClient.VecException error = assertThrows(VecClient.VecException.class,
                () -> models.callIdempotent(ModelRegistry.Kind.EMBED, 1000, () -> {
                    attempts.incrementAndGet();
                    throw new VecClient.VecException("HTTP 401: invalid api key");
                }));
        assertEquals(1, attempts.get());
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    @DisplayName("chat 永不重试：callInteractive 一次失败就是失败（不重复消耗 GPU）")
    void chatIsNeverRetried() {
        ModelRegistry models = ModelRegistry.offline(settings());
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(VecClient.VecException.class,
                () -> models.callInteractive(ModelRegistry.Kind.CHAT, () -> {
                    attempts.incrementAndGet();
                    throw new VecClient.VecException("HTTP 502: upstream error");
                }));
        assertEquals(1, attempts.get(), "chat 不幂等：即使是 5xx 也不许自动重试");
    }

    @Test
    @DisplayName("可重试判据：连接错误/超时/5xx/429 可重试；4xx 与业务错误不可")
    void retryableClassification() {
        assertTrue(ModelRegistry.retryable(new java.net.ConnectException("Connection refused")));
        assertTrue(ModelRegistry.retryable(new java.net.http.HttpTimeoutException("timed out")));
        assertTrue(ModelRegistry.retryable(new VecClient.VecException("HTTP 500: upstream")));
        assertTrue(ModelRegistry.retryable(new VecClient.VecException("HTTP 429: too many requests")));
        assertTrue(ModelRegistry.retryable(new VecClient.VecException("集群不可达（http://x:9200）：ConnectException")));
        assertTrue(ModelRegistry.retryable(new VecClient.VecException(
                "上游响应里没有向量数据", new java.net.SocketTimeoutException("read timed out"))));
        assertFalse(ModelRegistry.retryable(new VecClient.VecException("HTTP 400: bad request")));
        assertFalse(ModelRegistry.retryable(new VecClient.VecException("HTTP 401: invalid api key")));
        assertFalse(ModelRegistry.retryable(new VecClient.VecException("上游响应里没有向量数据")));
        assertFalse(ModelRegistry.retryable(new IllegalArgumentException("参数错")));
    }

    // ── P0-6：指标口径 ───────────────────────────────────────────

    @Test
    @DisplayName("指标快照：请求数/错误率/分位/在途/排队/上限都在，且不包含任何凭据")
    void snapshotExposesFiveMetricsPerModel() {
        ModelRegistry models = ModelRegistry.offline(settings());
        models.callIdempotent(ModelRegistry.Kind.EMBED, 1000, () -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });
        models.callInteractive(ModelRegistry.Kind.CHAT, () -> "ok");

        Map<String, Object> snapshot = models.snapshot();
        for (String model : List.of("chat", "ocr", "embed", "rerank")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) snapshot.get(model);
            assertTrue(item.containsKey("requests"), model);
            assertTrue(item.containsKey("errors"), model);
            assertTrue(item.containsKey("error_rate"), model);
            assertTrue(item.containsKey("p50_ms"), model);
            assertTrue(item.containsKey("p95_ms"), model);
            assertTrue(item.containsKey("p99_ms"), model);
            assertTrue(item.containsKey("inflight"), model);
            assertTrue(item.containsKey("queue_depth"), model);
            assertTrue(item.containsKey("limit"), model);
            assertTrue(item.containsKey("qps_since_start"), model);
        }
        assertFalse(snapshot.toString().contains("sk-"), "指标里不能出现密钥");
    }
}
