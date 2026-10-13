package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.testutil.TstRegistry;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.store.DocStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条向量后端（{@code local} 与 {@code opensearch}）的<b>结构一致</b>与<b>作用域硬约束</b>。
 *
 * <p>① <b>结构一致（要求 ①）</b>：同一批切片、同一个 query、同一套替身向量/重排，
 * 两条后端产出的 {@link SearchPort.SearchResult} 必须逐字段一致（命中数、<b>页码</b>、
 * 文档、顺序、分数）。z这里用 {@link InMemoryIndex} 当"假集群"——它是**真算余弦**的内存实现，
 * 不是"返回预先写好结果"的桩，所以顺序一致这件事是有意义的。
 *
 * <p>② <b>作用域硬约束（要求 ④）</b>：带 {@code projectIds}/{@code attachmentIds} 的约束下，
 * 作用域外的文档（以及<b>元数据缺失</b>的文档）<b>不可能被返回</b>——两条后端都要成立。
 */
class RetrievalBackendParityTest {

    @TempDir
    Path tmp;

    // ── 替身：确定性向量（按字符计数）+ 重排保持入参顺序 ─────────────

    private static final class FakeVec implements VecApi {

        private float[] vectorFor(String text) {
            String value = text == null ? "" : text;
            return new float[] {count(value, '甲'), count(value, '乙'), 1.0f};
        }

        private static float count(String text, char ch) {
            int total = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == ch) {
                    total++;
                }
            }
            return total;
        }

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                out.add(vectorFor(text));
            }
            return out;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            List<RerankHit> hits = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                hits.add(new RerankHit(i, 1.0 - i * 0.01, i));   // 保持入参顺序 → 两条后端应当得到同一顺序
            }
            return hits;
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
     * 离线"假集群"：<b>真算余弦</b>的内存 kNN 实现。
     *
     * <p>为什么不用"返回预置结果"的桩：一致性断言的价值在于"两条后端各自算出来的东西一样"，
     * 预置结果只能证明接线通了。这里按 chunk_key 存切片、按 {@link SearchScope} 过滤、
     * 用与 local 同一个余弦函数排序——顺序对不上就一定是实现差异。
     */
    static final class InMemoryIndex extends OpenSearchIndex {

        private final VecApi vec;
        final Map<String, ChunkDoc> store = new ConcurrentHashMap<>();
        int ensureCalls;
        int searchCalls;

        InMemoryIndex(AiSettings settings, VecApi vec) {
            super(settings);
            this.vec = vec;
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "in-memory", List.of());
        }

        @Override
        public void ensureIndexOnce(int dimension) {
            ensureCalls++;
        }

        @Override
        public void upsertAll(List<ChunkDoc> chunks) {
            for (ChunkDoc chunk : chunks) {
                store.put(chunk.id(), chunk);
            }
        }

        @Override
        public Set<String> existingChunkKeys(java.util.Collection<String> chunkKeys) {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (String key : chunkKeys) {
                if (store.containsKey(key)) {
                    out.add(key);
                }
            }
            return out;
        }

        @Override
        public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension,
                               Map<String, DocMeta> metaByDocId) {
            List<ChunkDoc> docs = toDocs(chunks, vectors, metaByDocId);
            upsertAll(docs);
            return docs.size();
        }

        @Override
        public List<SearchHit> search(float[] queryVector, int k) {
            return search(queryVector, null, k, SearchScope.empty());
        }

        @Override
        public List<SearchHit> search(float[] queryVector, int k, SearchScope scope) {
            return search(queryVector, null, k, scope);
        }

        @Override
        public List<SearchHit> search(float[] queryVector, String queryText, int k, SearchScope scope) {
            searchCalls++;
            List<SearchHit> hits = new ArrayList<>();
            for (ChunkDoc chunk : store.values()) {
                if (!scope.matches(chunk.docId(), chunk.meta())) {
                    continue;
                }
                double score = vec.cosine(queryVector, chunk.vector());
                hits.add(new SearchHit(chunk.id(), chunk.docId(), chunk.filename(), chunk.pageNo(),
                        score, chunk.text()));
            }
            hits.sort((a, b) -> {
                int byScore = Double.compare(b.score(), a.score());
                return byScore != 0 ? byScore : a.chunkKey().compareTo(b.chunkKey());
            });
            return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
        }
    }

    // ── 语料与装配 ────────────────────────────────────────────────

    private String save(DocStore store, String name, String... texts) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        List<PageText> pages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            pages.add(new PageText(i + 1, texts[i]));
        }
        doc.setPages(pages);
        return store.save(name, doc, 0L).getDocId();
    }

    private record Fixture(RetrievalService local, RetrievalService openSearch, InMemoryIndex cluster,
                           DocStore store, String docA, String docB, String docC) {
    }

    /** 三份文档：A 属 P55、B 属 P99、C <b>没有元数据</b>（用来钉"缺失 ≠ 通配"）。 */
    private Fixture fixture() {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        DocStore store = new DocStore(tmp.resolve("docs"));
        String docA = save(store, "a.pdf", "甲方合同金额为一百万元。");
        String docB = save(store, "b.pdf", "乙方验收标准参照国家标准执行。");
        String docC = save(store, "c.pdf", "丙方付款方式为分期付款，含预付款。");

        DocMetaStore metaStore = new DocMetaStore(tmp);
        metaStore.put(docA, new DocMeta("8842", "P55", "CONTRACT", "内部", "合同", "pm-attachment", ""));
        metaStore.put(docB, new DocMeta("8843", "P99", "ACCEPT", "公开", "验收", "pm-attachment", ""));

        FakeVec vec = new FakeVec();
        InMemoryIndex cluster = new InMemoryIndex(settings, vec);

        RetrievalService local = new RetrievalService(settings, vec, store, null,
                TstRegistry.l2(settings), new SimpleMeterRegistry(), metaStore);

        AiSettings osSettings = new AiSettings();
        osSettings.setWorkDir(tmp);
        osSettings.getRetrieval().setBackend("opensearch");
        osSettings.getOpenSearch().setUrl("http://in-memory:9200");
        RetrievalService openSearch = new RetrievalService(osSettings, vec, store, cluster,
                TstRegistry.l2(osSettings), new SimpleMeterRegistry(), metaStore);
        return new Fixture(local, openSearch, cluster, store, docA, docB, docC);
    }

    // ── ① local 与 opensearch 结构一致 ───────────────────────────

    @Test
    @DisplayName("同一语料同一 query：local 与 opensearch 的命中数/字段/页码/顺序/分数完全一致")
    void bothBackendsReturnIdenticalStructure() {
        Fixture fixture = fixture();

        SearchPort.SearchResult localResult = fixture.local().search("合同金额 付款", 5, null);
        SearchPort.SearchResult osResult = fixture.openSearch().search("合同金额 付款", 5, null);

        assertEquals(localResult.found(), osResult.found());
        assertEquals(localResult.retrieval(), osResult.retrieval());
        assertEquals(localResult.reranked(), osResult.reranked());
        assertTrue(localResult.found() > 0, "语料里应当有命中：" + localResult.note());

        List<Map<String, Object>> localHits = localResult.hits().stream().map(SearchPort.Hit::toMap).toList();
        List<Map<String, Object>> osHits = osResult.hits().stream().map(SearchPort.Hit::toMap).toList();
        assertEquals(localHits.size(), osHits.size());
        for (int i = 0; i < localHits.size(); i++) {
            assertEquals(localHits.get(i).keySet(), osHits.get(i).keySet(), "第 " + i + " 条命中的字段集合必须一致");
            assertEquals(localHits.get(i).get("doc_id"), osHits.get(i).get("doc_id"));
            assertEquals(localHits.get(i).get("filename"), osHits.get(i).get("filename"));
            assertEquals(localHits.get(i).get("page_no"), osHits.get(i).get("page_no"));
            assertEquals(localHits.get(i).get("text"), osHits.get(i).get("text"));
            assertEquals((double) localHits.get(i).get("vector_score"), (double) osHits.get(i).get("vector_score"), 1e-9,
                    "两条后端用的是同一套 cosinesimil 口径（这是「顺序对得上」的前提）");
        }
        assertTrue(fixture.cluster().store.size() >= 3, "opensearch 路径必须把切片写进索引");
        assertTrue(fixture.cluster().searchCalls >= 1);
    }

    // ── ② 作用域硬约束 ───────────────────────────────────────────

    @Test
    @DisplayName("作用域硬约束：projectIds 之外、以及元数据缺失的文档都不可能被返回（两条后端都成立）")
    void scopeFilterIsHardForBothBackends() {
        Fixture fixture = fixture();
        SearchScope onlyP55 = SearchScope.of(List.of(), List.of(), List.of("P55"));

        for (RetrievalService service : List.of(fixture.local(), fixture.openSearch())) {
            SearchPort.SearchResult result = service.searchInScope("付款 合同 验收", 10, onlyP55);
            assertTrue(result.found() > 0);
            for (SearchPort.Hit hit : result.hits()) {
                assertEquals(fixture.docA(), hit.docId(), "作用域外（P99）与无元数据的文档都不能出现");
            }
            assertFalse(result.hits().stream().anyMatch(h -> fixture.docB().equals(h.docId())));
            assertFalse(result.hits().stream().anyMatch(h -> fixture.docC().equals(h.docId())),
                    "元数据缺失的文档在带约束的维度上视为越界（缺失 ≠ 通配）");
        }
    }

    @Test
    @DisplayName("作用域只剩一份文档时（doc_ids）检索范围立即收窄；作用域内无内容时如实返回 0")
    void docIdScopeNarrowsSearch() {
        Fixture fixture = fixture();

        for (RetrievalService service : List.of(fixture.local(), fixture.openSearch())) {
            SearchPort.SearchResult onlyB = service.searchInScope("付款 合同", 10, SearchScope.ofDocIds(List.of(fixture.docB())));
            assertTrue(onlyB.hits().stream().allMatch(hit -> fixture.docB().equals(hit.docId())),
                    "doc_ids 是硬约束，不该召回别的文档");

            SearchPort.SearchResult emptyScope =
                    service.searchInScope("付款 合同", 10, SearchScope.ofDocIds(List.of("不存在的docid")));
            assertEquals(0, emptyScope.found(), "作用域内没有任何文档 → 如实返回 0，而不是退回全库");
            assertTrue(emptyScope.note().contains("作用域"), emptyScope.note());
        }
    }

    @Test
    @DisplayName("查询侧补算（懒补算）有明确计数：冷缓存下一次检索会被计入 ai.vector.lazy_backfill")
    void lazyBackfillIsCountedAndLogged() {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp.resolve("cold"));
        DocStore store = new DocStore(tmp.resolve("cold").resolve("docs"));
        save(store, "a.pdf", "甲方合同金额为一百万元。");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        RetrievalService service = new RetrievalService(settings, new FakeVec(), store, null,
                TstRegistry.l2(settings), registry, new DocMetaStore(tmp.resolve("cold")));
        service.search("合同金额", 3, null);

        double backfilled = registry.get("ai.vector.lazy_backfill").counter().count();
        assertTrue(backfilled >= 1, "查询侧补算必须有计数（P0-3：保留补齐能力但要能发现）");
        assertNotNull(registry.find("ai.retrieval.rerank.pairs"));
    }

    @Test
    @DisplayName("召回档位：默认 baseline=50；recall-mode=low 时用 recall-low（并保持可配置）")
    void recallModeIsConfigurableButDefaultsToBaseline() {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        RetrievalService service = new RetrievalService(settings, new FakeVec(),
                new DocStore(tmp.resolve("docs")), null, TstRegistry.l2(settings),
                new SimpleMeterRegistry(), new DocMetaStore(tmp));
        assertEquals(50, service.effectiveRecall());
        assertEquals(25, settings.getRetrieval().getRecallLow());
        assertEquals("baseline", settings.getRetrieval().getRecallMode());

        settings.getRetrieval().setRecallMode("low");
        assertEquals(25, service.effectiveRecall());
        settings.getRetrieval().setRecallMode("未知值");
        assertEquals(50, service.effectiveRecall(), "未知档位不能静默降配");
    }
}
