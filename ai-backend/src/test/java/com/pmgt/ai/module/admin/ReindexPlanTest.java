package com.pmgt.ai.module.admin;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.chunk.DocumentChunker;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.retrieval.DocMeta;
import com.pmgt.ai.module.retrieval.DocMetaStore;
import com.pmgt.ai.module.retrieval.OpenSearchIndex;
import com.pmgt.ai.module.retrieval.RerankHit;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.retrieval.VecApi;
import com.pmgt.ai.module.retrieval.VecHealth;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import com.pmgt.ai.testutil.TstRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重建侧对 {@code clean_rev/chunk_rev} 的<b>体现</b>（设计 §3.3.3 的 L3）：
 * 版本变化要能从 {@code /admin/reindex} 看出来（任务行带版本快照 + 只读的"重建计划"）。
 *
 * <p>要钉住的是"<b>需重算</b>"这件事可被核对，而不是靠运维猜：
 * 向量缓存的头部与当前口径不一致 ⇒ {@code stale_docs > 0} 且 {@code needs_rebuild=true}。
 */
class ReindexPlanTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetRevision() {
        PipelineRev.reset();
    }

    @Test
    @DisplayName("plan：版本一致时 stale_docs=0；clean_rev +1 后缓存头部过期 ⇒ stale_docs=N、needs_rebuild=true")
    void planReportsStaleDocumentsAfterRevChange() {
        AiSettings settings = settings(0);
        DocStore store = new DocStore(tmp.resolve("docs"));
        String first = save(store, "a.pdf", "甲".repeat(600));
        String second = save(store, "b.pdf", "乙".repeat(600));

        Fixture fixture = fixture(settings, store);
        // 用当前版本把两份文档的向量缓存写出来（走公开入口：与生产同一条路径）
        for (StoredDoc doc : store.allDocs()) {
            fixture.indexer().indexDocument(doc, false, null);
        }

        Map<String, Object> before = fixture.reindex().plan();
        assertEquals(2, before.get("docs"));
        assertEquals(0, before.get("stale_docs"), before.toString());
        assertEquals(2, before.get("fresh_docs"));
        assertEquals(0, before.get("unknown_docs"));
        assertEquals(Boolean.FALSE, before.get("needs_rebuild"));
        assertEquals(0, before.get("clean_rev"));
        assertFalse(String.valueOf(before.get("recommendation")).contains("force=true"),
                "一致时不该劝人重建：" + before.get("recommendation"));

        // 改 clean_rev（生产里是"改配置 + 重启"）⇒ 缓存整体失效，必须重算
        settings.getPipeline().setCleanRev(1);
        PipelineRev.activate(settings);
        fixture.indexer().indexDocument(store.get(first), false, null);   // 重算第一份

        Map<String, Object> after = fixture.reindex().plan();
        assertEquals(1, after.get("clean_rev"));
        assertEquals(1, after.get("stale_docs"), "第二份的缓存头部仍是 clean_rev=0：" + after);
        assertEquals(1, after.get("fresh_docs"));
        assertEquals(Boolean.TRUE, after.get("needs_rebuild"));
        assertTrue(String.valueOf(after.get("recommendation")).contains("/admin/reindex?force=true"));
        assertTrue(String.valueOf(after.get("stale_sample")).contains(second));
        assertEquals(0, after.get("unknown_docs"), "没有向量缓存才算 unknown（这里两份都算过向量）");
    }

    @Test
    @DisplayName("重建任务行带版本快照（pipeline_rev/clean_rev/chunk_rev/开关），便于事后核对")
    void jobCarriesRevisionSnapshot() throws Exception {
        AiSettings settings = settings(3);
        settings.getClean().setEnabled(true);
        DocStore store = new DocStore(tmp.resolve("job-docs"));
        save(store, "a.pdf", "甲".repeat(600));
        Fixture fixture = fixture(settings, store);

        ReindexService.Job job = fixture.reindex().start(List.of(), true);
        Map<String, Object> state = job.toMap();
        assertTrue(String.valueOf(state.get("pipeline_rev")).contains("clean=3"),
                "任务行必须能看出\"这次重建是哪一版规则\"：" + state);
        assertEquals(3, state.get("clean_rev"));
        assertEquals(Boolean.TRUE, state.get("clean_enabled"));
        assertTrue(state.containsKey("chunk_rev"));
        assertEquals(Boolean.TRUE, state.get("chunk_improved"));

        long deadline = System.currentTimeMillis() + 10_000;
        while (!"DONE".equals(job.toMap().get("status")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("DONE", job.toMap().get("status"), job.toMap().toString());
        assertTrue(fixture.reindex().configSummary().containsKey("clean_enabled"));
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    private record Fixture(RetrievalService retrieval, DocIndexer indexer, ReindexService reindex) {
    }

    private static String save(DocStore store, String name, String page) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        doc.setProvider("text-layer");
        List<PageText> pages = new ArrayList<>();
        PageText first = new PageText(1, page);
        first.setSource("text-layer");
        pages.add(first);
        doc.setPages(pages);
        return store.save(name, doc, 0L).getDocId();
    }

    private static AiSettings settings(int cleanRev) {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(cleanRev);
        return settings;
    }

    private Fixture fixture(AiSettings settings, DocStore store) {
        settings.setWorkDir(tmp);
        StubIndex index = new StubIndex(settings);
        DocMetaStore metaStore = new DocMetaStore(tmp);
        RetrievalService retrieval = new RetrievalService(settings, new NoopVec(), store, index,
                TstRegistry.l2(settings), new SimpleMeterRegistry(), metaStore);
        DocIndexer indexer = new DocIndexer(settings, store, retrieval, index,
                new SimpleMeterRegistry(), metaStore);
        ReindexService reindex = new ReindexService(settings, store, indexer, index);
        return new Fixture(retrieval, indexer, reindex);
    }

    /** 离线替身：不发任何请求（本用例只验"计划"与"任务行"，不验真实写入）。 */
    static final class StubIndex extends OpenSearchIndex {
        StubIndex(AiSettings settings) {
            super(settings);
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "stub", List.of());
        }

        @Override
        public void ensureIndexOnce(int dimension) {
        }

        @Override
        public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension,
                               Map<String, DocMeta> metaByDocId) {
            return chunks.size();
        }

        @Override
        public java.util.Set<String> existingChunkKeys(java.util.Collection<String> chunkKeys) {
            return java.util.Set.of();
        }
    }

    static final class NoopVec implements VecApi {
        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
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
}
