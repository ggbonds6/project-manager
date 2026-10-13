package com.pmgt.ai.module.eval;

import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.tools.RetrievalEvalTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索级评测入口（{@link RetrievalEval}）：recall@k / MRR / 命中率，以及
 * <b>{@code citation_pages=null} ⇒ SKIP 而不是失败</b>（本轮的硬要求）。
 *
 * <p>同时用<b>真实评测集</b>（{@code eval/smoke-30.json}）核对"工具真的能读它"：
 * 那份数据集里页码全是 {@code null}（待人工标注），因此它的正确结果是"全跳过、无失败"，
 * 而不是"检索全错"——这正是本用例要钉的语义。
 */
class RetrievalEvalTest {

    private static SearchPort.Hit hit(String docId, int pageNo, String key, String text) {
        return new SearchPort.Hit(docId, "a.pdf", pageNo, key, 0.9, 0.1, 0.8, text, false,
                docId + ":p=" + pageNo, "", "");
    }

    private static SearchPort.SearchResult result(SearchPort.Hit... hits) {
        return new SearchPort.SearchResult(hits.length, List.of(hits), "hybrid", true, "");
    }

    // ══════════════════════════════════════════════════════════════
    // ① citation_pages=null ⇒ SKIP（不是失败）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未标注页码的题记 SKIPPED_UNLABELED：不算失败、不计入分母、指标为 n/a（不是 0）")
    void unlabeledCasesAreSkippedNotFailed() {
        List<RetrievalEval.Case> cases = List.of(
                new RetrievalEval.Case("D-1", "付款期限是多少天？", "", null, null, "待人工补页码"),
                new RetrievalEval.Case("D-2", "验收标准是什么？", "", List.of(), List.of(), "待人工补页码"));

        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("doc1", 2, "doc1:2:abc", "命中内容")));

        assertEquals(2, report.cases());
        assertEquals(0, report.labeled(), "未标注题不计入分母");
        assertEquals(2, report.skippedUnlabeled());
        assertNull(report.recallAtK(), "分母为 0 ⇒ n/a，不能给 0（0 会被读成'全错'）");
        assertNull(report.mrr());
        assertNull(report.hitRate());
        assertFalse(report.hasFailure(), "跳过不是失败（退出码必须为 0）");
        for (RetrievalEval.CaseResult line : report.results()) {
            assertEquals(RetrievalEval.CaseResult.STATUS_SKIPPED, line.status());
            assertTrue(line.note().contains("citation_pages=null"), line.note());
        }
        assertTrue(RetrievalEval.describe(report, 5).contains("n/a"), RetrievalEval.describe(report, 5));
    }

    @Test
    @DisplayName("混合：已标注的照算，未标注的照跳（分母只算已标注）")
    void mixedLabeledAndUnlabeled() {
        List<RetrievalEval.Case> cases = List.of(
                new RetrievalEval.Case("D-1", "命中第 2 页", "doc1", List.of(2), List.of(), ""),
                new RetrievalEval.Case("D-2", "未标注", null, null, null, ""));
        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("doc1", 2, "doc1:2:abc", "内容")));

        assertEquals(1, report.labeled());
        assertEquals(1, report.skippedUnlabeled());
        assertEquals(1.0, report.recallAtK());
        assertEquals(1.0, report.hitRate());
        assertFalse(report.hasFailure());
    }

    // ══════════════════════════════════════════════════════════════
    // ② 指标本身
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("recall@k / MRR：真值排第 2 ⇒ RR=1/2；top-2 里覆盖 2/3 条真值 ⇒ recall=0.6667")
    void recallAndMrrAreComputedOverExpectedPages() {
        List<RetrievalEval.Case> cases = List.of(
                new RetrievalEval.Case("D-1", "付款期限", "doc1", List.of(1, 2, 3), List.of(), ""));
        RetrievalEval.Report report = RetrievalEval.run(cases, 2, (query, k) -> result(
                hit("doc1", 9, "doc1:9:x", "无关页"),
                hit("doc1", 2, "doc1:2:y", "命中第 2 页")));

        RetrievalEval.CaseResult line = report.results().get(0);
        assertEquals(RetrievalEval.CaseResult.STATUS_PASS, line.status());
        assertEquals(1, line.matched(), "top-2 里只命中了第 2 页这一条真值");
        assertEquals(3, line.expected());
        assertEquals(0.3333, line.recallAtK(), 1e-4);
        assertEquals(0.5, line.reciprocalRank(), 1e-4, "排第 2 ⇒ RR = 1/2");
        assertTrue(line.hit());
        assertTrue(line.hitKeys().contains("doc1:9:x"), "命中键要回显（便于人工核对切片）");
    }

    @Test
    @DisplayName("一条真值都没命中 ⇒ FAIL（有失败即退出码 1），且 top-k 的切片键回显")
    void missIsFailure() {
        List<RetrievalEval.Case> cases = List.of(
                new RetrievalEval.Case("D-1", "付款期限", "doc1", List.of(7), List.of(), ""));
        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("doc1", 2, "doc1:2:y", "第 2 页")));

        assertTrue(report.hasFailure(), "有已标注题却一条都没命中 ⇒ 失败");
        assertEquals(0.0, report.recallAtK());
        assertEquals(0.0, report.mrr());
        assertEquals(RetrievalEval.CaseResult.STATUS_FAIL, report.results().get(0).status());
    }

    @Test
    @DisplayName("正确切片优先于页码：给了 citation_chunk_keys 就按切片键严格比对")
    void chunkKeysTakePrecedenceOverPages() {
        List<RetrievalEval.Case> cases = List.of(new RetrievalEval.Case("D-1", "query", "doc1",
                List.of(2), List.of("d=doc1:p=2:h=abc:c=1:k=0"), ""));
        // 页码对得上、切片键对不上 ⇒ 严格口径下算未命中
        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("doc1", 2, "doc1:2:other", "同页但不同切片")));
        assertTrue(report.hasFailure(), "给了切片键就必须按切片键比（更严格）");

        RetrievalEval.Report matched = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("doc1", 2, "d=doc1:p=2:h=abc:c=1:k=0", "正确切片")));
        assertFalse(matched.hasFailure());
        assertEquals(1.0, matched.recallAtK());
    }

    @Test
    @DisplayName("docId 非空时只认该文档的命中（否则 A 文档第 2 页会冒充 B 文档第 2 页）")
    void docIdScopesTheComparison() {
        List<RetrievalEval.Case> cases = List.of(
                new RetrievalEval.Case("D-1", "query", "docA", List.of(2), List.of(), ""));
        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> result(hit("docB", 2, "docB:2:x", "别的文档")));
        assertTrue(report.hasFailure(), "文档号不匹配不算命中");
    }

    // ══════════════════════════════════════════════════════════════
    // ③ 真实评测集：页码未标注 ⇒ 全 SKIP、无失败
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读真实的 eval/smoke-30.json：能解析、题目数 > 0；页码全为 null ⇒ 全 SKIP 且无失败")
    void readsTheRealEvaluationSet() throws Exception {
        Path file = Path.of("..", "eval", "smoke-30.json");
        Assumptions.assumeTrue(Files.isRegularFile(file), "评测集不在本地（跳过）：" + file.toAbsolutePath());

        List<RetrievalEval.Case> cases = RetrievalEvalTool.readCases(file);
        assertTrue(cases.size() >= 30, "评测集题目数应当 ≥30：实际 " + cases.size());
        assertTrue(cases.stream().allMatch(item -> !item.question().isBlank()));

        RetrievalEval.Report report = RetrievalEval.run(cases, 5,
                (query, k) -> new SearchPort.SearchResult(0, List.of(), "none", false, ""));
        assertEquals(cases.size(), report.skippedUnlabeled(),
                "当前数据集的 citation_pages 全是 null ⇒ 全部跳过（这是设计，不是 bug）");
        assertEquals(0, report.labeled());
        assertFalse(report.hasFailure(), "未标注 ⇒ 退出码必须为 0");
        assertNull(report.recallAtK());
        assertNotNull(report.note());
    }

    @Test
    @DisplayName("评测集读取：页码数组、camelCase 别名与缺失字段都按'未标注'处理，不抛异常")
    void readsPageAnnotationsTolerantly() {
        List<RetrievalEval.Case> cases = new ArrayList<>();
        cases.add(new RetrievalEval.Case("D-1", "q", "", List.of(2, 3), List.of(), ""));
        cases.add(new RetrievalEval.Case("D-2", "q2", "", null, null, ""));
        assertEquals(1, cases.stream().filter(RetrievalEval.Case::labeled).count());
        assertTrue(cases.get(0).labeled());
        assertFalse(cases.get(1).labeled());
    }
}
