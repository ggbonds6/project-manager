package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.testutil.TstRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量缓存的<b>流水线版本校验</b>（设计 §3.3.3 的 L2 防护）。
 *
 * <p>放在本包（而不是 {@code module.pipeline}）是因为 {@code RetrievalService.embedChunks} 是包级可见的
 * ——这与"向量缓存是检索层的内部实现"的边界一致：外部只该通过 {@code DocIndexer} 用它。
 *
 * <p>要钉住的是：<b>clean_rev/chunk_rev 变了，旧向量一个都不能复用</b>
 * （否则"改了清洗规则却拿旧向量配新文本"，检索会悄悄给出错位的命中）。
 */
class VectorCacheRevisionTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("版本没变 ⇒ 第二次命中缓存（一次 embed 请求都不发），缓存头部写着当前版本")
    void sameRevisionReusesCache() {
        Fixture fixture = fixture(0);
        String docId = saveDoc(fixture.store);
        DocStore.Chunk chunk = fixture.chunks(docId);

        Map<String, float[]> first = fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(1, first.size());
        assertEquals(1, fixture.vec.embedCalls.get(), "第一次必须真算向量");
        String cache = read(fixture, docId);
        assertTrue(cache.contains("\"clean_rev\":0"), cache);
        assertTrue(cache.contains("\"chunk_rev\":0"), cache);

        Map<String, float[]> second = fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(1, second.size());
        assertEquals(1, fixture.vec.embedCalls.get(), "版本没变 ⇒ 第二次必须命中缓存");
    }

    @Test
    @DisplayName("clean_rev 变化 ⇒ 缓存整体失效并重算（即使切片文本一模一样）")
    void cleanRevChangeInvalidatesCache() {
        Fixture fixture = fixture(0);
        String docId = saveDoc(fixture.store);
        DocStore.Chunk chunk = fixture.chunks(docId);
        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(1, fixture.vec.embedCalls.get());

        fixture.settings.getPipeline().setCleanRev(1);
        PipelineRev.activate(fixture.settings);   // 配置改了要重新激活（生产里这是"改配置要重启"）
        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(2, fixture.vec.embedCalls.get(),
                "clean_rev 变了 ⇒ 必须整体重算（不能拿旧向量配新文本）");
        assertTrue(read(fixture, docId).contains("\"clean_rev\":1"),
                "重算后缓存头部要写新版本：" + read(fixture, docId));

        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(2, fixture.vec.embedCalls.get(), "版本稳定后恢复命中缓存");
    }

    @Test
    @DisplayName("chunk_rev 变化同样失效（切分参数变了，向量必须重算）")
    void chunkRevChangeInvalidatesCache() {
        Fixture fixture = fixture(0);
        String docId = saveDoc(fixture.store);
        DocStore.Chunk chunk = fixture.chunks(docId);
        fixture.retrieval.embedChunks(List.of(chunk));
        fixture.settings.getPipeline().setChunkRev(5);
        PipelineRev.activate(fixture.settings);   // 同上：改配置要重新激活
        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(2, fixture.vec.embedCalls.get(), "chunk_rev 变了也必须重算");
        assertTrue(read(fixture, docId).contains("\"chunk_rev\":5"), read(fixture, docId));
    }

    @Test
    @DisplayName("接入前写的缓存（缺版本字段）⇒ 视为失效并重算（宁可重算一次，不拿旧向量充数）")
    void legacyCacheWithoutRevisionFieldsIsInvalidated() throws Exception {
        Fixture fixture = fixture(0);
        String docId = saveDoc(fixture.store);
        DocStore.Chunk chunk = fixture.chunks(docId);
        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(1, fixture.vec.embedCalls.get());

        Path path = fixture.retrieval.vectorPath(docId);
        String text = Files.readString(path)
                .replace("\"clean_rev\":0,", "")
                .replace("\"chunk_rev\":0,", "");
        Files.writeString(path, text);
        assertFalse(Files.readString(path).contains("clean_rev"), "前置条件：缓存已无版本字段");

        fixture.retrieval.embedChunks(List.of(chunk));
        assertEquals(2, fixture.vec.embedCalls.get(), "缺版本字段的老缓存必须失效重算");
    }

    // ══════════════════════════════════════════════════════════════

    private record Fixture(AiSettings settings, DocStore store, CountingVec vec, RetrievalService retrieval) {

        DocStore.Chunk chunks(String docId) {
            return store.iterChunks(store.get(docId)).get(0);
        }
    }

    private Fixture fixture(int cleanRev) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getPipeline().setCleanRev(cleanRev);
        settings.getPipeline().setChunkKeyFormat("legacy");
        DocStore store = new DocStore(tmp.resolve("docs"));
        CountingVec vec = new CountingVec();
        DocMetaStore metaStore = new DocMetaStore(tmp);
        RetrievalService retrieval = new RetrievalService(settings, vec, store, null,
                TstRegistry.l2(settings), new SimpleMeterRegistry(), metaStore);
        return new Fixture(settings, store, vec, retrieval);
    }

    private String saveDoc(DocStore store) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        List<PageText> pages = new ArrayList<>();
        pages.add(new PageText(1, "甲".repeat(600)));
        doc.setPages(pages);
        return store.save("a.pdf", doc, 0L).getDocId();
    }

    private static String read(Fixture fixture, String docId) {
        try {
            return Files.readString(fixture.retrieval.vectorPath(docId));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 数 embed 调用次数（缓存是否命中就看它）。 */
    static final class CountingVec implements VecApi {

        final AtomicInteger embedCalls = new AtomicInteger();

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            embedCalls.incrementAndGet();
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
