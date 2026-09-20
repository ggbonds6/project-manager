package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索链路（{@code RetrievalService}）：精排排序、关键词降级、向量缓存、切片键、后端守卫。
 *
 * <p>1:1 搬自 {@code ai-service/tests/test_retrieval.py}，行为规格以它为准。
 *
 * <p>全程<b>离线</b>：向量化与重排都用假 {@link VecApi} 替掉——真实模型
 * （Qwen3-VL-Embedding/Reranker）在政务内网，开发机连不上，所以这里只钉"接线与顺序"，
 * 不钉模型质量。文档库与向量缓存目录都用 {@link TempDir}，不碰仓库里的 {@code work/}。
 */
class RetrievalServiceTest {

    @TempDir
    Path tmp;

    /** 记录调用次数的假向量客户端：向量恒定，重排按入参顺序给递减分。 */
    private static final class FakeVec implements VecApi {

        final List<Integer> embedCalls = new ArrayList<>();
        boolean embedFails;
        boolean rerankFails;
        /** 重排替身：返回 (入参下标, 分数)，默认按入参顺序递减。 */
        java.util.function.BiFunction<String, List<String>, List<RerankHit>> rerankFn =
                (query, documents) -> {
                    List<RerankHit> hits = new ArrayList<>();
                    for (int i = 0; i < documents.size(); i++) {
                        hits.add(new RerankHit(i, 1.0 - i * 0.1, i));
                    }
                    return hits;
                };

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            if (embedFails) {
                throw new VecClient.VecException("HTTP 502: embeddings upstream error");
            }
            embedCalls.add(texts.size());
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[] {1.0f, 0.0f});
            }
            return out;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            if (rerankFails) {
                throw new VecClient.VecException("HTTP 502: rerank upstream error");
            }
            List<RerankHit> hits = rerankFn.apply(query, documents);
            return topK != null && topK > 0 && hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "fake", List.of());
        }

        @Override
        public double cosine(float[] a, float[] b) {
            if (a == null || b == null || a.length == 0 || a.length != b.length) {
                return 0.0;
            }
            double dot = 0;
            double na = 0;
            double nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += (double) a[i] * b[i];
                na += (double) a[i] * a[i];
                nb += (double) b[i] * b[i];
            }
            return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
        }
    }

    /** 文档库 + 向量缓存都落在临时目录，配置取 AiSettings 默认值。 */
    private RetrievalService service(DocStore store, VecApi vec) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        return new RetrievalService(settings, vec, store, null);
    }

    private DocStore store() {
        return new DocStore(tmp.resolve("docs"));
    }

    private static String save(DocStore store, String name, String... texts) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        List<PageText> pages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            pages.add(new PageText(i + 1, texts[i]));
        }
        doc.setPages(pages);
        return store.save(name, doc, 0L).getDocId();
    }

    // ── 用例 1: test_hybrid_keeps_rerank_order ──────────────────

    @Test
    void hybridKeepsRerankOrder() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元，付款方式为分期付款。");
        save(store, "b.pdf", "验收标准参照国家标准执行。");

        FakeVec vec = new FakeVec();
        // 故意把"候选里的第 2 条"排到第一 —— 最终顺序必须由 Reranker 决定，否则精排等于没接
        vec.rerankFn = (query, documents) -> List.of(new RerankHit(1, 0.9, 0), new RerankHit(0, 0.1, 1));
        RetrievalService service = service(store, vec);

        SearchPort.SearchResult result = service.search("付款方式", 2, null);

        assertEquals("hybrid", result.retrieval());
        assertTrue(result.reranked());
        assertEquals(List.of(0.9, 0.1), result.hits().stream().map(SearchPort.Hit::score).toList());
    }

    // ── 用例 2: test_degrades_to_keyword_when_vec_unavailable ───

    @Test
    void degradesToKeywordWhenVecUnavailable() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");

        FakeVec vec = new FakeVec();
        vec.embedFails = true;
        vec.rerankFails = true;
        RetrievalService service = service(store, vec);

        SearchPort.SearchResult result = service.search("合同金额", 3, null);

        assertEquals("keyword", result.retrieval());
        assertFalse(result.reranked());
        assertEquals(1, result.found());
        assertTrue(result.note().contains("向量服务不可用"), "降级原因必须写进 note，不许静默：" + result.note());
        // 降级后依然带页码来源："没找到"是可见的，这正是检索降级与 OCR 降级性质不同的地方
        assertEquals(1, result.hits().get(0).pageNo());
    }

    // ── 用例 3: test_vector_cache_avoids_reembedding_chunks ─────

    @Test
    void vectorCacheAvoidsReembeddingChunks() {
        DocStore store = store();
        save(store, "a.pdf", "第一页内容".repeat(5));

        FakeVec vec = new FakeVec();
        RetrievalService service = service(store, vec);

        service.search("内容", null, null);
        assertEquals(2, vec.embedCalls.size(), "首次：切片 + query 各一次");
        assertEquals(List.of(1, 1), List.copyOf(vec.embedCalls));

        vec.embedCalls.clear();
        service.search("内容", null, null);
        assertEquals(List.of(1), List.copyOf(vec.embedCalls), "第二次：切片命中缓存，只编码 query");
    }

    // ── 用例 4: test_chunk_key_follows_text_content ─────────────

    @Test
    void chunkKeyFollowsTextContent() {
        DocStore.Chunk chunk = new DocStore.Chunk("d", "a.pdf", 1, "甲");
        assertEquals(
                RetrievalService.chunkKey(chunk),
                RetrievalService.chunkKey(new DocStore.Chunk("d", "a.pdf", 1, "甲")));
        assertNotEquals(
                RetrievalService.chunkKey(chunk),
                RetrievalService.chunkKey(new DocStore.Chunk("d", "a.pdf", 1, "乙")),
                "文本变了就当新切片，向量缓存据此自然失效");
    }

    /**
     * 切片键带内容指纹的后果：内容改了 → 键变了 → 缓存里没有这个键 → 该切片必须重算。
     *
     * <p>两次都用同一个页面（同一 docId、同一 pageNo），只有文本不同——这样若实现里偷懒用
     * {@code docId:pageNo} 当缓存键，本用例就会失败（那正是"改了文本却复用旧向量"的 bug）。
     */
    @Test
    void contentChangeInvalidatesCachedVector() throws Exception {
        DocStore store = store();
        String docId = save(store, "a.pdf", "甲".repeat(600));

        FakeVec vec = new FakeVec();
        RetrievalService service = service(store, vec);
        service.search("甲", null, null);
        assertTrue(Files.isRegularFile(tmp.resolve("vectors").resolve(docId + ".json")), "向量应落缓存");
        assertEquals(List.of(1, 1), List.copyOf(vec.embedCalls), "首次：切片 + query 各一次");

        StoredDoc stored = store.get(docId);
        StoredDoc.PageInfo page = new StoredDoc.PageInfo();
        page.setPageNo(1);
        page.setText("乙".repeat(600));
        stored.setPages(List.of(page));
        store.write(stored);

        vec.embedCalls.clear();
        service.search("乙", null, null);
        assertEquals(List.of(1, 1), List.copyOf(vec.embedCalls),
                "内容变了 → 指纹变了 → 缓存不命中：切片（1）与 query（1）各编码一次");
    }

    // ── 用例 5: test_unimplemented_backend_fails_fast ───────────

    /** {@code VEC_BACKEND=opensearch} 而 url 未配置：必须显式报错，不许静默退回进程内检索。 */
    @Test
    void unconfiguredOpenSearchBackendFailsFast() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("opensearch");   // url 默认空
        RetrievalService service = new RetrievalService(settings, new FakeVec(), store, null);

        VecClient.VecException error = assertThrows(
                VecClient.VecException.class, () -> service.search("合同金额", null, null));
        assertTrue(error.getMessage().contains("opensearch"), "报错要说清是哪个后端：" + error.getMessage());
        assertTrue(error.getMessage().contains("未配置"), "报错要说清缺什么配置：" + error.getMessage());
    }

    /** 未实现的后端取值同样要 fail-fast（对齐 Python 的"尚未实现"语义）。 */
    @Test
    void unknownBackendFailsFast() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("faiss");
        RetrievalService service = new RetrievalService(settings, new FakeVec(), store, null);

        assertThrows(VecClient.VecException.class, () -> service.search("合同金额", null, null));
    }

    // ── 补充：空查询 / 空库 / 关键词打分的行为 ───────────────────

    @Test
    void emptyQueryAndEmptyLibraryReturnNone() {
        DocStore store = store();
        RetrievalService service = service(store, new FakeVec());

        SearchPort.SearchResult blank = service.search("   ", null, null);
        assertEquals("none", blank.retrieval());
        assertEquals(0, blank.found());
        assertFalse(blank.reranked());

        SearchPort.SearchResult empty = service.search("合同金额", null, null);
        assertEquals("none", empty.retrieval());
        assertTrue(empty.note().contains("还没有切片"));
    }

    /** 2-gram 停用表必须生效："什么"这类高频无区分度的二元组不进检索项。 */
    @Test
    void stopBigramsAreDroppedAndTermsAreTwoGram() {
        List<String> terms = RetrievalService.queryTerms("如何付款 K100AI-8B");
        assertTrue(terms.contains("付款"), "中文取 2-gram：" + terms);
        assertFalse(terms.contains("如何"), "停用二元组必须被丢掉：" + terms);
        assertTrue(terms.contains("k100ai-8b"), "英文/数字取整词：" + terms);
    }

    /** 关键词召回带页码：命中必须能标来源。 */
    @Test
    void keywordHitCarriesPageNumber() {
        DocStore store = store();
        save(store, "a.pdf", "第一页无关内容", "第二页写的是付款方式与分期条款。");
        FakeVec vec = new FakeVec();
        vec.embedFails = true;
        vec.rerankFails = true;

        SearchPort.SearchResult result = service(store, vec).search("付款方式", 3, null);

        assertEquals(1, result.found());
        assertEquals(2, result.hits().get(0).pageNo(), "页码必须带着走（来源标注的前提）");
        assertEquals("a.pdf", result.hits().get(0).filename());
    }
}
