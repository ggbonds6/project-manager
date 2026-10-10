package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发批量向量化（P0-3）：把"串行批 32"（今天 ≈18.5 条/s）改成<b>并发 8 的批 32</b>
 * （手册实测 147.9 条/s，约 8×）。
 *
 * <p>为什么单独一个类、而不是在 {@code RetrievalService.embedChunks} 里就地写线程池：
 * ① 解析入库（{@code DocIndexer}）与重建索引都要用它，共用一份并发上限与一份指标；
 * ② {@link ModelRegistry} 的 embed 信号量（默认 8）是<b>全进程唯一</b>的 embed 并发闸门——
 * 解析任务与重建索引<b>加起来</b>也超不过 8，这才叫"共享信号量"（评估 §5.2.2 的
 * {@code q.batch.embed} 语义）；③ 线程池只在构造时建一次（daemon），不随请求创建销毁。
 *
 * <p>保序：第 i 批的结果落在 {@code [i*32, i*32+len)} 位置，与入参一一对应。
 * 向量与文本错配比报错严重得多（会算出一堆"看起来正常但全错"的结果），所以这里按批下标写回，
 * 不依赖任何 Map 迭代顺序。
 */
public class EmbedBatcher {

    /** 单批条数（与 {@link VecClient#EMBED_BATCH} 同一口径：手册实测的最优单请求规模）。 */
    public static final int BATCH = VecClient.EMBED_BATCH;

    private final VecApi vec;
    private final ModelRegistry models;
    private final ExecutorService pool;
    private final int concurrency;
    private final long acquireMs;

    public EmbedBatcher(VecApi vec, ModelRegistry models) {
        this.vec = vec;
        this.models = models;
        this.concurrency = Math.max(1, models.concurrency(ModelRegistry.Kind.EMBED));
        this.acquireMs = Math.max(0, models.batchAcquireMs());
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "pm-ai-embed-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public int concurrency() {
        return concurrency;
    }

    /** 并发批量向量化；返回顺序与入参一致。空入参不发请求。 */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int total = texts.size();
        List<Callable<List<float[]>>> tasks = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        for (int start = 0; start < total; start += BATCH) {
            int from = start;
            int to = Math.min(start + BATCH, total);
            List<String> batch = List.copyOf(texts.subList(from, to));
            ranges.add(new int[] {from, to});
            tasks.add(() -> models.callIdempotent(ModelRegistry.Kind.EMBED, acquireMs,
                    () -> vec.embedTexts(batch, null)));
        }

        List<Future<List<float[]>>> futures = new ArrayList<>(tasks.size());
        for (Callable<List<float[]>> task : tasks) {
            futures.add(pool.submit(task));
        }

        float[][] slots = new float[total][];
        try {
            for (int i = 0; i < futures.size(); i++) {
                List<float[]> vectors = futures.get(i).get();
                int[] range = ranges.get(i);
                for (int offset = 0; offset < range[1] - range[0]; offset++) {
                    float[] vector = offset < vectors.size() ? vectors.get(offset) : null;
                    if (vector != null) {
                        slots[range[0] + offset] = vector;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (Future<?> future : futures) {
                future.cancel(true);
            }
            throw new VecClient.VecException("向量化被中断：" + e.getMessage(), e);
        } catch (ExecutionException e) {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new VecClient.VecException("向量化失败：" + cause, cause);
        }

        List<float[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            if (slots[i] == null) {
                throw new VecClient.VecException(
                        "向量化返回条数与入参不一致（第 " + i + " 条缺向量）：批次大小 " + BATCH
                                + "，入参 " + total + " 条；拒绝用错配的向量继续（错配比报错严重）");
            }
            out.add(slots[i]);
        }
        return out;
    }

    /** 关闭线程池（Spring 容器关闭时调用；daemon 线程兜底，不阻断 JVM 退出）。 */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** 供自检：当前生效的并发与批大小。 */
    public static String describe(AiSettings settings) {
        return "batch=" + BATCH + ", concurrency=" + settings.getLimits().getEmbedConcurrency();
    }
}
