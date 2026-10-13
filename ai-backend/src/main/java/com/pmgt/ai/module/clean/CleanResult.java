package com.pmgt.ai.module.clean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次清洗的结果（设计 §3.3.1 的 {@code CleanResult}）。
 *
 * <p>三条不可省的字段：
 * <ol>
 *   <li>{@code pages[].after} —— 交给切分的清洗后文本；{@code pages[].before} 保留原文
 *       （<b>前后对照</b>是清洗阶段唯一能自证"删对了没有"的东西）；</li>
 *   <li>{@code removed[]} —— "删了什么"的逐条明细（页码 + 规则 + 原行 + 次数）；
 *       设计里明说：命中行必须留痕供人工复核，否则误删"每页都出现的条款标题"就永远发现不了；</li>
 *   <li>{@code stats} + {@code rulesFired} —— 进阶段 counters 与质量口径（设计 §3.3.4）。</li>
 * </ol>
 *
 * <p><b>清洗关闭时</b>用 {@link #disabled} 构造：{@code enabled=false}、{@code pages[].after == before}
 * （逐字相同）、{@code removed} 与 {@code rulesFired} 全空、统计全 0——
 * "没做的事就是没做"，不给一个"看起来洗过"的结果。
 */
public record CleanResult(String docId,
                          int cleanRev,
                          boolean enabled,
                          String reason,
                          List<CleanPage> pages,
                          List<TraceItem> removed,
                          List<TraceItem> normalized,
                          Map<String, Integer> rulesFired,
                          Stats stats) {

    /** 清洗后的一页（{@code tables} 是 C5 标记出来的表格行区间，供切分整块处理）。 */
    public record CleanPage(int pageNo,
                            String source,
                            String before,
                            String after,
                            List<TableRange> tables) {

        public String safeAfter() {
            return after == null ? "" : after;
        }

        public boolean empty() {
            return safeAfter().isBlank();
        }
    }

    /** 表格块的行区间：{@code startLine}~{@code endLine}（含两端，0-based）。 */
    public record TableRange(int startLine, int endLine, int rows) {
    }

    /**
     * 一行痕迹。
     *
     * @param kind    {@code removed}（删了）| {@code normalized}（改了但没删，如全角→半角）
     * @param count   该行（归一化后）在本次命中的次数
     */
    public record TraceItem(int pageNo, String rule, String kind, String text, int count) {
    }

    /** 清洗统计（进阶段 counters 与 {@code clean_diff} 产物；设计 §3.3.4 的口径）。 */
    public record Stats(int pages,
                        int pagesCleaned,
                        int pagesSkipped,
                        int beforeChars,
                        int afterChars,
                        int removedChars,
                        int removedLines,
                        int pagesAllRemoved,
                        int duplicateParagraphs,
                        int tableBlocks,
                        int tableChars,
                        int replacementChars,
                        Integer totalPages,
                        double removedCharsRatio) {
    }

    /** 清洗关闭（或整份不可清洗）时的结果：文本逐字不变，统计全 0。 */
    public static CleanResult disabled(String docId, int cleanRev, String reason, List<CleanPage> rawPages) {
        int chars = 0;
        for (CleanPage page : rawPages) {
            chars += page.safeAfter().length();
        }
        Stats stats = new Stats(rawPages.size(), 0, rawPages.size(), chars, chars, 0, 0, 0, 0, 0, 0, 0,
                null, 0.0);
        return new CleanResult(docId, cleanRev, false, reason, List.copyOf(rawPages), List.of(), List.of(),
                Map.of(), stats);
    }

    /** 清洗后全文（按页拼接，切分与"清洗前后对照"都用它）。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (CleanPage page : pages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(page.safeAfter());
        }
        return sb.toString();
    }

    public String pageText(int pageNo) {
        for (CleanPage page : pages) {
            if (page.pageNo() == pageNo) {
                return page.safeAfter();
            }
        }
        return "";
    }

    /** 供切分阶段查"这一页的表格行区间"。 */
    public List<TableRange> tablesOf(int pageNo) {
        for (CleanPage page : pages) {
            if (page.pageNo() == pageNo) {
                return page.tables() == null ? List.of() : page.tables();
            }
        }
        return List.of();
    }

    /** 规则命中明细（{@code rule → 命中次数}），保持规则顺序，便于人读。 */
    public Map<String, Integer> firedByRule() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CleanRule rule : CleanRules.ALL) {
            Integer count = rulesFired.get(rule.id());
            if (count != null && count > 0) {
                out.put(rule.id(), count);
            }
        }
        return out;
    }

    /** 被删的行样本（默认最多 20 条，产物里给样本、全文留 {@code work/clean/<docId>.json}）。 */
    public List<TraceItem> removedSamples(int limit) {
        List<TraceItem> out = new ArrayList<>();
        for (TraceItem item : removed) {
            if (out.size() >= limit) {
                break;
            }
            out.add(item);
        }
        return out;
    }
}
