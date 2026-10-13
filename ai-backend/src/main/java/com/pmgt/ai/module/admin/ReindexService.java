package com.pmgt.ai.module.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.chunk.DocumentChunker;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.retrieval.OpenSearchIndex;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全量重建索引（含重新向量化）：<b>可中断、可续跑、带进度与 ETA</b>。
 *
 * <h2>为什么用 HTTP 端点而不是命令行参数</h2>
 * <ol>
 *   <li><b>可观测</b>：重建几万切片要几分钟到几十分钟，必须有"进度/失败数/ETA"可轮询——
 *       命令行参数只能在启动时给，跑起来就只剩日志；</li>
 *   <li><b>可中断、可恢复</b>：端点能带 {@code force} 与 {@code docIds} 参数，能取消、能按剩余
 *       文档续跑；命令行参数要重启进程（而 AI 服务是唯一实例，重启会打断正在跑的解析任务）；</li>
 *   <li><b>与仓库既有形态一致</b>：本服务对外就是 REST（{@code /health}、{@code /documents}、
 *       {@code /upload-tasks}），运维脚本 {@code curl} 就能用，不需要记住新的启动姿势。</li>
 * </ol>
 *
 * <h2>断点续跑怎么算"已经做完了"</h2>
 * <ul>
 *   <li><b>文档级</b>：{@code skipExisting=true}（默认）时，先取该文档全部切片键，
 *       若索引里<b>一个不缺</b>就整份跳过（计入 {@code skipped_docs}）；</li>
 *   <li><b>切片级</b>：剩下的文档按 {@code chunk_key} 跳过索引里<b>已存在</b>的切片，只补缺失的
 *       （{@code _id} 含内容指纹，"存在即正确"）。</li>
 * </ul>
 * 只按 {@code doc_id} 判断"这份文档有没有"是不够的：一份文档的切片是分批写入的，
 * 中途中断会留下"半份文档"，按 doc 粒度会把它当成完成而永久漏掉后半段。
 *
 * <p>并发：向量化走 {@link DocIndexer} → {@code EmbedBatcher}（<b>批 32、并发 8</b>，共享 embed 信号量），
 * 写入按 {@code OPENSEARCH_BULK_SIZE}（默认 500）分批。任务本身<b>串行处理文档</b>（并发在向量化那一段），
 * 这样进度与错误归属清楚，也不会把平台网关一次打满。
 */
@Slf4j
@Service
public class ReindexService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 只读解析向量缓存头部用（与 {@code RetrievalService} 的缓存格式同源）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettings settings;
    private final DocStore docStore;
    private final DocIndexer docIndexer;
    private final OpenSearchIndex openSearchIndex;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pm-ai-reindex");
        thread.setDaemon(true);
        return thread;
    });

    public ReindexService(AiSettings settings, DocStore docStore, DocIndexer docIndexer,
                          OpenSearchIndex openSearchIndex) {
        this.settings = settings;
        this.docStore = docStore;
        this.docIndexer = docIndexer;
        this.openSearchIndex = openSearchIndex;
    }

    /** 一次重建任务的状态（对外的 {@code GET} 形状）。 */
    public static final class Job {
        private final String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final String startedAt = LocalDateTime.now().format(TS);
        private final boolean skipExisting;
        private final List<String> docIds;
        private volatile String status = "RUNNING";
        private volatile int totalDocs;
        private volatile int processed;
        private volatile int failed;
        private volatile int skippedDocs;
        private volatile int chunks;
        private volatile int written;
        private volatile int skippedChunks;
        private volatile String currentDocId = "";
        private volatile String error = "";
        private volatile String finishedAt = "";
        private volatile boolean cancelRequested;
        /** 并发/批大小快照（Job 是静态类，读不到外层 settings，启动时由 start() 写入）。 */
        private volatile int concurrency;
        private volatile int bulkSize;
        /**
         * 启动时的流水线版本快照（设计 §3.3.3 的 L3）。
         *
         * <p>为什么要写进 Job：{@code clean_rev} 变了就必须重算（L1 换键 + L2 缓存失效），
         * 而"这次重建是按哪版规则重建的"只有记在任务行上才能事后核对
         * （否则只看到"重建过"，不知道重建到了哪一版）。
         */
        private volatile String pipelineRev = "";
        private volatile int cleanRev;
        private volatile int chunkRev;
        private volatile boolean cleanEnabled;
        private volatile boolean chunkImproved;

        Job(boolean skipExisting, List<String> docIds) {
            this.skipExisting = skipExisting;
            this.docIds = List.copyOf(docIds);
            this.totalDocs = this.docIds.size();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("job_id", jobId);
            out.put("status", status);
            out.put("started_at", startedAt);
            out.put("finished_at", finishedAt);
            out.put("total_docs", totalDocs);
            // processed_docs = 已处理（含跳过与失败）；skipped_docs / failed_docs 是它的子集
            out.put("processed_docs", processed);
            out.put("failed_docs", failed);
            out.put("skipped_docs", skippedDocs);
            out.put("chunks", chunks);
            out.put("written_chunks", written);
            out.put("skipped_chunks", skippedChunks);
            out.put("current_doc_id", currentDocId);
            out.put("skip_existing", skipExisting);
            out.put("doc_ids", docIds);
            out.put("error", error);
            out.put("percent", totalDocs <= 0 ? 100.0
                    : Math.round(processed * 1000.0 / totalDocs) / 10.0);
            out.put("eta_seconds", etaSeconds());
            out.put("concurrency", concurrency);
            out.put("bulk_size", bulkSize);
            // ── 版本快照（L3）：clean_rev 变化 ⇒ 必须用 force=true 重建，任务行上要能看出来 ──
            out.put("pipeline_rev", pipelineRev);
            out.put("clean_rev", cleanRev);
            out.put("chunk_rev", chunkRev);
            out.put("clean_enabled", cleanEnabled);
            out.put("chunk_improved", chunkImproved);
            return out;
        }

        /** ETA：按"已完成文档的平均耗时 × 剩余文档"估算（样本不足时返回 null，不编一个数）。 */
        private Double etaSeconds() {
            int done = processed;
            if (done <= 0 || done >= totalDocs || finishedAt != null && !finishedAt.isBlank()) {
                return 0.0;
            }
            try {
                LocalDateTime start = LocalDateTime.parse(startedAt, TS);
                double elapsed = java.time.Duration.between(start, LocalDateTime.now()).toMillis() / 1000.0;
                return Math.round(elapsed / done * (totalDocs - done) * 10.0) / 10.0;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 启动一次重建；已有任务在跑时抛 {@code IllegalStateException}（由 controller 映射成 409）。 */
    public Job start(List<String> docIds, boolean force) {
        for (Job job : jobs.values()) {
            if ("RUNNING".equals(job.status)) {
                throw new IllegalStateException("已有重建任务在跑（job_id=" + job.jobId
                        + "，进度 " + job.toMap().get("percent") + "%），请等它结束或先取消");
            }
        }
        boolean skipExisting = !force;
        List<String> targets = new ArrayList<>();
        if (docIds == null || docIds.isEmpty()) {
            for (StoredDoc doc : docStore.allDocs()) {
                targets.add(doc.getDocId());
            }
            targets.sort(Comparator.naturalOrder());
        } else {
            targets.addAll(new LinkedHashSet<>(docIds));
        }

        // ⚠️ 先把"索引不可达"这类硬错误暴露在**启动时**（而不是跑了一半才发现）：
        // 不可达就明确报错，绝不静默改用 local 重建。
        if (docIndexer.usesOpenSearch()) {
            openSearchIndex.health(settings.getOpenSearch().getTimeoutSeconds());
        }

        Job job = new Job(skipExisting, targets);
        job.concurrency = settings.getLimits().getEmbedConcurrency();
        job.bulkSize = settings.getOpenSearch().getBulkSize();
        // 版本快照（L3）：重建是按哪一版规则跑的，必须能事后核对
        job.pipelineRev = PipelineRev.pipelineRev();
        job.cleanRev = PipelineRev.current().cleanRev();
        job.chunkRev = PipelineRev.current().chunkRev();
        job.cleanEnabled = settings.getClean().isEnabled();
        job.chunkImproved = settings.getChunk().isImproved();
        jobs.put(job.jobId, job);
        runner.submit(() -> run(job));
        log.info("重建索引任务 {} 启动：{} 份文档（skip_existing={}, backend={}, force={}, pipeline_rev={}）",
                job.jobId, targets.size(), skipExisting, settings.getRetrieval().getBackend(), force,
                job.pipelineRev);
        return job;
    }

    /**
     * <b>重建计划（只读，L3 的"体现"）</b>：按向量缓存的版本头判断哪些文档需要重算。
     *
     * <p>为什么要它：{@code clean_rev}/{@code chunk_rev} 变化后，切片键（L1）与向量缓存（L2）
     * 都会失效——但"要不要现在跑一次重建"不能靠猜。这里给出<b>可核对的数字</b>：
     * <ul>
     *   <li>{@code stale_docs}：向量缓存头部的 {@code clean_rev/chunk_rev} 与当前口径不一致（⇒ 会重算）；</li>
     *   <li>{@code fresh_docs}：一致（⇒ 缓存仍然有效）；</li>
     *   <li>{@code unknown_docs}：没有向量缓存（从未向量化，或缓存被删）。</li>
     * </ul>
     * 它是<b>只读</b>：不发请求、不写文件、不改索引。{@code local} 后端上"缓存一致"就等价于
     * "不用重算"；{@code opensearch} 后端还要看索引里的切片键（那需要连集群，本端点刻意不连，
     * 所以返回里明写 {@code index_checked=false}）。
     */
    public Map<String, Object> plan() {
        int stale = 0;
        int fresh = 0;
        int unknown = 0;
        List<String> staleSample = new ArrayList<>();
        Path vectors = settings.getWorkDir().resolve("vectors");
        for (StoredDoc doc : docStore.allDocs()) {
            Path file = vectors.resolve(doc.getDocId() + ".json");
            if (!Files.isRegularFile(file)) {
                unknown++;
                continue;
            }
            Integer cacheClean = null;
            Integer cacheChunk = null;
            try {
                com.fasterxml.jackson.databind.JsonNode payload = MAPPER.readTree(Files.readString(file));
                if (payload.has("clean_rev")) {
                    cacheClean = payload.path("clean_rev").asInt(-1);
                }
                if (payload.has("chunk_rev")) {
                    cacheChunk = payload.path("chunk_rev").asInt(-1);
                }
            } catch (Exception e) {
                unknown++;
                continue;
            }
            boolean match = cacheClean != null && cacheChunk != null
                    && cacheClean == PipelineRev.current().cleanRev()
                    && cacheChunk == PipelineRev.current().chunkRev();
            if (match) {
                fresh++;
            } else {
                stale++;
                if (staleSample.size() < 20) {
                    staleSample.add(doc.getDocId());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline_rev", PipelineRev.pipelineRev());
        out.put("clean_rev", PipelineRev.current().cleanRev());
        out.put("chunk_rev", PipelineRev.current().chunkRev());
        out.put("clean_enabled", settings.getClean().isEnabled());
        out.put("chunk_improved", settings.getChunk().isImproved());
        out.put("chunk_key_format", PipelineRev.format());
        out.put("docs", stale + fresh + unknown);
        out.put("stale_docs", stale);
        out.put("fresh_docs", fresh);
        out.put("unknown_docs", unknown);
        out.put("stale_sample", staleSample);
        out.put("index_checked", false);
        out.put("needs_rebuild", stale > 0);
        out.put("recommendation", stale > 0
                ? "clean_rev/chunk_rev 与向量缓存头部不一致：跑 POST /admin/reindex?force=true 重算"
                        + "（force 才会忽略'已存在'；L1 的切片键已换，旧索引条目不会被误当命中）"
                : "向量缓存与当前版本一致；local 后端无需重建。opensearch 后端请另按索引里的 "
                        + "chunk_key 核对（本端点不连集群，故 index_checked=false）");
        return out;
    }

    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    /** 最近的任务（倒序），供运维一眼看历史。 */
    public List<Map<String, Object>> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing((Job job) -> job.startedAt).reversed())
                .map(Job::toMap)
                .toList();
    }

    /** 请求取消（协作式：在文档边界生效；已写入的切片不回滚——它们是正确的，续跑会跳过）。 */
    public boolean cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !"RUNNING".equals(job.status)) {
            return false;
        }
        job.cancelRequested = true;
        return true;
    }

    // ── 执行体 ─────────────────────────────────────────────────────

    /**
     * 执行重建：<b>文档级并发 = embed 并发上限</b>（默认 8）。
     *
     * <p>为什么不让文档串行：向量化的并发粒度是"每 32 条一批"，而典型切片数 < 32 的文档只有
     * <b>一个批次</b>——串行处理文档时，并发 8 的收益完全用不上（实测口径：每批 ≈1.7s，
     * 100 份小文档就是 170s）。改成 8 个文档并行之后，embed 信号量的 8 个 permit 才真正被填满，
     * 与"批 32 + 并发 8 = 147.9 条/s"的手册结论对齐。
     *
     * <p>取消是协作式的：每个工作线程<b>在开始时</b>检查一次取消位，已在处理中的文档会跑完
     * （已写入的切片是正确的、幂等的，下次续跑会跳过它们）。
     */
    private void run(Job job) {
        long startedNanos = System.nanoTime();
        int workers = Math.max(1, Math.min(settings.getLimits().getEmbedConcurrency(), job.docIds.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "pm-ai-reindex-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(job.docIds.size());
            for (String docId : job.docIds) {
                futures.add(pool.submit(() -> processDoc(job, docId)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // processDoc 内部已兜底；真漏出来了也不能让整轮停下
                    log.warn("重建索引：工作线程异常退出：{}", e.toString());
                }
            }
            if (job.cancelRequested) {
                job.status = "CANCELLED";
            } else {
                job.status = job.failed > 0 ? "DONE_WITH_ERRORS" : "DONE";
            }
        } catch (RuntimeException e) {
            job.status = "FAILED";
            job.error = simplify("", e);
            log.warn("重建索引任务 {} 失败：{}", job.jobId, job.error);
        } finally {
            pool.shutdown();
            job.finishedAt = LocalDateTime.now().format(TS);
            job.currentDocId = "";
            log.info("重建索引任务 {} 结束：status={} processed={} skipped={} failed={} chunks={} written={} 耗时 {}s",
                    job.jobId, job.status, job.processed, job.skippedDocs, job.failed, job.chunks, job.written,
                    Math.round((System.nanoTime() - startedNanos) / 1_000_000_000.0));
        }
    }

    /** 处理一份文档（工作线程）。单份失败只记账，不中断整轮。 */
    private void processDoc(Job job, String docId) {
        if (job.cancelRequested) {
            return;
        }
        job.currentDocId = docId;
        StoredDoc doc = docStore.get(docId);
        if (doc == null) {
            synchronized (job) {
                job.skippedDocs++;
                job.processed++;
            }
            return;
        }
        try {
            if (job.skipExisting && fullyIndexed(doc)) {
                synchronized (job) {
                    job.skippedDocs++;
                    job.processed++;
                }
                return;
            }
            DocIndexer.IndexOutcome outcome = docIndexer.indexDocument(doc, job.skipExisting, null);
            synchronized (job) {
                job.chunks += outcome.chunks();
                job.written += outcome.written();
                job.skippedChunks += outcome.skipped();
                job.processed++;
            }
        } catch (RuntimeException e) {
            synchronized (job) {
                job.failed++;
                job.processed++;
                if (job.error.isBlank()) {
                    job.error = simplify(docId, e);
                }
            }
            log.warn("重建索引：文档 {} 失败（继续处理其余文档）：{}", docId, e.getMessage());
        }
    }

    /**
     * 文档级断点：该文档的全部切片键是否<b>一个不缺</b>地都在索引里。
     *
     * <p>⚠️ 切片键必须与入库/检索<b>同一口径</b>算（{@code DocumentChunker.chunksOf}）：
     * 用回退口径算键会让"索引里其实已经齐了"的文档被当成没齐，从而白跑一遍向量化。
     *
     * <p>非 OpenSearch 后端（local）没有"索引"可查，恒返回 false（每次都重算缓存——
     * 缓存命中时不发请求，代价可忽略）。
     */
    private boolean fullyIndexed(StoredDoc doc) {
        if (!docIndexer.usesOpenSearch()) {
            return false;
        }
        List<DocStore.Chunk> chunks = DocumentChunker.chunksOf(doc, settings);
        if (chunks.isEmpty()) {
            return true;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (DocStore.Chunk chunk : chunks) {
            keys.add(RetrievalService.chunkKey(chunk));
        }
        return openSearchIndex.existingChunkKeys(keys).size() >= keys.size();
    }

    private static String simplify(String docId, RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return (docId.isBlank() ? "" : "doc " + docId + ": ") + message;
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** 供自检：重建能力是否可用（与后端无关，local 也能重建缓存）。 */
    public Map<String, Object> configSummary() {
        AtomicInteger running = new AtomicInteger();
        jobs.values().forEach(job -> {
            if ("RUNNING".equals(job.status)) {
                running.incrementAndGet();
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", settings.getRetrieval().getBackend());
        out.put("running_jobs", running.get());
        out.put("jobs", jobs.size());
        out.put("skip_existing_default", true);
        out.put("pipeline_rev", PipelineRev.pipelineRev());
        out.put("clean_enabled", settings.getClean().isEnabled());
        out.put("clean_rev", PipelineRev.current().cleanRev());
        out.put("chunk_rev", PipelineRev.current().chunkRev());
        return out;
    }
}
