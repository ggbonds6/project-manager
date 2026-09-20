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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        boolean rerankCalled;
        /** 每个文本拿到的向量（默认都一样）；用来验证"向量没和别的切片文本错配"。 */
        java.util.function.Function<String, float[]> vectorFor = text -> new float[] {1.0f, 0.0f};
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
            for (String text : texts) {
                out.add(vectorFor.apply(text));
            }
            return out;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            rerankCalled = true;
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

    /**
     * 离线替身：把 OpenSearch 适配层的 HTTP 层整个换掉（覆写 {@code health/search}），
     * 于是"路由是否走对了"可以在<b>不发一个网络请求</b>的前提下断言。
     * 字段都是包私有：测试类同包，直接读计数。
     */
    static final class StubIndex extends OpenSearchIndex {

        int healthCalls;
        int ensureIndexCalls;
        int upsertCalls;
        int upsertedDocs;
        int searchCalls;
        boolean indexSearchFails;
        /** kNN 的"返回值"：由测试按切片键造，模拟集群里已有的切片。 */
        List<SearchHit> hits = List.of();
        /** 写进索引的文档（按切片键取文本，用来断言"向量没和别的切片文本错配"）。 */
        final Map<String, String> textsById = new LinkedHashMap<>();

        StubIndex(AiSettings settings) {
            super(settings);
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            healthCalls++;
            return new VecHealth(true, "stub", List.of());
        }

        @Override
        public void ensureIndexOnce(int dimension) {
            ensureIndexCalls++;
        }

        @Override
        public void upsertAll(List<ChunkDoc> chunks) {
            upsertCalls++;
            upsertedDocs += chunks.size();
            for (ChunkDoc chunk : chunks) {
                textsById.put(chunk.id(), chunk.text());
            }
        }

        @Override
        public List<SearchHit> search(float[] queryVector, int k) {
            searchCalls++;
            if (indexSearchFails) {
                throw new VecClient.VecException("集群不可达（http://stub:9200）：ConnectException: Connection refused");
            }
            return hits;
        }
    }

    /** 文档库 + 向量缓存都落在临时目录，配置取 AiSettings 默认值（backend=local）。 */
    private RetrievalService service(DocStore store, VecApi vec) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        return new RetrievalService(settings, vec, store, null);
    }

    /** OpenSearch 后端的服务 + 离线索引替身。 */
    private RetrievalService openSearchService(DocStore store, VecApi vec, StubIndex index) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("opensearch");
        settings.getOpenSearch().setUrl("http://stub:9200");
        return new RetrievalService(settings, vec, store, index);
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

    /** {@code VEC_BACKEND=opensearch} 而 url 未配置：必须显式报错，且**不做任何检索工作**（不动向量、不动网络）。 */
    @Test
    void unconfiguredOpenSearchBackendFailsFast() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("opensearch");   // url 默认空
        FakeVec vec = new FakeVec();
        RetrievalService service = new RetrievalService(settings, vec, store, null);

        VecClient.VecException error = assertThrows(
                VecClient.VecException.class, () -> service.search("合同金额", null, null));
        assertTrue(error.getMessage().contains("OPENSEARCH_URL"),
                "报错要直接指向该配哪个项：" + error.getMessage());
        assertTrue(error.getMessage().contains("不静默退回"));
        assertTrue(vec.embedCalls.isEmpty(), "守卫必须在编码/检索之前失败，不能先干活再报错");
        assertFalse(vec.rerankCalled, "守卫必须在精排之前失败");
    }

    /** 未实现的后端取值同样要 fail-fast（对齐 Python 的"尚未实现"语义）。 */
    @Test
    void unknownBackendFailsFast() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("faiss");
        FakeVec vec = new FakeVec();
        RetrievalService service = new RetrievalService(settings, vec, store, null);

        VecClient.VecException error = assertThrows(
                VecClient.VecException.class, () -> service.search("合同金额", null, null));
        assertTrue(error.getMessage().contains("faiss"), "报错要说清是哪个后端：" + error.getMessage());
        assertTrue(vec.embedCalls.isEmpty(), "未知后端不该先编码再报错");
    }

    // ── OpenSearch 在线路由（离线：HTTP 层被替身换掉）─────────────

    /** 配好 opensearch 后：探活 → 确保索引 → 幂等 upsert → kNN 召回 → 精排，且带"未实测"标注。 */
    @Test
    void openSearchBackendRoutesThroughIndexAndReranks() {
        DocStore store = store();
        String docId = save(store, "a.pdf", "合同金额为一百万元，付款方式为分期付款。");
        String text = store.get(docId).pageText(1);
        String key = RetrievalService.chunkKey(new DocStore.Chunk(docId, "a.pdf", 1, text));

        StubIndex index = new StubIndex(new AiSettings());
        // kNN 的返回：模拟集群里已经有这个切片（分数是 cosinesimil 的相似度量纲）
        index.hits = List.of(new OpenSearchIndex.SearchHit(key, docId, "a.pdf", 1, 0.62, text));

        FakeVec vec = new FakeVec();
        vec.rerankFn = (query, documents) -> documents.isEmpty()
                ? List.of()
                : List.of(new RerankHit(0, 0.88, 0));
        SearchPort.SearchResult result = openSearchService(store, vec, index).search("付款方式", 2, null);

        assertEquals(1, index.healthCalls, "走 opensearch 前必须探活（连不上就显式报错）");
        assertEquals(1, index.ensureIndexCalls, "首次检索要确保索引存在");
        assertTrue(index.upsertCalls >= 1, "切片必须写进索引（幂等 upsert）");
        assertTrue(index.upsertedDocs >= 1);
        assertEquals(1, index.searchCalls, "向量召回必须由 kNN 完成，而不是进程内余弦");
        assertTrue(result.reranked(), "精排仍走 Reranker");
        assertEquals("hybrid", result.retrieval(), "kNN 命中即向量召回成功 → hybrid");
        assertEquals(1, result.found());
        assertEquals(1, result.hits().get(0).pageNo(), "kNN 命中必须带页码（来源标注的前提）");
        assertEquals(0.62, result.hits().get(0).vectorScore(), 1e-9, "vector_score 取 kNN 的相似度");
        assertTrue(result.note().contains("未实测"), "必须在返回里诚实标注这条路径未实测：" + result.note());
    }

    /** local 后端绝不能碰 OpenSearch（防止"顺手也探一下"变成隐式依赖）。 */
    @Test
    void localBackendNeverTouchesOpenSearch() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");
        StubIndex index = new StubIndex(new AiSettings());

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getOpenSearch().setUrl("http://stub:9200");   // 就算配了地址，backend=local 也不该用
        RetrievalService service = new RetrievalService(settings, new FakeVec(), store, index);

        service.search("合同金额", null, null);

        assertEquals(0, index.healthCalls, "local 后端不该探活 OpenSearch");
        assertEquals(0, index.ensureIndexCalls);
        assertEquals(0, index.searchCalls);
    }

    /** 集群不可达（kNN 那一步抛）→ 显式冒泡，不许静默退回 local 结果。 */
    @Test
    void unreachableClusterFailsLoudly() {
        DocStore store = store();
        save(store, "a.pdf", "合同金额为一百万元。");
        StubIndex index = new StubIndex(new AiSettings());
        index.indexSearchFails = true;

        RetrievalService service = openSearchService(store, new FakeVec(), index);
        VecClient.VecException error = assertThrows(
                VecClient.VecException.class, () -> service.search("合同金额", null, null));

        assertTrue(error.getMessage().contains("集群不可达"), error.getMessage());
        assertEquals(1, index.searchCalls);
    }

    /**
     * 写进索引的向量必须和它自己的切片文本配对。
     *
     * <p>回归用例：切片向量来自 {@code embedChunks} 返回的 <b>Map</b>，迭代顺序没有保证；
     * 若实现里图省事把 {@code values()} 与 {@code chunks} 按下标 zip，向量就会挂到别的切片上——
     * 索引里全是错配向量，检索结果看起来正常却全错。这里让两页文本拿到的向量明显不同，
     * 谁被 zip 错位就会露出来。
     */
    @Test
    void indexedVectorStaysPairedWithItsOwnChunkText() {
        DocStore store = store();
        String docId = save(store, "a.pdf", "甲方信息" + "甲".repeat(600), "乙方信息" + "乙".repeat(600));
        StubIndex index = new StubIndex(new AiSettings());

        FakeVec vec = new FakeVec();
        vec.vectorFor = text -> text.startsWith("甲方") ? new float[] {1f, 0f} : new float[] {0f, 1f};
        openSearchService(store, vec, index).search("信息", null, null);

        assertEquals(2, index.textsById.size(), "两个切片都该写进索引");
        StoredDoc stored = store.get(docId);
        for (int pageNo = 1; pageNo <= 2; pageNo++) {
            String expectedText = stored.pageText(pageNo);
            String key = RetrievalService.chunkKey(new DocStore.Chunk(docId, "a.pdf", pageNo, expectedText));
            assertEquals(expectedText, index.textsById.get(key),
                    "第 " + pageNo + " 页的索引记录必须挂着自己的文本（键 = " + key + "）");
        }
        assertTrue(index.textsById.size() == 2 && index.textsById.values().stream().anyMatch(t -> t.startsWith("甲方")),
                "两页文本都要在索引里，且没有被彼此覆盖：" + index.textsById.keySet());
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

    // ── 全角/半角标点折叠（有意偏离 Python 版，见 RetrievalService 类注释）────

    /** 文档里是全角逗号的金额，用半角查询必须命中（Python 版这里漏召回）。 */
    @Test
    void halfWidthQueryHitsFullWidthPunctuationInDocument() {
        DocStore store = store();
        String original = "中标金额：人民币 7，982，300.00 元（含税）。";
        save(store, "a.pdf", original);
        FakeVec vec = new FakeVec();
        vec.embedFails = true;      // 只验证关键词那一路：向量挂了也照样要命中
        vec.rerankFails = true;

        SearchPort.SearchResult result = service(store, vec).search("7,982,300.00", 3, null);

        assertEquals(1, result.found(), "半角查询必须命中文档里的全角写法：" + result.note());
        // 返回给模型的必须是**原文**（折叠只发生在打分副本上）
        assertEquals(original, result.hits().get(0).text());
    }

    /** 反向：文档全角括号/冒号时，半角查询也要命中；且原文没有被改写。 */
    @Test
    void halfWidthQueryHitsFullWidthBracketsAndColon() {
        DocStore store = store();
        String original = "付款期限（三十日）：自验收合格之日起算。";
        save(store, "a.pdf", original);
        FakeVec vec = new FakeVec();
        vec.embedFails = true;
        vec.rerankFails = true;

        SearchPort.SearchResult result = service(store, vec).search("付款期限(三十日):自验收", 3, null);

        assertEquals(1, result.found(), "半角括号/冒号必须命中全角写法：" + result.note());
        assertEquals(original, result.hits().get(0).text(), "返回的 text 必须是原文");
    }

    /** 折叠函数本身：全角标点/空格 → 半角；中文与原文其余部分不动。 */
    @Test
    void foldMapsFullWidthPunctuationOnly() {
        assertEquals("7,982,300.00", RetrievalService.fold("7，982，300.00"));
        assertEquals("a:b;c(d)e%f/g-h", RetrievalService.fold("a：b；c（d）e％f／g－h"));
        assertEquals("甲 乙", RetrievalService.fold("甲\u3000乙"), "全角空格折成半角");
        assertEquals("中文标点。不动", RetrievalService.fold("中文标点。不动"), "句号（U+3002）不在全角 ASCII 区，保持原样");
        assertEquals("", RetrievalService.fold(null));
    }
}
