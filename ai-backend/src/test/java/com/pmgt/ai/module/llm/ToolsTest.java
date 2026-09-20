package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code tools.py} 的回归测试（<b>离线</b>：检索用替身、文档库用 {@code @TempDir}，不算真文档、不联网）。
 *
 * <p>重点两条（与 Python 版测试一一对应）：
 * <ul>
 *   <li>{@code calculate} 的<b>白名单</b>：只允许数字与 {@code + - * /}，且全程 {@code BigDecimal}；</li>
 *   <li>工具分发 {@code execute} 的参数过滤（Python 侧历史上被 lambda 包一层坑过一次）。</li>
 * </ul>
 */
class ToolsTest {

    /** 空检索替身：不联网，返回"没命中"。 */
    private static final class FakeSearchPort implements SearchPort {

        private final List<Hit> hits;
        private String lastQuery;
        private Integer lastTopK;
        private String lastDocId;

        FakeSearchPort(List<Hit> hits) {
            this.hits = hits;
        }

        @Override
        public SearchResult search(String query, Integer topK, String docId) {
            this.lastQuery = query;
            this.lastTopK = topK;
            this.lastDocId = docId;
            return new SearchResult(hits.size(), hits, "hybrid", true, "");
        }
    }

    @TempDir
    Path tempDir;

    private DocStore store;
    private Tools tools;

    @BeforeEach
    void setUp() {
        store = new DocStore(tempDir);
        tools = new Tools(new FakeSearchPort(List.of()), store);
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

    // ══════════════════════════════════════════════════════════════════
    // calculate：精确算术
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：{@code result} 变回数字类型（JSON 浮点会丢分位精度）。
     *
     * <p>审计口径要求"看到什么就是什么"，所以结果一律是字符串。
     */
    @Test
    void calculateReturnsStringResult() {
        Map<String, Object> result = tools.calculate("2394690 + 3192920");
        assertEquals("5587610", result.get("result"));
        assertInstanceOf(String.class, result.get("result"), "result 必须是字符串");
        assertEquals("2394690 + 3192920", result.get("expression"));
    }

    /**
     * 挡的是：算钱悄悄换回 float（{@code 0.1 + 0.2 != 0.3}）。
     *
     * <p>这条就是"金额计算用 BigDecimal 而不是 float"的钉子：一旦有人把求值改回 double，
     * {@code result} 会变成 {@code 0.30000000000000004}，这里立刻红。
     */
    @Test
    void calculateIsExactForTenths() {
        Map<String, Object> result = tools.calculate("0.1 + 0.2");
        assertEquals("0.3", result.get("result"));
        assertEquals("0.30", result.get("rounded_2"));
    }

    /** 挡的是：合同里抄来的金额（带千分位）算不了；以及"精确值 + 到分"两个口径混用。 */
    @Test
    void calculateHandlesCommasAndRounding() {
        assertEquals("1000000", tools.calculate("1,234,567.89 - 234,567.89").get("result"));
        // 除不尽时 result 保留高精度、rounded_2 才是到分（前端要哪个取哪个）
        assertEquals("0.33", tools.calculate("1 / 3").get("rounded_2"));
        assertEquals("5", tools.calculate("2 * 3 - 1").get("result"));
        // 全角逗号同样要能算（原文里常见）
        assertEquals("1000", tools.calculate("1，000 + 0").get("result"));
        // 一元正负号
        assertEquals("-6", tools.calculate("-2 * 3").get("result"));
    }

    /**
     * 挡的是：沙箱收紧之后又被放开。
     *
     * <p>实现刻意只留四则运算（见 {@code Tools} 的注释）：{@code 9**9**9} 这类表达式 200 字符的
     * 长度上限根本拦不住，只能靠"运算符白名单"。<b>任何拒绝都必须是结构化 error，不能抛异常</b>
     * （抛出去会中断整轮问答）。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "__import__(\"os\")",   // 任意代码执行：最直接的攻击面
            "(1).__class__",        // 属性访问：从这里能摸到 type/mro，进而逃出沙箱
            "9**9**9",              // 乘方：大整数幂会把 CPU/内存打满（长度上限挡不住它）
            "7 // 2",               // 取整：审计场景用不到，白名单刻意不给
            "7 % 2",                // 取模：同上
            "True",                 // bool 是 int 的子类，必须显式排除
            "abc",                  // 名字而非数字
            "1 +",                  // 语法错
    })
    void calculateRejectsNonWhitelisted(String expression) {
        Map<String, Object> result = tools.calculate(expression);
        assertTrue(result.containsKey("error"), "必须是结构化 error：" + expression);
        assertFalse(result.containsKey("result"), "被拒的表达式不能有 result：" + expression);
    }

    /** 挡的是：超长表达式（>200 字符）被硬算——它是拒绝乘方之外的第二道闸。 */
    @Test
    void calculateRejectsTooLongExpression() {
        assertEquals("表达式过长", tools.calculate("1+".repeat(101)).get("error"));
    }

    /** 挡的是：空表达式 / 除零把异常抛给上层。 */
    @Test
    void calculateEmptyAndDivisionByZero() {
        assertEquals("表达式为空", tools.calculate("").get("error"));
        assertEquals("表达式为空", tools.calculate("   ").get("error"));
        assertTrue(tools.calculate("1 / 0").containsKey("error"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具注册表与分发
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：分发时又用 lambda 之类丢掉参数。
     *
     * <p>Python 踩过的坑（代码注释里写着）：{@code inspect.signature(lambda **kw: ...)} 只看得到
     * {@code **kw}，参数过滤会把模型传来的参数<b>全丢掉</b>，症状是
     * {@code search_documents() missing 1 required positional argument: 'query'}。
     * 所以 {@code execute} 必须把多余/未知参数丢掉，同时保留真实参数。
     */
    @Test
    void executeFiltersParametersBySignature() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("expression", "2394690 + 3192920");
        args.put("多余参数", 1);
        assertEquals("5587610", tools.execute("calculate", args).get("result"));

        assertTrue(String.valueOf(tools.execute("不存在的工具", Map.of()).get("error"))
                .startsWith("未知工具"));
    }

    /** 空参数不能把工具打炸（模型偶尔发一个没有参数的工具调用）。 */
    @Test
    void executeToleratesMissingAndNullArguments() {
        assertNotNull(tools.execute("calculate", null));
        assertTrue(tools.execute("calculate", null).containsKey("error"));
        assertTrue(tools.execute("read_page", Map.of()).containsKey("error"));
    }

    /**
     * 挡的是：schema 里暴露了工具、本地却没有分发（模型调用必然报错）。
     *
     * <p>另外钉住 {@code list_documents} <b>已被删除</b>（清单改由 system 提示词注入）：
     * 它要是被"顺手加回来"，模型会先白烧一轮往返列文档。
     */
    @Test
    void toolRegistryMatchesDispatch() {
        Set<String> names = tools.toolNames();
        assertEquals(Set.of("search_documents", "read_page", "calculate"), names);
        // 分发必须认这些名字（不认识的会返回"未知工具"）
        for (String name : names) {
            String error = String.valueOf(tools.execute(name, Map.of()).getOrDefault("error", ""));
            assertFalse(error.startsWith("未知工具"), "schema 里有 " + name + "，分发却没有");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 检索（替身，纯离线）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：空查询 / 空文档库让工具炸掉。
     *
     * <p>检索是问答的第一步，它一抛异常整轮问答就断了。空输入应当返回 {@code found=0}
     * 加一句"换个关键词"的 hint（而不是异常，也不是编造内容）。
     */
    @Test
    void searchDocumentsEmptyInputIsSafe() {
        for (String query : new String[] {"", "   "}) {
            Map<String, Object> result = tools.searchDocuments(query, null, null);
            assertEquals(0, result.get("found"));
            assertEquals(List.of(), result.get("hits"));
            assertTrue(result.containsKey("hint"));
        }
        // 模型常把数字传成字符串（"10"）——实现里做容错，这里确认不炸
        assertEquals(0, tools.searchDocuments("付款条款", null, null).get("found"));
    }

    /** 命中时整形出的字段必须与 Python 版一致（模型与前端都按老契约读）。 */
    @Test
    void searchDocumentsShapesHits() {
        SearchPort.Hit hit = new SearchPort.Hit("abc123", "合同.pdf", 3, 0.91, 0.5, 0.8, "付款条款原文");
        Tools searching = new Tools(new FakeSearchPort(List.of(hit)), store);

        Map<String, Object> result = searching.searchDocuments("付款条款", 5, "abc123");
        assertEquals(1, result.get("found"));
        assertEquals("hybrid", result.get("retrieval"));
        assertEquals(true, result.get("reranked"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        Map<String, Object> first = hits.get(0);
        assertEquals("abc123", first.get("doc_id"));
        assertEquals("合同.pdf", first.get("filename"));
        assertEquals(3, first.get("page_no"));
        assertEquals(0.91, first.get("score"));
        assertEquals(0.5, first.get("keyword_score"));
        assertEquals(0.8, first.get("vector_score"));
        assertEquals("付款条款原文", first.get("text"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 读整页（临时文档库，纯离线）
    // ══════════════════════════════════════════════════════════════════

    /** 挡的是：找不到文档时抛异常而不是给出可读的错误。 */
    @Test
    void readPageUnknownDocReturnsError() {
        Map<String, Object> result = tools.readPage("no-such-doc", 1);
        assertTrue(String.valueOf(result.get("error")).contains("找不到文档"));
    }

    @Test
    void readPageReturnsFullTextAndTruncates() throws Exception {
        String longPage = "甲方与乙方就付款条款达成一致。".repeat(500);
        saveDoc("aaaa1111", "合同.pdf", "第一页正文", longPage, "");

        Map<String, Object> first = tools.readPage("aaaa1111", 1);
        assertEquals("aaaa1111", first.get("doc_id"));
        assertEquals("合同.pdf", first.get("filename"));
        assertEquals(1, first.get("page_no"));
        assertEquals(3, first.get("total_pages"));
        assertEquals("第一页正文", first.get("text"));

        // 超过 4000 字要截断并明确告知模型
        String truncated = String.valueOf(tools.readPage("aaaa1111", 2).get("text"));
        assertTrue(truncated.endsWith("…（本页过长，已截断）"));

        // 空白页给可读的错误，不返回空文本
        Map<String, Object> blank = tools.readPage("aaaa1111", 3);
        assertTrue(String.valueOf(blank.get("error")).contains("没有文本"));
        assertEquals(3, blank.get("total_pages"));

        // 越界页码同样走"没有文本"分支
        assertTrue(tools.readPage("aaaa1111", 99).containsKey("error"));
    }

    /** 页码不是数字时不能抛异常（模型偶尔传字符串/对象）。 */
    @Test
    void readPageInvalidPageNoIsStructured() throws Exception {
        saveDoc("bbbb2222", "合同.pdf", "正文");
        assertTrue(tools.readPage("bbbb2222", null).containsKey("error"));
        // 经 execute 走一遍：非法页码也不许抛异常
        Map<String, Object> viaExecute = tools.execute("read_page", Map.of("doc_id", "bbbb2222", "page_no", "第一页"));
        assertTrue(viaExecute.containsKey("error"));
    }

    /** 文档库真写了临时文件（自检一下替身没有骗自己）。 */
    @Test
    void tempStoreIsIsolatedFromRealWorkDir() throws Exception {
        saveDoc("cccc3333", "合同.pdf", "正文");
        assertTrue(Files.exists(tempDir.resolve("cccc3333.json")));
        assertEquals(1, store.get("cccc3333").getPageCount());
        assertTrue(new String(Files.readAllBytes(tempDir.resolve("cccc3333.json")), StandardCharsets.UTF_8)
                .contains("正文"));
    }
}
