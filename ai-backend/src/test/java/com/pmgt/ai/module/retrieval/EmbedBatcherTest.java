package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发批量向量化（{@link EmbedBatcher}，P0-3）：<b>批 32 + 并发 8</b>，且保序。
 *
 * <p>① 并发上限用 {@link CyclicBarrier} 钉成可判定事实：只有"真的有 N 个请求在飞"才能通过屏障，
 * 所以既证明"确实并发"（旧实现是串行 for 循环），也证明"没超过信号量"；
 * ② 保序：向量与文本错配比报错严重得多（检索结果看起来正常却全错），所以按批下标写回这件事必须有测试。
 */
class EmbedBatcherTest {

    /** 记录在飞峰值 + 按文本返回可区分的向量；闸门用来强制"前 N 个请求必须同时在飞"。 */
    private static final class CountingVec implements VecApi {

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Integer> batchSizes = java.util.Collections.synchronizedList(new ArrayList<>());
        private final int parties;
        /** 只有前 {@code parties} 个请求参与闸门（避免最后一批不满时把闸门等超时）。 */
        private final java.util.concurrent.CountDownLatch gate;
        private final AtomicInteger arrived = new AtomicInteger();

        CountingVec(int parties) {
            this.parties = parties;
            this.gate = new java.util.concurrent.CountDownLatch(parties);
        }

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            batchSizes.add(texts.size());
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                if (parties > 1 && arrived.incrementAndGet() <= parties) {
                    gate.countDown();
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new VecClient.VecException("闸门超时：并发没达到 " + parties);
                    }
                }
                List<float[]> out = new ArrayList<>();
                for (String text : texts) {
                    // 向量 = [文本编号, 1]，编号刻意保持在 float 可精确表示的范围内，
                    // 于是"谁被挂错"一眼能看出来（hashCode 会丢精度，不能用来做相等断言）
                    out.add(new float[] {idOf(text), 1f});
                }
                return out;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VecClient.VecException("等待闸门被中断", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        /** 文本编号（{@code 切片-13} → 13；{@code 文本7} → 7）：小于 2^24，float 精确可表示。 */
        private static float idOf(String text) {
            String digits = text.replaceAll("\\D+", "");
            return digits.isEmpty() ? 0f : (float) Integer.parseInt(digits);
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            return List.of();
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "fake", List.of());
        }

        @Override
        public double cosine(float[] a, float[] b) {
            return 0;
        }
    }

    private static AiSettings settings(int embedConcurrency) {
        AiSettings settings = new AiSettings();
        settings.getLimits().setEmbedConcurrency(embedConcurrency);
        settings.getLimits().setRetryBackoffMs(1);
        settings.getLimits().setRetryJitterMs(0);
        settings.getLimits().setBatchAcquireMs(10000);
        return settings;
    }

    @Test
    @DisplayName("批 32 + 并发 4：13 批（417 条）确实 4 个在飞，且没有一个请求超过上限")
    void concurrencyIsCappedAndActuallyParallel() {
        int concurrency = 4;
        AiSettings settings = settings(concurrency);
        CountingVec vec = new CountingVec(concurrency);           // 屏障 4：少于 4 个并发就会超时
        EmbedBatcher batcher = new EmbedBatcher(vec, ModelRegistry.offline(settings));
        try {
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < EmbedBatcher.BATCH * 13 + 1; i++) {   // 417 条 → 14 批
                texts.add("切片-" + i);
            }
            List<float[]> vectors = batcher.embedAll(texts);

            assertEquals(texts.size(), vectors.size());
            assertEquals(concurrency, vec.maxInFlight.get(),
                    "并发必须真的达到上限（旧实现是串行 for 循环，这里会是 1）");
            assertEquals(14, vec.batchSizes.size(), "417 条 / 批 32 = 14 批");
            assertTrue(vec.batchSizes.stream().allMatch(size -> size <= EmbedBatcher.BATCH));
        } finally {
            batcher.shutdown();
        }
    }

    @Test
    @DisplayName("保序：返回的向量与入参文本一一对应（错配比报错严重）")
    void vectorsStayPairedWithTheirTexts() {
        AiSettings settings = settings(2);
        CountingVec vec = new CountingVec(1);
        EmbedBatcher batcher = new EmbedBatcher(vec, ModelRegistry.offline(settings));
        try {
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                texts.add("文本" + i);
            }
            List<float[]> vectors = batcher.embedAll(texts);

            for (int i = 0; i < texts.size(); i++) {
                assertEquals((float) i, vectors.get(i)[0],
                        "第 " + i + " 条的向量必须来自第 " + i + " 条文本");
            }
        } finally {
            batcher.shutdown();
        }
    }

    @Test
    @DisplayName("空入参不发请求；返回条数与入参不一致时明确报错（拒绝用错配向量继续）")
    void emptyInputAndMismatchAreExplicit() {
        AiSettings settings = settings(2);
        EmbedBatcher batcher = new EmbedBatcher(new CountingVec(1), ModelRegistry.offline(settings));
        try {
            assertTrue(batcher.embedAll(List.of()).isEmpty());
            assertTrue(batcher.embedAll(null).isEmpty());
        } finally {
            batcher.shutdown();
        }

        VecApi shortVec = new VecApi() {
            @Override
            public List<float[]> embedTexts(List<String> texts, String instruction) {
                return List.of(new float[] {1f});   // 少返回一条
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
                return List.of();
            }

            @Override
            public VecHealth health(double timeoutSeconds) {
                return new VecHealth(true, "fake", List.of());
            }

            @Override
            public double cosine(float[] a, float[] b) {
                return 0;
            }
        };
        EmbedBatcher shortBatcher = new EmbedBatcher(shortVec, ModelRegistry.offline(settings(1)));
        try {
            VecClient.VecException error = org.junit.jupiter.api.Assertions.assertThrows(
                    VecClient.VecException.class, () -> shortBatcher.embedAll(List.of("a", "b")));
            assertTrue(error.getMessage().contains("错配") || error.getMessage().contains("不一致"),
                    error.getMessage());
        } finally {
            shortBatcher.shutdown();
        }
    }
}
