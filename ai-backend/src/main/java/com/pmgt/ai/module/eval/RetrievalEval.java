package com.pmgt.ai.module.eval;

import com.pmgt.ai.module.retrieval.SearchPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>检索级</b>评测（recall@k / MRR / 命中率）——补 {@code scripts/eval-ai.mjs} 之外的那把尺子。
 * <h2>为什么要有它（与 {@code eval-ai.mjs} 的分工）</h2>
 * 既有的 {@code scripts/eval-ai.mjs} 评的是<b>端到端问答</b>（数字一致率、拒答、工具选择、引用覆盖率），
 * 它拿不到"检索这一层到底排对没有"：答案对了可能是模型自己推出来的，检索对了才是 RAG 的地基。
 * 本类只回答检索层的问题：<b>给定 query 与"已知正确切片/页码"，top-k 里有没有它们、排在第几</b>。
 *
 * <h2>{@code citation_pages=null} 时必须 SKIP 而不是失败</h2>
 * 文档题的页码是<b>人工标注</b>（{@code eval/README.md} §1 的硬边界：不许瞎标），
 * 当前数据集里全部是 {@code null}。没有标注就没有真值，没有真值就<b>不能算命中率</b>——
 * 把它当成 FAIL 会让"未标注"看起来像"检索坏了"，当成 PASS 又是在自欺。
 * 所以：<b>该题记 {@code SKIPPED_UNLABELED}，退出码仍为 0</b>，且所有指标的分母<b>只统计已标注的题</b>；
 * 本题集全未标注时指标显示 {@code null}（= {@code n/a}），而不是 0（0 会被误读成"全错"）。
 *
 * <h2>匹配口径（写清楚，避免"看起来算过了"）</h2>
 * <ul>
 *   <li><b>正确切片</b>优先：给了 {@code expectedChunkKeys} 就按切片键精确比对（最严格，含版本段）；</li>
 *   <li>否则按<b>页码</b>比对（{@code expectedPages}）：同一文档内命中该页即算命中该条真值；
 *       一条真值可能对应多个切片（重叠窗口会产出多片同页），所以按"真值集合"去重统计；</li>
 *   <li>{@code docId} 非空时只认该文档的命中（否则 A 文档的第 3 页会命中 B 文档的第 3 页）。</li>
 * </ul>
 *
 * <h2>指标定义</h2>
 * <ul>
 *   <li>{@code recall@k} = 每题「top-k 覆盖到的真值数 / 该题真值总数」的<b>宏平均</b>（未标注题不计入）；</li>
 *   <li>{@code MRR} = 每题「第一个命中真值的排名倒数」的宏平均（一条都没命中记 0）；</li>
 *   <li>{@code hit_rate} = 「top-k 里至少有 1 条真值」的题数 / 已标注题数；</li>
 *   <li>{@code skipped_unlabeled} = 因 {@code citation_pages=null} 跳过的题数（<b>不是失败</b>）。</li>
 * </ul>
 *
 * <p>本类是<b>纯计算</b>：不读文件、不发请求、不写盘。检索动作由调用方以
 * {@link Retriever}（函数式接口）注入——单测里塞假结果、CLI 里塞真 {@code RetrievalService}。
 */
public final class RetrievalEval {

    private RetrievalEval() {
    }

    /** 一道题（由 {@code eval/smoke-30.json} 的 case 或测试数据映射而来）。 */
    public record Case(String id,
                       String question,
                       String docId,
                       List<Integer> expectedPages,
                       List<String> expectedChunkKeys,
                       String note) {

        public Case {
            expectedPages = expectedPages == null ? List.of() : List.copyOf(expectedPages);
            expectedChunkKeys = expectedChunkKeys == null ? List.of() : List.copyOf(expectedChunkKeys);
        }

        /** 有没有人工标注（页码或切片键任一即可；都为 null/空 = 未标注 ⇒ 跳过）。 */
        public boolean labeled() {
            return !expectedChunkKeys.isEmpty() || !expectedPages.isEmpty();
        }
    }

    /** 单题结果：{@code skipped} 为真时其余指标字段无意义（分母不计它）。 */
    public record CaseResult(String id,
                             String status,
                             int found,
                             int expected,
                             int matched,
                             double recallAtK,
                             double reciprocalRank,
                             boolean hit,
                             List<String> hitKeys,
                             String note) {

        public static final String STATUS_PASS = "PASS";
        public static final String STATUS_FAIL = "FAIL";
        public static final String STATUS_SKIPPED = "SKIPPED_UNLABELED";
    }

    /** 汇总指标：{@code null} 表示"分母为 0，算不出来"（不是 0 分）。 */
    public record Report(int cases,
                         int labeled,
                         int skippedUnlabeled,
                         Double recallAtK,
                         Double mrr,
                         Double hitRate,
                         List<CaseResult> results,
                         String note) {

        /** 有没有失败（用于退出码）：<b>跳过不算失败</b>。 */
        public boolean hasFailure() {
            for (CaseResult result : results) {
                if (CaseResult.STATUS_FAIL.equals(result.status())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 检索动作：query + topK → 检索结果（生产里就是 {@code RetrievalService.searchInScope}）。 */
    @FunctionalInterface
    public interface Retriever {
        SearchPort.SearchResult search(String query, int topK);
    }

    /**
     * 跑一轮检索级评测。
     *
     * @param cases    题目（{@code expected.citation_pages=null} 的题会记 SKIP）
     * @param topK     看前几条（recall@k / MRR 的 k）
     * @param retriever 检索实现
     */
    public static Report run(List<Case> cases, int topK, Retriever retriever) {
        int k = Math.max(1, topK);
        List<CaseResult> results = new ArrayList<>();
        int labeled = 0;
        int skipped = 0;
        double recallSum = 0;
        double rrSum = 0;
        int hits = 0;
        for (Case item : cases) {
            if (item == null) {
                continue;
            }
            if (!item.labeled()) {
                skipped++;
                results.add(new CaseResult(item.id(), CaseResult.STATUS_SKIPPED, 0, 0, 0, 0.0, 0.0, false,
                        List.of(), "citation_pages=null（待人工标注）：跳过而不是失败——没有真值就不算命中率"));
                continue;
            }
            SearchPort.SearchResult search = retriever == null ? null : retriever.search(item.question(), k);
            List<SearchPort.Hit> hitsList = search == null || search.hits() == null ? List.of() : search.hits();
            List<SearchPort.Hit> scoped = new ArrayList<>();
            for (SearchPort.Hit hit : hitsList) {
                if (item.docId() == null || item.docId().isBlank() || item.docId().equals(hit.docId())) {
                    scoped.add(hit);
                }
            }
            Set<String> matchedTruth = new LinkedHashSet<>();
            int firstRank = -1;
            List<String> hitKeys = new ArrayList<>();
            for (int i = 0; i < scoped.size(); i++) {
                SearchPort.Hit hit = scoped.get(i);
                if (hit.chunkKey() != null && !hit.chunkKey().isBlank()) {
                    hitKeys.add(hit.chunkKey());
                }
                String truth = truthOf(item, hit);
                if (truth != null) {
                    matchedTruth.add(truth);
                    if (firstRank < 0) {
                        firstRank = i + 1;
                    }
                }
            }
            int expected = item.expectedChunkKeys().isEmpty()
                    ? item.expectedPages().size() : item.expectedChunkKeys().size();
            double recall = expected == 0 ? 0.0 : (double) matchedTruth.size() / expected;
            double reciprocal = firstRank < 0 ? 0.0 : 1.0 / firstRank;
            boolean hit = firstRank > 0;
            labeled++;
            recallSum += recall;
            rrSum += reciprocal;
            if (hit) {
                hits++;
            }
            results.add(new CaseResult(item.id(), hit ? CaseResult.STATUS_PASS : CaseResult.STATUS_FAIL,
                    scoped.size(), expected, matchedTruth.size(), round(recall), round(reciprocal), hit,
                    hitKeys, noteOf(item, hit, matchedTruth.size(), expected)));
        }
        Double recall = labeled == 0 ? null : round(recallSum / labeled);
        Double mrr = labeled == 0 ? null : round(rrSum / labeled);
        Double hitRate = labeled == 0 ? null : round((double) hits / labeled);
        String note = labeled == 0
                ? "没有已标注的题（citation_pages 全为 null）：recall@k / MRR / 命中率都是 n/a——"
                        + "这不是 0 分，是'尺子还没刻度'。补齐页码后重跑即得指标。"
                : "已标注 " + labeled + " 题（跳过未标注 " + skipped + " 题）；匹配口径见 RetrievalEval 类注释";
        return new Report(results.size(), labeled, skipped, recall, mrr, hitRate, List.copyOf(results), note);
    }

    /**
     * 这条命中命中哪一条真值（返回真值的标识）；没命中返回 {@code null}。
     *
     * <p>切片键优先：给了 {@code expectedChunkKeys} 就只认键（严格）；
     * 只给页码时返回"第 N 页"作为标识（同一页的多个切片只算一条真值）。
     */
    static String truthOf(Case item, SearchPort.Hit hit) {
        if (!item.expectedChunkKeys().isEmpty()) {
            return item.expectedChunkKeys().contains(hit.chunkKey()) ? hit.chunkKey() : null;
        }
        for (Integer page : item.expectedPages()) {
            if (page != null && page == hit.pageNo()) {
                return "page:" + page;
            }
        }
        return null;
    }

    private static String noteOf(Case item, boolean hit, int matched, int expected) {
        if (hit) {
            return "命中 " + matched + "/" + expected + " 条真值";
        }
        return "top-k 里没有真值（" + expected + " 条待命中）：核对切片规则/重建索引/页码标注是否过时";
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 单行汇总（CLI 与测试共用一处措辞，避免"报告与实现两套说法"）。 */
    public static String describe(Report report, int topK) {
        return "检索级评测：题 " + report.cases() + "（已标注 " + report.labeled()
                + "，跳过未标注 " + report.skippedUnlabeled() + "）"
                + "；recall@" + topK + "=" + fmt(report.recallAtK())
                + "，MRR=" + fmt(report.mrr())
                + "，命中率=" + fmt(report.hitRate());
    }

    /** {@code null} 打印成 {@code n/a}：分母为 0 与"全错"必须一眼可分。 */
    public static String fmt(Double value) {
        return value == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
