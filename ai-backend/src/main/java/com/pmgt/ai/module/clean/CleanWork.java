package com.pmgt.ai.module.clean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗的<b>可变工作区</b>：规则就地在它上面改，改完由 {@link #toResult()} 固化成 {@link CleanResult}。
 *
 * <p>为什么要有这一层（而不是让每条规则各自返回新文本）：
 * <ol>
 *   <li>规则之间有<b>顺序依赖</b>：C5（表格标记）必须早于 C3/C4（空白与断行），否则表格会被"压缩空白 + 合并行"毁掉；
 *       C7（编码归一）又要早于所有按字符判据的规则。用一份共享工作区，顺序就是 {@link CleanRules#ALL} 的列表顺序，
 *       一眼可见、可测；</li>
 *   <li><b>留痕只在两处</b>：删行走 {@link #remove}、改文本走 {@link #normalize}，
 *       规则作者不可能"忘了记账"；</li>
 *   <li>结果对象（{@code CleanResult}）因此是<b>不可变</b>的，可以安全地跨线程传给切分与上报。</li>
 * </ol>
 *
 * <p>行级状态是"标记删除 + 首行合并"的语义：C4 把被合并掉的后续行标 {@code removed}，
 * 而合并后的文本写在<b>保留的那一行</b>上——这样 {@code removed[]} 里记的是"哪一行被合并进了谁"，
 * 复核时能原样还原。
 */
public final class CleanWork {

    /** 一行文本及其清洗状态。 */
    public static final class Line {
        private String text;
        private boolean removed;
        private String rule = "";
        private boolean table;
        /** C6 段级去重：非空表示"与哪一行重复"（只标记时才留着，删掉时同时 removed=true）。 */
        private String duplicateOf = "";
        private boolean normalized;

        Line(String text) {
            this.text = text == null ? "" : text;
        }

        public String text() {
            return text;
        }

        public void setText(String value) {
            this.text = value == null ? "" : value;
        }

        public boolean removed() {
            return removed;
        }

        public String rule() {
            return rule;
        }

        public boolean table() {
            return table;
        }

        public void markTable() {
            this.table = true;
        }

        public String duplicateOf() {
            return duplicateOf;
        }

        public void markDuplicate(String of) {
            this.duplicateOf = of == null ? "" : of;
        }

        public boolean normalized() {
            return normalized;
        }

        public void markNormalized() {
            this.normalized = true;
        }
    }

    /** 一页及其行状态。 */
    public static final class Page {
        private final int pageNo;
        private final String source;
        private final String before;
        private final boolean supported;
        private final List<Line> lines = new ArrayList<>();

        Page(int pageNo, String source, String before, boolean supported, List<Line> lines) {
            this.pageNo = pageNo;
            this.source = source == null ? "" : source;
            this.before = before == null ? "" : before;
            this.supported = supported;
            this.lines.addAll(lines);
        }

        public int pageNo() {
            return pageNo;
        }

        public String source() {
            return source;
        }

        public String before() {
            return before;
        }

        /** 该页来源是否在"清洗只作用于文本层与 OCR markdown 两路"的白名单里。 */
        public boolean supported() {
            return supported;
        }

        public List<Line> lines() {
            return lines;
        }

        /** 该页清洗后的文本（保留行按 \n 拼；连续空行在 C3 之后最多留 1 行）。 */
        public String after() {
            StringBuilder sb = new StringBuilder();
            for (Line line : lines) {
                if (line.removed()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.text());
            }
            return sb.toString();
        }

        /** 未删除的行下标（规则遍历用；顺序即页面顺序）。 */
        public List<Integer> liveIndexes() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).removed()) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    private final CleanOptions options;
    private final String docId;
    private final String filename;
    private final int cleanRev;
    private final List<Page> pages = new ArrayList<>();
    private final List<CleanResult.TraceItem> removed = new ArrayList<>();
    private final List<CleanResult.TraceItem> normalized = new ArrayList<>();
    private final Map<String, Integer> rulesFired = new LinkedHashMap<>();
    private final Map<Integer, List<CleanResult.TableRange>> tables = new LinkedHashMap<>();

    private Integer totalPages;
    private int duplicateParagraphs;
    private int tableBlocks;
    private int tableChars;

    public CleanWork(CleanOptions options, String docId, String filename, int cleanRev, CleanContext context) {
        this.options = options;
        this.docId = docId == null ? "" : docId;
        this.filename = filename == null ? "" : filename;
        this.cleanRev = cleanRev;
        for (CleanContext.PageInput input : context.pages()) {
            List<Line> lines = new ArrayList<>();
            for (String line : splitLines(input.safeText())) {
                lines.add(new Line(line));
            }
            pages.add(new Page(input.pageNo(), input.source(), input.safeText(),
                    CleanContext.supportedSource(input.source()), lines));
        }
    }

    /** 拆行：统一 {@code \r\n}/{@code \r} → {@code \n} 后按 {@code \n} 切（C3 的"换行统一"就是这一步）。 */
    static List<String> splitLines(String text) {
        String normalized = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            out.add(line);
        }
        return out;
    }

    public CleanOptions options() {
        return options;
    }

    public String docId() {
        return docId;
    }

    public String filename() {
        return filename;
    }

    public int cleanRev() {
        return cleanRev;
    }

    public List<Page> pages() {
        return pages;
    }

    public List<CleanResult.TraceItem> removed() {
        return removed;
    }

    public List<CleanResult.TraceItem> normalized() {
        return normalized;
    }

    public Map<String, Integer> rulesFired() {
        return rulesFired;
    }

    public Integer totalPages() {
        return totalPages;
    }

    public void setTotalPages(Integer value) {
        if (value != null && value > 0) {
            this.totalPages = value;
        }
    }

    public int duplicateParagraphs() {
        return duplicateParagraphs;
    }

    public void addDuplicateParagraphs(int count) {
        this.duplicateParagraphs += Math.max(0, count);
    }

    public int tableBlocks() {
        return tableBlocks;
    }

    public int tableChars() {
        return tableChars;
    }

    /** 标记一页的表格行区间（C5）；同一页多次调用会合并区间。 */
    public void markTable(Page page, int startLine, int endLine) {
        if (page == null || startLine < 0 || endLine < startLine) {
            return;
        }
        tables.computeIfAbsent(page.pageNo(), key -> new ArrayList<>())
                .add(new CleanResult.TableRange(startLine, Math.min(endLine, page.lines().size() - 1),
                        endLine - startLine + 1));
        tableBlocks++;
    }

    /** 表格块里"标记时"的字符数（估算表格占比，进 counters）。 */
    public void addTableChars(int chars) {
        this.tableChars += Math.max(0, chars);
    }

    public List<CleanResult.TableRange> tablesOf(Page page) {
        return tables.getOrDefault(page.pageNo(), List.of());
    }

    /** 该行是否落在表格区间内（C3/C4 据此跳过表格块）。 */
    public boolean inTable(Page page, int index) {
        for (CleanResult.TableRange range : tablesOf(page)) {
            if (index >= range.startLine() && index <= range.endLine()) {
                return true;
            }
        }
        return false;
    }

    /** 删一行（留痕 + 计规则命中）。 */
    public void remove(Page page, Line line, CleanRule rule) {
        if (line.removed()) {
            return;
        }
        line.removed = true;
        line.rule = rule.id();
        removed.add(new CleanResult.TraceItem(page.pageNo(), rule.id(), "removed", clip(line.text()), 1));
        fired(rule, 1);
    }

    /** 归一一行（改了但没删：全角→半角、空白压缩这类）——单列 {@code normalized[]}，不混进"删除"。 */
    public void normalize(Page page, Line line, CleanRule rule, String beforeText) {
        line.markNormalized();
        normalized.add(new CleanResult.TraceItem(page.pageNo(), rule.id(), "normalized",
                clip(beforeText), 1));
        fired(rule, 1);
    }

    /** 记一次规则命中（同一条规则命中多行时累加）。 */
    public void fired(CleanRule rule, int count) {
        if (count > 0) {
            rulesFired.merge(rule.id(), count, Integer::sum);
        }
    }

    /** 固化结果（不可变）。 */
    public CleanResult toResult() {
        List<CleanResult.CleanPage> resultPages = new ArrayList<>();
        int beforeChars = 0;
        int afterChars = 0;
        int removedLines = 0;
        int pagesAllRemoved = 0;
        int pagesCleaned = 0;
        int pagesSkipped = 0;
        for (Page page : pages) {
            String after = page.after();
            beforeChars += page.before().length();
            afterChars += after.length();
            removedLines += page.lines().size() - page.liveIndexes().size();
            if (page.supported()) {
                if (after.isBlank() && !page.before().isBlank()) {
                    pagesAllRemoved++;
                }
                pagesCleaned++;
            } else {
                pagesSkipped++;
            }
            resultPages.add(new CleanResult.CleanPage(page.pageNo(), page.source(), page.before(), after,
                    tablesOfAfter(page)));
        }
        int removedChars = Math.max(0, beforeChars - afterChars);
        double ratio = beforeChars <= 0 ? 0.0 : Math.round(removedChars * 10000.0 / beforeChars) / 10000.0;
        CleanResult.Stats stats = new CleanResult.Stats(pages.size(), pagesCleaned, pagesSkipped,
                beforeChars, afterChars, removedChars, removedLines, pagesAllRemoved,
                duplicateParagraphs, tableBlocks, tableChars, replacementChars, totalPages, ratio);
        return new CleanResult(docId, cleanRev, options.enabled(), null, resultPages,
                List.copyOf(removed), List.copyOf(normalized), Map.copyOf(rulesFired), stats);
    }

    /**
     * 把表格区间换算成<b>清洗后文本</b>的行号（切分阶段是按 {@code after().split("\n")} 找表格的）。
     *
     * <p>为什么必须换算：规则是按"工作区行号"标记的，而 C3 会删掉多余空行——
     * 两套行号一旦错位，切分就会把表格的前几行当正文（"表头没了、表体还在"）。
     */
    private List<CleanResult.TableRange> tablesOfAfter(Page page) {
        List<CleanResult.TableRange> raw = tablesOf(page);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Integer> live = page.liveIndexes();
        List<CleanResult.TableRange> out = new ArrayList<>();
        int start = -1;
        int rows = 0;
        for (int i = 0; i < live.size(); i++) {
            boolean table = page.lines().get(live.get(i)).table();
            if (table) {
                if (start < 0) {
                    start = i;
                    rows = 0;
                }
                rows++;
                continue;
            }
            if (start >= 0) {
                out.add(new CleanResult.TableRange(start, i - 1, rows));
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new CleanResult.TableRange(start, live.size() - 1, rows));
        }
        return out;
    }

    private int replacementChars;

    /** C7 记下的替代字符 {@code \uFFFD} 数量（不静默删，只统计）。 */
    public void addReplacementChars(int count) {
        this.replacementChars += Math.max(0, count);
    }

    public int replacementChars() {
        return replacementChars;
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }
}
