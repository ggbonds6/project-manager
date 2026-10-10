package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片 → 向量 → 落缓存/写索引（P0-3：<b>向量化前移进解析任务</b>）。
 *
 * <h2>为什么必须前移（治"第一次提问超时"）</h2>
 * <p>改造前向量化<b>不在入库路径上</b>：{@code DocStore.save} 只落文本，向量是"第一次检索时"
 * 在<b>问答请求线程里</b>惰性补算的，而且按 32 条<b>串行</b>发（约 18.5 条/s）。
 * 100 份 × 30 切片 = 3,000 切片 → ≈162s，叠加问答本身 ≈10s，<b>必然撞穿前端 180s 超时</b>；
 * 用户看到"问答失败"，而 AI 侧还在继续补算，把对话副本与唯一的重排实例一起拖住。
 * 前移之后：导入时算完（并写进 OpenSearch），问答路径上<b>一次向量化都不用发生</b>。
 *
 * <h2>两条后端的一致性</h2>
 * <ul>
 *   <li>{@code local}：向量落在 {@code work/vectors/<docId>.json}（{@code RetrievalService.embedChunks}
 *       负责缓存读写）；</li>
 *   <li>{@code opensearch}：向量写进 {@code pm-ai-chunks}（{@code _id} = 切片键，幂等）。
 *       索引写入是<b>可续跑</b>的：{@code skipExisting=true} 时按 {@code chunk_key} 跳过已存在的切片
 *       （见 {@link OpenSearchIndex#existingChunkKeys(java.util.Collection)}），这正是"重建索引可中断"的基础。</li>
 * </ul>
 *
 * <p>保留"查询侧补齐"的能力（{@code RetrievalService.embedChunks} 仍会在缺向量时补算），
 * 但那条路径现在会打 WARN 并计 {@code ai.vector.lazy_backfill}——<b>持续 >0 就是缺陷</b>。
 */
@Component
public class DocIndexer {

    private final AiSettings settings;
    private final DocStore docStore;
    private final RetrievalService retrieval;
    private final OpenSearchIndex openSearchIndex;
    private final DocMetaStore docMetaStore;
    private final Counter embedded;
    private final Counter indexed;

    @Autowired
    public DocIndexer(AiSettings settings, DocStore docStore, RetrievalService retrieval,
                      OpenSearchIndex openSearchIndex, MeterRegistry registry, DocMetaStore docMetaStore) {
        this.settings = settings;
        this.docStore = docStore;
        this.retrieval = retrieval;
        this.openSearchIndex = openSearchIndex;
        this.docMetaStore = docMetaStore == null ? new DocMetaStore(settings.getWorkDir()) : docMetaStore;
        MeterRegistry meters = registry == null ? new SimpleMeterRegistry() : registry;
        this.embedded = Counter.builder("ai.vector.embedded")
                .description("入库阶段实际向量化的切片数").register(meters);
        this.indexed = Counter.builder("ai.vector.indexed")
                .description("写入 OpenSearch 的切片数（local 后端恒 0）").register(meters);
    }

    /** 一次入库索引的结果（写进任务详情的 notes，也用于重建索引进度）。 */
    public record IndexOutcome(int chunks, int embedded, int reused, int written, int skipped, double seconds) {

        public String describe() {
            return "切片 " + chunks + "：新算向量 " + embedded + "，复用缓存 " + reused
                    + "，写入索引 " + written + (skipped > 0 ? "，跳过已存在 " + skipped : "")
                    + "，耗时 " + seconds + "s";
        }
    }

    /** 入库索引（解析任务调用）：不做"跳过已存在"——新文档的切片本来就该写进去。 */
    public IndexOutcome indexDocument(StoredDoc doc, ProgressFn progress) {
        return indexDocument(doc, false, progress);
    }

    /**
     * 入库索引。
     *
     * @param skipExisting 只在重建索引（续跑）时为 true：按 chunk_key 跳过索引里已有的切片
     */
    public IndexOutcome indexDocument(StoredDoc doc, boolean skipExisting, ProgressFn progress) {
        long started = System.nanoTime();
        ProgressFn onProgress = progress == null ? ProgressFn.NONE : progress;
        List<DocStore.Chunk> chunks = docStore.iterChunks(doc);
        if (chunks.isEmpty()) {
            return new IndexOutcome(0, 0, 0, 0, 0, seconds(started));
        }

        Set<String> keys = new LinkedHashSet<>();
        for (DocStore.Chunk chunk : chunks) {
            keys.add(RetrievalService.chunkKey(chunk));
        }

        // ── 断点（先查索引再算向量）───────────────────────────────
        // 顺序很重要：**先按 chunk_key 查出已存在的切片、把它们排除掉，再去算向量**。
        // 反过来的话，一份"索引里已经齐了、但本地向量缓存被清过"的文档会被重新向量化一遍
        // （几分钟的无用功），而它本来一个字节都不用写。
        boolean openSearch = usesOpenSearch();
        Set<String> existing = skipExisting && openSearch
                ? openSearchIndex.existingChunkKeys(keys)
                : Set.of();
        List<DocStore.Chunk> targets = new ArrayList<>();
        Set<String> targetKeys = new LinkedHashSet<>();
        for (DocStore.Chunk chunk : chunks) {
            String key = RetrievalService.chunkKey(chunk);
            if (!existing.contains(key)) {
                targets.add(chunk);
                targetKeys.add(key);
            }
        }
        int skipped = chunks.size() - targets.size();
        if (targets.isEmpty()) {
            onProgress.on("embed", chunks.size(), chunks.size());
            return new IndexOutcome(chunks.size(), 0, 0, 0, skipped, seconds(started));
        }

        // 已缓存的向量（按切片键）：用来统计"新算 vs 复用"
        Map<String, float[]> cached = retrieval.loadVectors(doc.getDocId(), targetKeys);
        List<DocStore.Chunk> missing = new ArrayList<>();
        for (DocStore.Chunk chunk : targets) {
            if (!cached.containsKey(RetrievalService.chunkKey(chunk))) {
                missing.add(chunk);
            }
        }
        embedded.increment(missing.size());
        onProgress.on("embed", chunks.size(), chunks.size());

        // 缺向量的切片交给 EmbedBatcher（批 32 / 并发 8 / 共享 embed 信号量 / 幂等重试）。
        // ⚠️ 必须走 retrieval.embedChunks（而不是"只算不落盘"）：它会把向量写进
        // work/vectors/<docId>.json —— 这是"查询侧不再补算"的关键（P0-3）。
        // 两条后端都要落缓存：opensearch 的查询路径同样先读这份缓存，缓存命中的话
        // 一次向量化请求都不会发生。
        Map<String, float[]> vectors = retrieval.embedChunks(targets);

        if (!openSearch) {
            return new IndexOutcome(chunks.size(), missing.size(), cached.size(), 0, skipped, seconds(started));
        }

        // ── OpenSearch：按切片键对齐向量后批量 upsert（幂等）──
        int dimension = 0;
        for (float[] vector : vectors.values()) {
            dimension = vector.length;
            break;
        }
        Map<String, DocMeta> metas = docMetaStore.all();
        List<DocStore.Chunk> toWrite = new ArrayList<>();
        List<float[]> aligned = new ArrayList<>();
        for (DocStore.Chunk chunk : targets) {
            float[] vector = vectors.get(RetrievalService.chunkKey(chunk));
            if (vector == null) {
                continue;
            }
            toWrite.add(chunk);
            aligned.add(vector);
        }
        int written = toWrite.isEmpty() ? 0 : openSearchIndex.indexChunks(toWrite, aligned, dimension, metas);
        indexed.increment(written);
        return new IndexOutcome(chunks.size(), missing.size(), cached.size(), written, skipped, seconds(started));
    }

    /** 是否走 OpenSearch 后端（与 {@code RetrievalService.usesOpenSearch()} 同一口径）。 */
    public boolean usesOpenSearch() {
        String backend = settings.getRetrieval().getBackend();
        return backend != null && "opensearch".equalsIgnoreCase(backend.strip());
    }

    private static double seconds(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000_000.0 * 100.0) / 100.0;
    }

    /** 供自检：索引器当前生效的后端与批量参数。 */
    public Map<String, Object> configSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", settings.getRetrieval().getBackend());
        out.put("embed", EmbedBatcher.describe(settings));
        out.put("open_search", openSearchIndex == null ? null : openSearchIndex.configSummary());
        out.put("doc_meta_entries", docMetaStore.size());
        out.put("embed_concurrency", settings.getLimits().getEmbedConcurrency());
        return out;
    }
}
