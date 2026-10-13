package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.retrieval.SearchScope;
import com.pmgt.ai.module.store.DocStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级作用域<b>硬下推</b>的回归测试（离线：检索用替身）。
 *
 * <p>钉住四件事（G3 + G17 的验收口径）：
 * <ol>
 *   <li>请求作用域是<b>上界</b>：模型给的 {@code doc_id} 与它<b>取交集</b>，
 *       既不能放大到全库，也不能被模型悄悄换掉；</li>
 *   <li>模型给的 docId <b>越界</b>时返回"越界即空"的结构化结果（{@code scope_violation}），
 *       <b>不退化成"在请求作用域内检索"</b>；</li>
 *   <li>无请求作用域时行为与本版本前<b>逐字节一致</b>（模型给 docId → 只搜那份文档；不给 → 全库）；
 *   </li>
 *   <li>密级维度参与过滤（{@link SearchScope#matches} 的第四维），且缺失元数据不算命中。</li>
 * </ol>
 */
class ToolsScopePushdownTest {

    /** 记录调用的检索替身：区分"带上界"与"不带"两条入口。 */
    private static final class RecordingSearchPort implements SearchPort {

        String calledVia;
        String query;
        Integer topK;
        String docId;
        SearchScope scope;
        SearchResult result = new SearchResult(0, List.of(), "none", false, "");

        @Override
        public SearchResult search(String query, Integer topK, String docId) {
            this.calledVia = "search";
            this.query = query;
            this.topK = topK;
            this.docId = docId;
            return result;
        }

        @Override
        public SearchResult searchInScope(String query, Integer topK, SearchScope scope) {
            this.calledVia = "searchInScope";
            this.query = query;
            this.topK = topK;
            this.scope = scope;
            return result;
        }
    }

    @TempDir
    Path tempDir;

    private RecordingSearchPort port;
    private Tools tools;

    @BeforeEach
    void setUp() {
        port = new RecordingSearchPort();
        tools = new Tools(port, new DocStore(tempDir));
    }

    /** ① 模型给的 docId 被并入请求作用域（交集），检索走带作用域的入口。 */
    @Test
    void modelDocIdIsIntersectedWithRequestScope() {
        SearchScope request = SearchScope.ofDocIds(List.of("docA", "docB"));

        tools.searchDocuments("付款条款", 5, "docA", null, request);

        assertEquals("searchInScope", port.calledVia, "请求作用域存在时必须走带作用域入口");
        assertNotNull(port.scope);
        assertEquals(Set.of("docA"), port.scope.docIds(),
                "模型只能把范围收窄：结果应是 {docA}，而不是请求作用域或全库");
        assertEquals(5, port.topK);
    }

    /** ② 越界即空：模型点名请求作用域外的文档 → 结构化拒绝，且**不发起检索**。 */
    @Test
    void outOfScopeDocIdIsRefusedWithoutSearching() {
        SearchScope request = SearchScope.ofDocIds(List.of("docA"));

        Map<String, Object> out = tools.searchDocuments("付款条款", 5, "docZ", null, request);

        assertEquals(0, out.get("found"));
        assertEquals(Boolean.TRUE, out.get("scope_violation"));
        assertNull(port.calledVia, "越界必须立刻返回，不能退化成在请求作用域内检索");
        assertTrue(String.valueOf(out.get("hint")).contains("越界即空"),
                "文案必须说清是越界，而不是「没检索到」：" + out.get("hint"));
    }

    /** ③ 无请求作用域：模型给 docId → 只搜那份；不给 → 全库（与老行为一致）。 */
    @Test
    void withoutRequestScopeBehavesAsBefore() {
        tools.searchDocuments("付款条款", 5, "docA", null, null);
        assertEquals("searchInScope", port.calledVia, "docId 一直是硬约束（下推成单文档作用域）");
        assertEquals(Set.of("docA"), port.scope.docIds());

        port = new RecordingSearchPort();
        tools = new Tools(port, new DocStore(tempDir));
        tools.searchDocuments("付款条款", 5, null, null, SearchScope.empty());
        assertEquals("search", port.calledVia, "无任何约束时走无作用域入口，等价于全库");
        assertNull(port.docId);
    }

    /** 请求作用域里只有项目/密级维度时，docId 仍要与这些维度求交（走带作用域入口）。 */
    @Test
    void projectAndSecurityDimensionsStillApply() {
        SearchScope request = SearchScope.of(null, null, List.of("P1"), List.of("内部"));

        tools.searchDocuments("付款条款", 5, "docA", null, request);

        assertEquals("searchInScope", port.calledVia);
        assertEquals(Set.of("P1"), port.scope.projectIds());
        assertEquals(Set.of("内部"), port.scope.securityLevels());
        assertTrue(port.scope.docIds().contains("docA"), "模型给的 docId 仍作为收窄条件生效");
    }

    /** ④ 密级维度：给了值就必须命中；元数据缺失（空串）不算命中。 */
    @Test
    void securityLevelDimensionMatchesStrictly() {
        // 密级值刻意用 ASCII：本模块的测试编译编码不是 UTF-8（见 pom 里没有 sourceEncoding），
        // 源码里的中文字面量在断言里会变成乱码 —— 用 ASCII 值可以稳定钉住语义。
        SearchScope secret = SearchScope.of(null, null, null, List.of("L2"));

        assertTrue(secret.constrained(), "密级给了值就是有约束");
        assertTrue(secret.matches("docA", new com.pmgt.ai.module.retrieval.DocMeta(
                "", "", "", "L2", "", "", "")));
        assertFalse(secret.matches("docA", new com.pmgt.ai.module.retrieval.DocMeta(
                "", "", "", "L1", "", "", "")));
        assertFalse(secret.matches("docA", com.pmgt.ai.module.retrieval.DocMeta.EMPTY),
                "缺失密级元数据必须算越界（缺失 ≠ 通配）");
        assertTrue(secret.describe().contains("security_levels=[L2]"), secret.describe());
    }

    /** 交集语义本身：两侧都空 → 另一侧；都有值 → retainAll；一侧为空维度 → 取另一侧。 */
    @Test
    void intersectSemantics() {
        SearchScope none = SearchScope.empty();
        SearchScope a = SearchScope.ofDocIds(List.of("a", "b"));
        assertEquals(a, none.intersect(a));

        SearchScope b = SearchScope.ofDocIds(List.of("b", "c"));
        assertEquals(Set.of("b"), a.intersect(b).docIds());
        assertFalse(a.intersect(b).provablyEmptyAgainst(b));

        SearchScope z = SearchScope.ofDocIds(List.of("z"));
        assertTrue(z.provablyEmptyAgainst(a),
                "z 与上界 a 无交集 ⇒ 可被证明为空（实现里拿到的是交集，本断言钉住判据本身）");
    }
}
