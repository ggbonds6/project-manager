package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化引用的回归测试（<b>离线</b>：检索用替身、文档库用 {@code @TempDir}）。
 *
 * <p>钉住四件事（都是"前端能不能跳到原文那一页"的前提）：
 * <ol>
 *   <li>同一页被命中两次<b>只产生一条引用</b>（否则前端会出现两条指向同一页的卡片）；</li>
 *   <li>编号按<b>首次命中顺序</b>分配且不会再变（编号已经写进发给模型的工具结果了）；</li>
 *   <li>没有任何命中时 {@code citations} 是<b>空列表</b>（不是 null、不是缺字段）；</li>
 *   <li>{@code read_page} 取回的整页同样登记（否则"读了整页再引用"就成了无出处的答案）。</li>
 * </ol>
 */
class CitationRegistryTest {

    /** 可编排命中的检索替身（不联网）。 */
    private static final class FakeSearchPort implements SearchPort {

        private List<Hit> hits = List.of();

        void setHits(List<Hit> hits) {
            this.hits = hits;
        }

        @Override
        public SearchResult search(String query, Integer topK, String docId) {
            return new SearchResult(hits.size(), hits, "hybrid", true, "");
        }
    }

    private static SearchPort.Hit hit(String docId, String filename, int pageNo, double score, String text) {
        return new SearchPort.Hit(docId, filename, pageNo, score, 0.5, 0.8, text);
    }

    @TempDir
    Path tempDir;

    private FakeSearchPort searchPort;
    private Tools tools;

    @BeforeEach
    void setUp() {
        DocStore store = new DocStore(tempDir);
        searchPort = new FakeSearchPort();
        tools = new Tools(searchPort, store);
    }

    /** 造一份入库文档（@TempDir 里，不碰真实 work/docs）。 */
    private void saveDoc(String docId, String filename, String... pageTexts) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("doc_id", docId);
        doc.put("filename", filename);
        doc.put("kind", "text_pdf");
        List<Map<String, Object>> pages = new ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("page_no", i + 1);
            page.put("text", pageTexts[i]);
            pages.add(page);
        }
        doc.put("pages", pages);
        doc.put("uploaded_at", "2026-09-20T10:00:00");
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValue(tempDir.resolve(docId + ".json").toFile(), doc);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hitsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("hits");
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 同一页命中两次 → 只产生一条引用
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：同一页的两个切片（或两次检索）各占一个 cite 号。
     *
     * <p>后果很具体：前端引用列表里出现两条一模一样的"合同.pdf 第 3 页"，
     * 模型也可能写出两个不同的号指向同一页——而它其实只读过一页。
     */
    @Test
    void samePageHitTwiceRegistersOneCitation() {
        searchPort.setHits(List.of(
                hit("aaaa1111", "合同.pdf", 3, 0.91, "付款条款：验收后 30 日内支付。"),
                hit("aaaa1111", "合同.pdf", 3, 0.72, "第 3 页的另一段：验收后 30 日内支付。")));

        CitationRegistry citations = new CitationRegistry();
        Map<String, Object> result = tools.searchDocuments("付款条款", 5, null, citations);

        assertEquals(1, citations.size(), "同一页只登记一条");
        assertEquals(1, hitsOf(result).get(0).get("cite"));
        assertEquals(1, hitsOf(result).get(1).get("cite"), "同一页的第二次命中必须复用同一个编号");

        List<Map<String, Object>> rows = citations.toList();
        assertEquals(1, rows.size());
        assertEquals("aaaa1111", rows.get(0).get("doc_id"));
        assertEquals(3, rows.get(0).get("page_no"));
        // 同一页多次命中时分数/片段取更高的那次（0.91 的那段）
        assertEquals(0.91, rows.get(0).get("score"));
        assertTrue(String.valueOf(rows.get(0).get("snippet")).contains("付款条款"));
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 编号按首次命中顺序分配、且不会再变
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：第二轮检索把已有编号挤掉/重排。
     *
     * <p>编号在模型看见命中的那一刻就已经写进工具结果，模型据此写下 {@code [1][2]}；
     * 如果后面的检索能改动编号，答案里的 {@code [2]} 就会指向另一页——比没有引用更糟。
     */
    @Test
    void citeNumbersFollowFirstHitOrderAndNeverChange() {
        CitationRegistry citations = new CitationRegistry();

        // 第一轮：A 文档 P5 → 1，A 文档 P3 → 2
        searchPort.setHits(List.of(
                hit("aaaa1111", "合同.pdf", 5, 0.90, "第五页原文"),
                hit("aaaa1111", "合同.pdf", 3, 0.80, "第三页原文")));
        Map<String, Object> first = tools.searchDocuments("付款条款", 5, null, citations);
        assertEquals(1, hitsOf(first).get(0).get("cite"));
        assertEquals(2, hitsOf(first).get(1).get("cite"));

        // 第二轮：P3 再次命中（仍是 2，分数升级到更高），新的 B 文档 P1 → 3
        searchPort.setHits(List.of(
                hit("aaaa1111", "合同.pdf", 3, 0.99, "第三页原文（更完整的条款）"),
                hit("bbbb2222", "中标通知书.pdf", 1, 0.60, "第一页原文")));
        Map<String, Object> second = tools.searchDocuments("验收", 5, null, citations);
        assertEquals(2, hitsOf(second).get(0).get("cite"), "老编号不能因为新检索而改变");
        assertEquals(3, hitsOf(second).get(1).get("cite"), "新页按首次命中顺序取下一个号");

        List<Map<String, Object>> rows = citations.toList();
        assertEquals(List.of(1, 2, 3), rows.stream().map(row -> row.get("index")).toList());
        assertEquals(List.of(5, 3, 1), rows.stream().map(row -> row.get("page_no")).toList());
        assertEquals(List.of("aaaa1111", "aaaa1111", "bbbb2222"),
                rows.stream().map(row -> row.get("doc_id")).toList());
        // 第二轮用更高分重命中同一页：编号不变，分数刷新
        assertEquals(0.99, rows.get(1).get("score"));
    }

    /** 引用表里的顺序必须是编号顺序（前端按下标渲染，顺序错了跳转就全错）。 */
    @Test
    void citationListIsInIndexOrder() {
        CitationRegistry citations = new CitationRegistry();
        searchPort.setHits(List.of(
                hit("aaaa1111", "合同.pdf", 4, 0.4, "第四页"),
                hit("bbbb2222", "招标文件.pdf", 2, 0.9, "第二页"),
                hit("aaaa1111", "合同.pdf", 1, 0.7, "第一页")));

        tools.searchDocuments("金额", 5, null, citations);
        List<Map<String, Object>> rows = citations.toList();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).get("index"), "citations[" + i + "] 的 index 必须是 " + (i + 1));
        }
        assertEquals(List.of(4, 2, 1), rows.stream().map(row -> row.get("page_no")).toList());
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 无命中 → citations = []
    // ══════════════════════════════════════════════════════════════════

    /** 挡的是：没有引用时给出 null（前端遍历会炸）或缺字段（前端读不到）。 */
    @Test
    void noHitsYieldsEmptyCitations() {
        CitationRegistry citations = new CitationRegistry();
        searchPort.setHits(List.of());

        Map<String, Object> result = tools.searchDocuments("不存在的内容", 5, null, citations);
        assertEquals(0, result.get("found"));
        assertEquals(List.of(), citations.toList());
        assertEquals(0, citations.size());

        // 响应里必须是空列表，而且既有字段一个都不能少
        QaService.QaResult qa = new QaService.QaResult(
                "文档中未找到", List.of(), citations.toList(), List.of(), Map.of(), "done", "");
        Map<String, Object> dict = qa.toDict();
        assertEquals(List.of(), dict.get("citations"));
        assertEquals(List.of("answer", "trace", "scope", "llm", "stopped_reason", "error", "citations"),
                new ArrayList<>(dict.keySet()), "既有键的名字与顺序都不许动，新字段只能追加在末尾");
    }

    // ══════════════════════════════════════════════════════════════════
    // read_page：整页读取也要登记
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：模型走 {@code read_page} 看原文后引用它，却没有任何出处可跳。
     *
     * <p>同一页先被检索命中过时，这里必须复用原编号（不能出现"检索一个号、读页又一个号"）。
     */
    @Test
    void readPageRegistersPageAndSharesNumberWithSearchHit() throws Exception {
        saveDoc("aaaa1111", "合同.pdf", "第一页正文", "第二页正文：验收后 30 日内支付。");

        CitationRegistry citations = new CitationRegistry();
        searchPort.setHits(List.of(hit("aaaa1111", "合同.pdf", 2, 0.88, "第二页正文：验收后 30 日内支付。")));
        tools.searchDocuments("付款", 5, null, citations);

        Map<String, Object> page = tools.readPage("aaaa1111", 2, citations);
        assertEquals(1, page.get("cite"), "同一页必须复用检索时分配的编号（它是第一处被引用的页）");
        assertEquals(1, citations.size());

        // 另一页（没被检索命中过）走 read_page：新分配一个号，分数记 0.0（无检索分数）
        Map<String, Object> other = tools.readPage("aaaa1111", 1, citations);
        assertEquals(2, other.get("cite"));
        List<Map<String, Object>> rows = citations.toList();
        assertEquals(2, rows.size());
        assertEquals("第一页正文", rows.get(1).get("snippet"));
        assertEquals(0.0, rows.get(1).get("score"));

        // 空白页/越界页没有正文可引，不登记（否则引用表里会出现一条空片段）
        assertTrue(tools.readPage("aaaa1111", 99, citations).containsKey("error"));
        assertEquals(2, citations.size(), "空白/越界页不产生引用");
    }

    // ══════════════════════════════════════════════════════════════════
    // snippet 与向后兼容
    // ══════════════════════════════════════════════════════════════════

    /** snippet 去换行 + ≤200 字（前端卡片是一行摘要，换行会让卡片被撑开）。 */
    @Test
    void snippetStripsNewlinesAndIsTruncated() {
        String messy = "第一行\r\n第二行\t带制表  " + "正文".repeat(300);
        String snippet = CitationRegistry.normalizeSnippet(messy);

        assertFalse(snippet.contains("\n"), "snippet 不能含换行");
        assertFalse(snippet.contains("\r"));
        assertFalse(snippet.contains("\t"));
        assertTrue(snippet.length() <= CitationRegistry.MAX_SNIPPET_CHARS,
                "snippet 必须 ≤ " + CitationRegistry.MAX_SNIPPET_CHARS + " 字，实际 " + snippet.length());
        assertTrue(snippet.endsWith("…"), "被截断时要能看出被截断了");

        // 短文本原样返回，不长出省略号
        assertEquals("付款条款原文", CitationRegistry.normalizeSnippet("  付款条款原文  "));
    }

    /**
     * 挡的是：给老调用点/单测传 {@code null} 注册表时行为变化。
     *
     * <p>不登记时命中里就不该有 {@code cite}（宁缺勿假：一个凭空出现的编号会让调用方以为
     * 有对应的引用表）。
     */
    @Test
    void withoutRegistryHitsHaveNoCiteField() {
        searchPort.setHits(List.of(hit("aaaa1111", "合同.pdf", 3, 0.9, "第三页")));
        Map<String, Object> result = tools.searchDocuments("付款", 5, null);

        Map<String, Object> first = hitsOf(result).get(0);
        assertFalse(first.containsKey("cite"));
        // 老字段一个不少（既有契约不变）
        assertEquals("aaaa1111", first.get("doc_id"));
        assertEquals("合同.pdf", first.get("filename"));
        assertEquals(3, first.get("page_no"));
        assertEquals(0.9, first.get("score"));
        assertEquals(0.5, first.get("keyword_score"));
        assertEquals(0.8, first.get("vector_score"));
        assertEquals("第三页", first.get("text"));
    }

    /** 命中字段顺序固定（Map.of 的顺序是未指定的，JSON 字段顺序会漂）。 */
    @Test
    void hitMapKeepsFieldOrder() {
        assertEquals(List.of("doc_id", "filename", "page_no", "score", "keyword_score", "vector_score", "text"),
                new ArrayList<>(hit("aaaa1111", "合同.pdf", 3, 0.9, "第三页").toMap().keySet()));
    }

    /** 经 {@code execute} 走一遍：模型发的工具调用同样带 cite（这是模型唯一能看到编号的路径）。 */
    @Test
    void executeRegistersCitations() {
        searchPort.setHits(List.of(hit("aaaa1111", "合同.pdf", 3, 0.9, "第三页")));
        CitationRegistry citations = new CitationRegistry();

        Map<String, Object> result = tools.execute(
                "search_documents", Map.of("query", "付款条款"), citations);
        assertEquals(1, hitsOf(result).get(0).get("cite"));
        assertEquals(1, citations.size());

        // calculate 与注册表无关，不受影响
        assertEquals("3", tools.execute("calculate", Map.of("expression", "1 + 2"), citations).get("result"));
    }
}
