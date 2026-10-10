package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.module.admin.ReindexService;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化前移（P0-3）与重建索引的<b>断点续跑</b>（要求 ⑥）。
 *
 * <p>要钉住的三件事：
 * ① 解析入库阶段就把向量算完并写进后端（local=向量缓存、opensearch=索引）；
 * ② <b>续跑</b>：{@code skipExisting} 时按 {@code chunk_key} 跳过索引里已有的切片——
 *    尤其要保证"已齐的文档<b>一个向量都不重算</b>"（先查索引、再算向量，顺序不能反）；
 * ③ 统计正确：chunks / embedded / reused / written / skipped 与进度、ETA。
 */
class DocIndexerReindexTest {

    @TempDir
    Path tmp;

    /** 可编程的假集群：能预设"已存在哪些切片键"，并记录写入了什么。 */
    static final class StubIndex extends OpenSearchIndex {

        final Set<String> existing = new LinkedHashSet<>();
        final Map<String, String> written = new LinkedHashMap<>();
        final AtomicInteger ensureCalls = new AtomicInteger();
        final AtomicInteger bulkCalls = new AtomicInteger();
        final AtomicInteger searchCalls = new AtomicInteger();

        StubIndex(AiSettings settings) {
            super(settings);
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "stub", List.of());
        }

        @Override
        public void ensureIndexOnce(int dimension) {
            ensureCalls.incrementAndGet();
        }

        @Override
        public Set<String> existingChunkKeys(java.util.Collection<String> chunkKeys) {
            searchCalls.incrementAndGet();
            Set<String> out = new LinkedHashSet<>();
            for (String key : chunkKeys) {
                if (existing.contains(key)) {
                    out.add(key);
                }
            }
            return out;
        }

        @Override
        public void upsertAll(List<ChunkDoc> chunks) {
            for (ChunkDoc chunk : chunks) {
                written.put(chunk.id(), chunk.text());
                existing.add(chunk.id());     // 写进去之后就算"已存在"（模拟幂等）
            }
        }

        @Override
        public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension,
                               Map<String, DocMeta> metaByDocId) {
            bulkCalls.incrementAndGet();
            ensureIndexOnce(dimension);          // 真实实现会先确保索引存在（这里也照做，便于断言）
            List<ChunkDoc> docs = toDocs(chunks, vectors, metaByDocId);
            upsertAll(docs);
            return docs.size();
        }
    }

    /** 记录调用次数的假向量客户端。 */
    static final class CountingVec implements VecApi {

        final AtomicInteger embedCalls = new AtomicInteger();
        final AtomicInteger embeddedTexts = new AtomicInteger();

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            embedCalls.incrementAndGet();
            embeddedTexts.addAndGet(texts.size());
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                out.add(new float[] {text.length(), 1f});
            }
            return out;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            return List.of();
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "stub", List.of());
        }

        @Override
        public double cosine(float[] a, float[] b) {
            return 0;
        }
    }

    private record Fixture(DocStore store, StubIndex index, CountingVec vec, RetrievalService retrieval,
                           DocIndexer indexer) {
    }

    private Fixture fixture(String backend, Path work) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(work);
        settings.getRetrieval().setBackend(backend);
        settings.getOpenSearch().setUrl("http://stub:9200");
        DocStore store = new DocStore(work.resolve("docs"));
        CountingVec vec = new CountingVec();
        StubIndex index = new StubIndex(settings);
        DocMetaStore metaStore = new DocMetaStore(work);
        RetrievalService retrieval = new RetrievalService(settings, vec, store, index,
                ModelRegistry.offline(settings), new SimpleMeterRegistry(), metaStore);
        DocIndexer indexer = new DocIndexer(settings, store, retrieval, index,
                new SimpleMeterRegistry(), metaStore);
        return new Fixture(store, index, vec, retrieval, indexer);
    }

    /** 一份 2 页文档（每页一段 >500 字，于是每页恰好 1 个切片）。 */
    private static String saveTwoChunkDoc(DocStore store, String name) {
        return saveDoc(store, name, "甲".repeat(600), "乙".repeat(600));
    }

    /** 自定义两页文本（用来区分"会失败的那份"与"应当成功的那份"）。 */
    private static String saveDoc(DocStore store, String name, String page1, String page2) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        List<PageText> pages = new ArrayList<>();
        pages.add(new PageText(1, page1));
        pages.add(new PageText(2, page2));
        doc.setPages(pages);
        return store.save(name, doc, 0L).getDocId();
    }

    // ── ① 解析入库阶段就完成向量化与写入 ──────────────────────────

    @Test
    @DisplayName("入库即向量化：local 后端写入向量缓存，统计 chunks/embedded 正确")
    void localBackendEmbedsAtIngestTime() {
        Fixture fixture = fixture("local", tmp.resolve("local"));
        String docId = saveTwoChunkDoc(fixture.store(), "a.pdf");
        StoredDoc doc = fixture.store().get(docId);

        List<String> progress = new ArrayList<>();
        DocIndexer.IndexOutcome outcome = fixture.indexer().indexDocument(doc, false,
                (stage, done, total) -> progress.add(stage + ":" + done + "/" + total));

        assertEquals(2, outcome.chunks());
        assertEquals(2, outcome.embedded(), "冷缓存：两个切片都要算向量");
        assertEquals(0, outcome.written(), "local 后端不写索引（写的是向量缓存）");
        assertEquals(2, fixture.vec().embeddedTexts.get(), "批量向量化一次算完（并发批 32）");
        assertTrue(progress.contains("embed:2/2"), progress.toString());
        assertTrue(java.nio.file.Files.isRegularFile(fixture.retrieval().vectorPath(docId)),
                "向量必须落到 work/vectors/<docId>.json（解析阶段完成，查询侧无需再补算）");
    }

    @Test
    @DisplayName("入库即写索引：opensearch 后端按 chunk_key 写入（幂等 _id）")
    void openSearchBackendWritesAtIngestTime() {
        Fixture fixture = fixture("opensearch", tmp.resolve("os"));
        String docId = saveTwoChunkDoc(fixture.store(), "a.pdf");
        StoredDoc doc = fixture.store().get(docId);
        fixture.index().existing.clear();     // 模拟"索引里还没有这份文档"

        DocIndexer.IndexOutcome outcome = fixture.indexer().indexDocument(doc, false, null);

        assertEquals(2, outcome.chunks());
        assertEquals(2, outcome.embedded());
        assertEquals(2, outcome.written(), "两个切片都要写进索引");
        assertEquals(2, fixture.index().written.size());
        assertEquals(1, fixture.index().ensureCalls.get(), "首次写入要确保索引存在");
        for (String key : fixture.index().written.keySet()) {
            assertTrue(key.startsWith(docId + ":"), "索引 _id = 切片键：" + key);
        }
    }

    // ── ② 断点续跑：跳过已完成，统计正确 ─────────────────────────

    @Test
    @DisplayName("续跑：索引里已齐的文档一个向量都不重算、一条都不重写")
    void resumeSkipsFullyIndexedDocumentWithoutReembedding() {
        Fixture fixture = fixture("opensearch", tmp.resolve("resume-all"));
        String docId = saveTwoChunkDoc(fixture.store(), "a.pdf");
        StoredDoc doc = fixture.store().get(docId);

        // 第一次入库：把两个切片都写进去
        fixture.indexer().indexDocument(doc, false, null);
        assertEquals(2, fixture.index().written.size());
        int embedCallsAfterFirst = fixture.vec().embedCalls.get();

        // 第二次（重建索引的续跑）：应当整份跳过，且**不再向量化**
        DocIndexer.IndexOutcome second = fixture.indexer().indexDocument(doc, true, null);

        assertEquals(2, second.chunks());
        assertEquals(2, second.skipped(), "两个切片都已在索引里 → 全部跳过");
        assertEquals(0, second.embedded(), "已齐的切片不许重算向量（先查索引、再算向量）");
        assertEquals(0, second.written());
        assertEquals(embedCallsAfterFirst, fixture.vec().embedCalls.get(), "续跑不该产生任何向量化调用");
        assertEquals(2, fixture.index().written.size(), "幂等：文档数不翻倍");
    }

    @Test
    @DisplayName("续跑：只补缺失的切片（半份文档也能续上）")
    void resumeWritesOnlyMissingChunks() {
        Fixture fixture = fixture("opensearch", tmp.resolve("resume-partial"));
        String docId = saveTwoChunkDoc(fixture.store(), "a.pdf");
        StoredDoc doc = fixture.store().get(docId);

        List<DocStore.Chunk> chunks = fixture.store().iterChunks(doc);
        String firstKey = RetrievalService.chunkKey(chunks.get(0));
        fixture.index().existing.add(firstKey);       // 模拟"上次写到一半就中断了"

        DocIndexer.IndexOutcome outcome = fixture.indexer().indexDocument(doc, true, null);

        assertEquals(2, outcome.chunks());
        assertEquals(1, outcome.skipped(), "已存在的那个切片要跳过");
        assertEquals(1, outcome.embedded(), "只给它缺的那个算向量");
        assertEquals(1, outcome.written());
        assertFalse(fixture.index().written.containsKey(firstKey),
                "已存在的切片不该被重写（跳过），但也仍在索引里");
        assertEquals(2, fixture.index().existing.size());
    }

    @Test
    @DisplayName("force（skipExisting=false）：忽略已存在，把全部切片重写一遍")
    void forceRewritesEverything() {
        Fixture fixture = fixture("opensearch", tmp.resolve("force"));
        String docId = saveTwoChunkDoc(fixture.store(), "a.pdf");
        StoredDoc doc = fixture.store().get(docId);
        fixture.indexer().indexDocument(doc, false, null);

        DocIndexer.IndexOutcome forced = fixture.indexer().indexDocument(doc, false, null);

        assertEquals(0, forced.skipped());
        assertEquals(2, forced.written(), "force 把全部切片重写（用于换模型/换维度）");
    }

    // ── ③ 重建索引任务：进度 / 跳过 / 统计 / 并发保护 ──────────────

    @Test
    @DisplayName("重建索引任务：进度与统计正确（跳过的文档计入 skipped_docs，ETA 在结束后归零）")
    void reindexJobReportsProgress() throws Exception {
        Fixture fixture = fixture("opensearch", tmp.resolve("job"));
        List<String> docIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            docIds.add(saveTwoChunkDoc(fixture.store(), "doc" + i + ".pdf"));
        }
        // 第 1 份预先"已齐" → 应整份跳过
        StoredDoc first = fixture.store().get(docIds.get(0));
        for (DocStore.Chunk chunk : fixture.store().iterChunks(first)) {
            fixture.index().existing.add(RetrievalService.chunkKey(chunk));
        }

        ReindexService reindex = new ReindexService(
                settingsOf(fixture), fixture.store(), fixture.indexer(), fixture.index());
        ReindexService.Job job = reindex.start(List.of(), false);

        long deadline = System.currentTimeMillis() + 10000;
        Map<String, Object> state = job.toMap();
        while (!"DONE".equals(state.get("status")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            state = job.toMap();
        }

        assertEquals("DONE", state.get("status"), state.toString());
        assertEquals(3, state.get("total_docs"));
        assertEquals(3, state.get("processed_docs"));
        assertEquals(1, state.get("skipped_docs"), "已齐的那份文档整份跳过");
        assertEquals(0, state.get("failed_docs"));
        assertEquals(4, state.get("written_chunks"), "另外两份各 2 个切片");
        assertEquals(100.0, state.get("percent"));
        assertEquals(0.0, state.get("eta_seconds"));
        assertEquals(4, fixture.index().written.size(), "另外两份各 2 个切片被写入（跳过的那份不写）");
    }

    @Test
    @DisplayName("重建索引任务：已有任务在跑时拒绝再启动；单份失败不中断整轮")
    void reindexJobRejectsConcurrentRunAndSurvivesDocFailures() throws Exception {
        Fixture fixture = fixture("opensearch", tmp.resolve("job2"));
        String bad = saveDoc(fixture.store(), "bad.pdf", "坏".repeat(600), "丙".repeat(600));
        String good = saveDoc(fixture.store(), "good.pdf", "好".repeat(600), "丁".repeat(600));

        // 让这份文档在向量化时失败（模拟"某份文档坏了"）；只有"坏"开头的内容会失败。
        // 其余批次睡 200ms：一是让"任务还在跑"这件事有确定的时间窗（用于断言重复启动被拒），
        // 二是顺带证明文档级并发（8 个工作线程）不会互相阻塞。
        VecApi failing = new VecApi() {
            @Override
            public List<float[]> embedTexts(List<String> texts, String instruction) {
                if (texts.stream().anyMatch(text -> text.startsWith("坏"))) {
                    throw new VecClient.VecException("HTTP 500: embedding upstream error");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                List<float[]> out = new ArrayList<>();
                for (String text : texts) {
                    out.add(new float[] {text.length(), 1f});
                }
                return out;
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
                return List.of();
            }

            @Override
            public VecHealth health(double timeoutSeconds) {
                return new VecHealth(true, "stub", List.of());
            }

            @Override
            public double cosine(float[] a, float[] b) {
                return 0;
            }
        };
        AiSettings settings = settingsOf(fixture);
        RetrievalService retrieval = new RetrievalService(settings, failing, fixture.store(), fixture.index(),
                ModelRegistry.offline(settings), new SimpleMeterRegistry(), new DocMetaStore(tmp.resolve("job2")));
        DocIndexer indexer = new DocIndexer(settings, fixture.store(), retrieval, fixture.index(),
                new SimpleMeterRegistry(), new DocMetaStore(tmp.resolve("job2")));
        ReindexService reindex = new ReindexService(settings, fixture.store(), indexer, fixture.index());

        ReindexService.Job job = reindex.start(List.of(bad, good), false);
        assertThrows(IllegalStateException.class, () -> reindex.start(List.of(), false),
                "已有任务在跑时必须拒绝再启动（409 而不是静默并行）");

        long deadline = System.currentTimeMillis() + 10000;
        while (!"DONE_WITH_ERRORS".equals(job.toMap().get("status"))
                && !"DONE".equals(job.toMap().get("status"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Map<String, Object> state = job.toMap();
        assertEquals(2, state.get("total_docs"));
        assertEquals(2, state.get("processed_docs"), "坏文档不能中断整轮");
        assertEquals(1, state.get("failed_docs"));
        assertTrue(String.valueOf(state.get("error")).contains("500"), state.toString());
        assertNotNull(fixture.store().get(good), "坏文档不能影响好文档的入库");
    }

    private static AiSettings settingsOf(Fixture fixture) {
        AiSettings settings = new AiSettings();
        settings.getRetrieval().setBackend("opensearch");
        settings.getOpenSearch().setUrl("http://stub:9200");
        settings.getLimits().setRetryBackoffMs(1);      // 测试不等真实退避（1s → 4s）
        settings.getLimits().setRetryJitterMs(0);
        return settings;
    }
}
