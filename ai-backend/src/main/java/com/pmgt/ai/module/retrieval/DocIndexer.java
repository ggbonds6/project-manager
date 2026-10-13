package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.module.chunk.ChunkOptions;
import com.pmgt.ai.module.chunk.DocumentChunker;
import com.pmgt.ai.module.clean.CleanResult;
import com.pmgt.ai.module.pipeline.ArtifactType;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.pipeline.RunContext;
import com.pmgt.ai.module.pipeline.StageKey;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片 → 向量 → 落缓存/写索引（P0-3：<b>向量化前移进解析任务</b>），并<b>按阶段边界上报</b>（设计 §3.2/§4）。
 *
 * <h2>为什么必须前移（治"第一次提问超时"）</h2>
 * <p>改造前向量化<b>不在入库路径上</b>：{@code DocStore.save} 只落文本，向量是"第一次检索时"
 * 在<b>问答请求线程里</b>惰性补算的，而且按 32 条<b>串行</b>发（约 18.5 条/s）。
 * 100 份 × 30 切片 = 3,000 切片 → ≈162s，叠加问答本身 ≈10s，<b>必然撞穿前端 180s 超时</b>；
 * 用户看到"问答失败"，而 AI 侧还在继续补算，把对话副本与唯一的重排实例一起拖住。
 * 前移之后：导入时算完（并写进 OpenSearch），问答路径上<b>一次向量化都不用发生</b>。
 *
 * <h2>两个入口（阶段边界必须与设计 §3.2 的五阶段一一对应）</h2>
 * <ol>
 *   <li>{@link #prepare(StoredDoc, RunContext)} = <b>CHUNK 阶段</b>：切分本身（纯本地）。
 *       拆出来是为了让"切分"成为一个<b>可上报、可计时</b>的独立阶段，
 *       而不是藏在 {@code indexDocument} 里（那样 CHUNK 阶段行只能靠估算，
 *       与设计"阶段耗时之和 / 任务耗时 ≥ 0.9"的验收口径对不上）；</li>
 *   <li>{@link #indexPrepared} = <b>EMBED + INDEX 阶段</b>：算向量（L2 embed 信号量）、
 *       写缓存/写索引（含 {@code PARTIAL} 语义与两类产物）。</li>
 * </ol>
 * 老签名（{@code indexDocument}）保留并转发到 {@code prepare + indexPrepared}，
 * <b>行为与接入前逐字一致</b>（只是多了一次切分结果的传递，没有多做任何事）。
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
@Slf4j
@Component
public class DocIndexer {

    /** {@code chunk_manifest} 摘要里逐片样本的条数上限（全文在 {@code work/chunks/}）。 */
    static final int MANIFEST_SAMPLES = 20;

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

    /** 一次入库索引的结果（写进任务详情的 notes，也用于重建索引进度与阶段上报的计数）。 */
    public record IndexOutcome(int chunks, int embedded, int reused, int written, int skipped, double seconds,
                               int batches, int failed) {

        public String describe() {
            return "切片 " + chunks + "：新算向量 " + embedded + "，复用缓存 " + reused
                    + "，写入索引 " + written + (skipped > 0 ? "，跳过已存在 " + skipped : "")
                    + (failed > 0 ? "，失败 " + failed : "")
                    + "，耗时 " + seconds + "s";
        }
    }

    /** CHUNK 阶段的输出（切分结果 + 本地耗时）：交给 {@link #indexPrepared} 继续走 EMBED/INDEX。 */
    public record Prepared(List<DocStore.Chunk> chunks, long elapsedMs) {
    }

    // ══════════════════════════════════════════════════════════════
    // CHUNK 阶段
    // ══════════════════════════════════════════════════════════════

    /**
     * 切分（CHUNK 阶段）：纯本地、确定性；上报 {@code RUNNING → DONE(或 PARTIAL)}，
     * 并写一份 {@code chunk_manifest} 摘要产物（Q5 的数据源，DB 只存摘要、全文留 {@code work/}）。
     *
     * @param cleaned 已算好的清洗结果（解析链路会传；{@code null} ⇒ 这里按当前配置自己清洗一遍，
     *                保证"查询侧与入库侧同一口径"，见 {@code DocumentChunker.chunksOf}）
     */
    public Prepared prepare(StoredDoc doc, CleanResult cleaned, RunContext run) {
        long started = System.nanoTime();
        ChunkOptions options = ChunkOptions.of(settings);
        if (run != null) {
            run.stageStarted(StageKey.CHUNK, "doc=" + doc.getDocId() + " pages=" + doc.getPageCount()
                    + " pipeline_rev=" + PipelineRev.pipelineRev());
        }
        List<DocStore.Chunk> chunks = cleaned == null
                ? DocumentChunker.chunksOf(doc, settings)
                : DocumentChunker.chunk(cleaned, doc, options);
        long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        if (run != null) {
            run.stageDone(StageKey.CHUNK, chunks.size(), 0, chunkCounters(doc, chunks, options),
                    outputDigestOf(chunks));
            run.artifact(ArtifactType.CHUNK_MANIFEST, chunkManifestJson(doc, chunks, options),
                    chunkManifestPath(doc), fullManifestJson(doc, chunks, options).length(),
                    PipelineRev.sha1Hex(outputDigestOf(chunks)),
                    chunks.size(), "切片清单（摘要进 DB；全文在 " + chunkManifestPath(doc) + "）");
        }
        writeChunkManifest(doc, chunks, options);
        return new Prepared(chunks, elapsed);
    }

    /** 老签名（不传清洗结果）：行为与"按当前配置清洗 + 切分"一致。 */
    public Prepared prepare(StoredDoc doc, RunContext run) {
        return prepare(doc, null, run);
    }

    private Map<String, Object> chunkCounters(StoredDoc doc, List<DocStore.Chunk> chunks, ChunkOptions options) {
        int max = 0;
        long total = 0;
        int tables = 0;
        int tableRows = 0;
        int maxTable = 0;
        int overlapped = 0;
        for (DocStore.Chunk chunk : chunks) {
            int len = chunk.charLen();
            max = Math.max(max, len);
            total += len;
            if (chunk.isTable()) {
                tables++;
                tableRows += chunk.tableRows();
                maxTable = Math.max(maxTable, len);
            }
            if (chunk.orderInPage() > 0) {
                overlapped++;
            }
        }
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("pages", doc.getPageCount());
        counters.put("chunk_count", chunks.size());
        counters.put("avg_char_len", chunks.isEmpty() ? 0 : Math.round((double) total / chunks.size()));
        counters.put("max_char_len", max);
        counters.put("max_char_len_limit", options.maxCharLen());
        counters.put("over_limit_chunks", chunks.stream().filter(c -> c.charLen() > options.maxCharLen()).count());
        counters.put("chunk_chars", options.chunkChars());
        counters.put("chunk_improved", options.improved());
        counters.put("structure_aware", options.structureAware());
        counters.put("tables_whole", options.tablesWhole());
        counters.put("overlap_ratio", options.overlapRatio());
        counters.put("overlap_chars", options.overlapChars());
        counters.put("overlapped_chunks", overlapped);
        counters.put("table_chunks", tables);
        counters.put("table_rows", tableRows);
        counters.put("max_table_chars", maxTable);
        counters.put("parent_count", DocumentChunker.parentCount(chunks));
        counters.put("parent_summary_chars", settings.getChunk().getParentSummaryChars());
        counters.put("clean_rev", PipelineRev.current().cleanRev());
        counters.put("chunk_rev", PipelineRev.current().chunkRev());
        counters.put("chunk_key_format", PipelineRev.format());
        return counters;
    }

    private String outputDigestOf(List<DocStore.Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(20, chunks.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(RetrievalService.chunkKey(chunks.get(i)));
        }
        return "chunks=" + chunks.size() + "; keys=" + sb;
    }

    /** {@code chunk_manifest} 摘要（≤4000 字符，超长截断——DB 只存摘要，全文由 object_key 指路）。 */
    private String chunkManifestJson(StoredDoc doc, List<DocStore.Chunk> chunks, ChunkOptions options) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("doc_id", doc.getDocId());
        manifest.put("filename", doc.getFilename());
        manifest.put("chunk_count", chunks.size());
        manifest.put("chunk_chars", options.chunkChars());
        manifest.put("max_char_len", maxCharLen(chunks));
        manifest.put("max_char_len_limit", options.maxCharLen());
        manifest.put("overlap_ratio", options.overlapRatio());
        manifest.put("chunk_improved", options.improved());
        manifest.put("table_chunks", chunks.stream().filter(DocStore.Chunk::isTable).count());
        manifest.put("parent_count", DocumentChunker.parentCount(chunks));
        manifest.put("sample_limit", MANIFEST_SAMPLES);
        manifest.put("pipeline_rev", PipelineRev.pipelineRev());
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < Math.min(MANIFEST_SAMPLES, chunks.size()); i++) {
            DocStore.Chunk chunk = chunks.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_key", RetrievalService.chunkKey(chunk));
            item.put("page_no", chunk.pageNo());
            item.put("order_in_page", chunk.orderInPage());
            item.put("char_len", chunk.charLen());
            item.put("is_table", chunk.isTable());
            if (chunk.isTable()) {
                item.put("table_rows", chunk.tableRows());
            }
            item.put("parent_key", chunk.parentKey());
            if (chunk.sectionPath() != null && !chunk.sectionPath().isBlank()) {
                item.put("section_path", chunk.sectionPath());
            }
            samples.add(item);
        }
        manifest.put("samples", samples);
        return clipJson(manifest, 4000, doc, chunks.size());
    }

    /** 切片清单全文（落 {@code work/chunks/<docId>.json}，可重建物、不进主系统）。 */
    private String fullManifestJson(StoredDoc doc, List<DocStore.Chunk> chunks, ChunkOptions options) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("doc_id", doc.getDocId());
        manifest.put("filename", doc.getFilename());
        manifest.put("chunk_count", chunks.size());
        manifest.put("chunk_chars", options.chunkChars());
        manifest.put("max_char_len", options.maxCharLen());
        manifest.put("overlap_ratio", options.overlapRatio());
        manifest.put("structure_aware", options.structureAware());
        manifest.put("tables_whole", options.tablesWhole());
        manifest.put("parent_summary_chars", settings.getChunk().getParentSummaryChars());
        manifest.put("pipeline_rev", PipelineRev.pipelineRev());
        manifest.put("clean_rev", PipelineRev.current().cleanRev());
        manifest.put("chunk_rev", PipelineRev.current().chunkRev());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocStore.Chunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_key", RetrievalService.chunkKey(chunk));
            item.put("page_no", chunk.pageNo());
            item.put("order_in_page", chunk.orderInPage());
            item.put("char_len", chunk.charLen());
            item.put("is_table", chunk.isTable());
            item.put("table_rows", chunk.tableRows());
            item.put("parent_key", chunk.parentKey());
            item.put("section_path", chunk.sectionPath());
            item.put("source", chunk.source());
            item.put("text", chunk.text());
            items.add(item);
        }
        manifest.put("chunks", items);
        // 父块索引：small-to-big 的接缝（父键 → 子键列表 + 摘要）；升级章节树时这里换成章节父块
        manifest.put("parents", DocumentChunker.parentsOf(chunks, settings.getChunk().getParentSummaryChars()));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(manifest);
        } catch (Exception e) {
            return "{\"doc_id\":\"" + doc.getDocId() + "\",\"chunk_count\":" + chunks.size() + "}";
        }
    }

    /** 切片清单全文的路径（作为 {@code chunk_manifest} 产物的 {@code object_key}）。 */
    private String chunkManifestPath(StoredDoc doc) {
        return settings.getWorkDir().resolve("chunks").resolve(doc.getDocId() + ".json").toString();
    }

    /** 落盘切片清单全文（失败只 WARN：它是可重建物，不该影响解析结果）。 */
    private void writeChunkManifest(StoredDoc doc, List<DocStore.Chunk> chunks, ChunkOptions options) {
        java.nio.file.Path path = java.nio.file.Path.of(chunkManifestPath(doc));
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, fullManifestJson(doc, chunks, options),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("切片清单全文落盘失败（不影响解析）：{}：{}", path, e.getMessage());
        }
    }

    private static int maxCharLen(List<DocStore.Chunk> chunks) {
        int max = 0;
        for (DocStore.Chunk chunk : chunks) {
            max = Math.max(max, chunk.charLen());
        }
        return max;
    }

    private static String clipJson(Map<String, Object> payload, int limit, StoredDoc doc, int chunkCount) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            return json.length() <= limit ? json : json.substring(0, limit);
        } catch (Exception e) {
            return "{\"doc_id\":\"" + doc.getDocId() + "\",\"chunk_count\":" + chunkCount + "}";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // EMBED + INDEX 阶段
    // ══════════════════════════════════════════════════════════════

    /** 入库索引（老签名，行为不变）：不做"跳过已存在"——新文档的切片本来就该写进去。 */
    public IndexOutcome indexDocument(StoredDoc doc, ProgressFn progress) {
        return indexDocument(doc, false, progress);
    }

    /**
     * 入库索引（老签名，行为不变）。
     *
     * @param skipExisting 只在重建索引（续跑）时为 true：按 chunk_key 跳过索引里已有的切片
     */
    public IndexOutcome indexDocument(StoredDoc doc, boolean skipExisting, ProgressFn progress) {
        return indexPrepared(doc, prepare(doc, null), skipExisting, progress, null);
    }

    /**
     * 已知切分结果的入库索引（解析链路走这条：CHUNK 阶段已经上过报，这里只负责 EMBED 与 INDEX）。
     *
     * <p>阶段边界与契约一一对应：
     * <ul>
     *   <li>{@code EMBED}：{@code RUNNING → DONE/PARTIAL}，计数 {@code embedded/reused/failed/batches}，
     *       产物 {@code embed_batch}；</li>
     *   <li>{@code INDEX}：{@code RUNNING → DONE/PARTIAL}，计数 {@code written/skipped/failed}，
     *       产物 {@code index_result}；<b>{@code PARTIAL}</b> 是设计新增的状态（部分切片写失败）。</li>
     * </ul>
     */
    public IndexOutcome indexPrepared(StoredDoc doc, Prepared prepared, boolean skipExisting,
                                      ProgressFn progress, RunContext run) {
        long started = System.nanoTime();
        ProgressFn onProgress = progress == null ? ProgressFn.NONE : progress;
        // 没有预切分结果时按"清洗 + 切分"重算一遍：必须与 prepare 完全同口径（同一个入口）
        StoredDoc source = docStore == null ? doc
                : java.util.Objects.requireNonNullElse(docStore.get(doc.getDocId()), doc);
        List<DocStore.Chunk> chunks = prepared == null || prepared.chunks() == null
                ? DocumentChunker.chunksOf(source, settings) : prepared.chunks();
        if (chunks.isEmpty()) {
            if (run != null) {
                run.stageDone(StageKey.EMBED, 0, 0, Map.of("reason", "无切片（文档没有可切分的文本）"), "chunks=0");
                run.stageDone(StageKey.INDEX, 0, 0, Map.of("written", 0), "written=0");
            }
            return new IndexOutcome(0, 0, 0, 0, 0, seconds(started), 0, 0);
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
        if (run != null) {
            run.stageStarted(StageKey.EMBED, "chunks=" + targets.size() + " skip_existing=" + skipExisting);
        }
        if (targets.isEmpty()) {
            onProgress.on("embed", chunks.size(), chunks.size());
            if (run != null) {
                run.stageDone(StageKey.EMBED, 0, 0,
                        Map.of("cached", chunks.size(), "skipped_existing", skipped),
                        "embedded=0; reused=" + chunks.size());
                run.stageDone(StageKey.INDEX, 0, 0, Map.of("skipped_existing", skipped), "written=0");
            }
            return new IndexOutcome(chunks.size(), 0, 0, 0, skipped, seconds(started), 0, 0);
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

        int batchSize = Math.max(1, settings.getLimits().getEmbedConcurrency() * 32);
        int batches = (int) Math.ceil((double) missing.size() / 32);
        if (run != null) {
            run.stageDone(StageKey.EMBED, targets.size(), 0,
                    embedCounters(doc, chunks.size(), missing.size(), cached.size(), skipped, batches),
                    "embedded=" + missing.size() + "; reused=" + cached.size() + "; keys=" + targetKeys.size());
            run.artifact(ArtifactType.EMBED_BATCH,
                    "{\"doc_id\":\"" + doc.getDocId() + "\",\"embedded\":" + missing.size()
                            + ",\"reused\":" + cached.size() + ",\"batches\":" + batches
                            + ",\"model\":\"" + settings.getVec().getEmbedModel() + "\""
                            + ",\"pipeline_rev\":\"" + PipelineRev.pipelineRev() + "\"}",
                    null, null, null, missing.size(),
                    "向量化批次统计（batch_size=32，并发=" + settings.getLimits().getEmbedConcurrency()
                            + "；batch_size 上限估值 " + batchSize + "）");
        }

        if (!openSearch) {
            if (run != null) {
                run.stageDone(StageKey.INDEX, 0, 0,
                        Map.of("backend", "local", "written", 0, "cache_entries", vectors.size()),
                        "local 后端：向量落 work/vectors，无索引写入");
            }
            return new IndexOutcome(chunks.size(), missing.size(), cached.size(), 0, skipped, seconds(started),
                    batches, 0);
        }

        // ── OpenSearch：按切片键对齐向量后批量 upsert（幂等）──
        if (run != null) {
            run.stageStarted(StageKey.INDEX, "targets=" + targets.size());
        }
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
        int written = 0;
        int failed = 0;
        try {
            written = toWrite.isEmpty() ? 0 : openSearchIndex.indexChunks(toWrite, aligned, dimension, metas);
            indexed.increment(written);
        } catch (RuntimeException e) {
            // 写索引整体失败：**不静默降级**（local 与 opensearch 的语义差异是刻意的，见类注释），
            // 但阶段要如实上报 FAILED，让"卡在哪一步/为什么失败"在任务流里看得见。
            failed = toWrite.size();
            if (run != null) {
                run.stageFailed(StageKey.INDEX, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            throw e;
        }
        failed += Math.max(0, toWrite.size() - written);
        if (run != null) {
            run.stageDone(StageKey.INDEX, toWrite.size(), failed,
                    Map.of("backend", "opensearch", "written", written, "failed", failed,
                            "skipped_existing", skipped, "dimensions", dimension,
                            "index", settings.getOpenSearch().getIndex()),
                    "written=" + written + "; failed=" + failed);
            run.artifact(ArtifactType.INDEX_RESULT,
                    "{\"doc_id\":\"" + doc.getDocId() + "\",\"index\":\""
                            + settings.getOpenSearch().getIndex() + "\",\"written\":" + written
                            + ",\"failed\":" + failed + ",\"skipped\":" + skipped
                            + ",\"dimensions\":" + dimension + "}",
                    null, null, null, written, "写索引结果（_id = chunk_key，幂等 upsert）");
        }
        return new IndexOutcome(chunks.size(), missing.size(), cached.size(), written, skipped, seconds(started),
                batches, failed);
    }

    private Map<String, Object> embedCounters(StoredDoc doc, int chunks, int embeddedCount, int reused, int skipped,
                                              int batches) {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("chunks", chunks);
        counters.put("embedded", embeddedCount);
        counters.put("reused", reused);
        counters.put("skipped_existing", skipped);
        counters.put("batches", batches);
        counters.put("batch_size", 32);
        counters.put("concurrency", settings.getLimits().getEmbedConcurrency());
        counters.put("model", settings.getVec().getEmbedModel());
        counters.put("pipeline_rev", PipelineRev.pipelineRev());
        return counters;
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
        out.put("chunk_key_format", PipelineRev.format());
        out.put("pipeline_rev", PipelineRev.pipelineRev());
        return out;
    }
}
