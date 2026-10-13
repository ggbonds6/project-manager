package com.pmgt.ai.module.clean;

import com.pmgt.ai.common.text.TextStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设计 §3.3.2 的八条规则（C1~C8）——一条一个类，<b>独立可开关、独立可测</b>。
 *
 * <p>应用顺序就是 {@link #ALL} 的列表顺序，顺序本身就是设计的一部分：
 * <pre>
 *   C7 编码归一 → C5 表格标记 → C1 页眉页脚 → C2 页码行 → C8 水印 → C6 段级去重 → C4 断行合并 → C3 空白归一
 * </pre>
 * 理由：① 编码先归一，后面所有"按字符比较"的判据才一致；② 表格必须先被标记，
 * 否则 C3/C4 会把表内文本压缩/合并掉；③ 删除类规则（C1/C2/C8）先去行，C6 去重才不会
 * 把"页眉"当成重复段；④ C4 合并会改变行数，所以 C3（按行 strip/压缩）放最后。
 *
 * <h2>没做的部分（如实登记，别在文档里写成"已支持"）</h2>
 * <ul>
 *   <li><b>C5 的 bbox 匹配未做</b>：{@code StoredDoc.BlockRef} 只有 {@code label + bbox}、
 *       没有行号/行文本，无法把"表格块"映射到具体行 ⇒ 当前只认 markdown 竖线结构（{@code TextStructure.pipeTableRanges}），
 *       没有竖线的表格按正文处理（宁可少标记，也不把正文误判成表格）；</li>
 *   <li><b>C8 只做词表（②）</b>：{@code ①非页首/页末的高频重复行}未做——它可能删掉"每页都出现的合规声明"，
 *       而这类文本恰恰是密级/合规线索（设计把它列为 P2 且默认关）；</li>
 *   <li><b>C1 的白名单正则未提供</b>：命中的行一律进 {@code removed_lines} 供人工复核，
 *       需要白名单时可先用 C8 的正则列表做"整行剔除"。</li>
 * </ul>
 */
public final class CleanRules {

    /** 全部规则，顺序即应用顺序（见类注释）。 */
    public static final List<CleanRule> ALL = List.of(
            new C7Encoding(),
            new C5Table(),
            new C1HeaderFooter(),
            new C2PageNumber(),
            new C8Watermark(),
            new C6ParagraphDedup(),
            new C4LineMerge(),
            new C3Whitespace());

    private CleanRules() {
    }

    public static CleanRule byId(String id) {
        for (CleanRule rule : ALL) {
            if (rule.id().equals(id)) {
                return rule;
            }
        }
        return null;
    }

    /** 只对"参与清洗的页"生效（来源不在两路白名单里的页原样传下去）。 */
    static List<CleanWork.Page> cleanablePages(CleanWork work) {
        List<CleanWork.Page> out = new ArrayList<>();
        for (CleanWork.Page page : work.pages()) {
            if (page.supported()) {
                out.add(page);
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // C1 跨页重复行检测（页眉/页脚）
    // ══════════════════════════════════════════════════════════════

    /**
     * 同一归一化行在 ≥{@code header-footer-ratio} 的页出现，且位于<b>页首 2 行或页末 2 行</b> ⇒ 判为页眉/页脚整行剔除。
     *
     * <p>三条防误删：① 短行（&lt; {@code header-footer-min-chars} 字）不参与；
     * ② 只处理页首/页末 2 行（正文中间的重复句子不动）；③ 被删的行全部写进 {@code removed_lines}。
     */
    public static final class C1HeaderFooter implements CleanRule {

        /** 页首/页末参与判定的行数（设计给的判据就是"页首 2 行或页末 2 行"）。 */
        static final int EDGE_LINES = 2;

        @Override
        public String id() {
            return "c1";
        }

        @Override
        public String label() {
            return "跨页重复行（页眉/页脚）剔除";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            // ① 证据取自**全部页**（跨页规则需要整份文档的视角）；
            //    删除只落在"参与清洗的页"上——未知来源的页原样传下去（见 CleanContext 的白名单）。
            List<CleanWork.Page> candidates = new ArrayList<>();
            for (CleanWork.Page page : work.pages()) {
                if (!page.before().isBlank()) {
                    candidates.add(page);
                }
            }
            if (candidates.size() < 2) {
                return;   // 单页文档没有"跨页"证据，不做任何判断
            }
            int threshold = Math.max(2, (int) Math.ceil(work.options().headerFooterRatio() * candidates.size()));

            // ② 统计"出现的页数"（同一页重复出现只算一页）
            Map<String, Set<Integer>> pagesOf = new LinkedHashMap<>();
            for (CleanWork.Page page : candidates) {
                for (int index : edgeIndexes(page)) {
                    String key = TextStructure.repeatKey(page.lines().get(index).text());
                    if (key.length() < work.options().headerFooterMinChars() || work.inTable(page, index)) {
                        continue;
                    }
                    pagesOf.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(page.pageNo());
                }
            }
            // ③ 达到阈值的 key 才是页眉/页脚
            Set<String> repeated = new LinkedHashSet<>();
            for (Map.Entry<String, Set<Integer>> entry : pagesOf.entrySet()) {
                if (entry.getValue().size() >= threshold) {
                    repeated.add(entry.getKey());
                }
            }
            if (repeated.isEmpty()) {
                return;
            }
            // ④ 只删"页首/页末"位置上命中这些 key 的行，且只删参与清洗的页
            for (CleanWork.Page page : cleanablePages(work)) {
                for (int index : edgeIndexes(page)) {
                    CleanWork.Line line = page.lines().get(index);
                    String key = TextStructure.repeatKey(line.text());
                    if (repeated.contains(key) && key.length() >= work.options().headerFooterMinChars()
                            && !work.inTable(page, index)) {
                        work.remove(page, line, this);
                    }
                }
            }
        }

        private static List<Integer> edgeIndexes(CleanWork.Page page) {
            List<Integer> out = new ArrayList<>();
            Set<Integer> seen = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(EDGE_LINES, page.lines().size()); i++) {
                if (seen.add(i)) {
                    out.add(i);
                }
            }
            for (int i = Math.max(0, page.lines().size() - EDGE_LINES); i < page.lines().size(); i++) {
                if (seen.add(i)) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C2 纯页码行剔除
    // ══════════════════════════════════════════════════════════════

    /**
     * 纯页码行剔除；<b>先把"共 Y 页"抽成页级元数据（{@code total_pages}）再删行</b>——
     * 页数与完整性是有用信息，删掉就再也拿不回来了（设计 §3.3.2 C2 的风险对策）。
     */
    public static final class C2PageNumber implements CleanRule {

        private static final Pattern BARE_NUMBER = Pattern.compile("^[-—–\\s]*\\d{1,4}[-—–\\s]*$");
        private static final Pattern CN_PAGE = Pattern.compile("^第\\s*(\\d+)\\s*页(\\s*共\\s*(\\d+)\\s*页)?$");
        private static final Pattern SLASH = Pattern.compile("^(\\d+)\\s*/\\s*(\\d+)$");
        private static final Pattern EN_PAGE =
                Pattern.compile("^page\\s+(\\d+)(\\s+of\\s+(\\d+))?$", Pattern.CASE_INSENSITIVE);

        @Override
        public String id() {
            return "c2";
        }

        @Override
        public String label() {
            return "纯页码行剔除（页数先抽成元数据）";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            for (CleanWork.Page page : cleanablePages(work)) {
                List<CleanWork.Line> lines = page.lines();
                for (int i = 0; i < lines.size(); i++) {
                    if (work.inTable(page, i)) {
                        continue;
                    }
                    String text = lines.get(i).text().strip();
                    Integer total = totalPagesOf(text);
                    if (total != null) {
                        work.setTotalPages(total);
                    }
                    if (isPageNumber(text)) {
                        work.remove(page, lines.get(i), this);
                    }
                }
            }
        }

        static boolean isPageNumber(String text) {
            String value = text == null ? "" : text.strip();
            if (value.isEmpty()) {
                return false;
            }
            return BARE_NUMBER.matcher(value).matches()
                    || CN_PAGE.matcher(value).matches()
                    || SLASH.matcher(value).matches()
                    || EN_PAGE.matcher(value).matches();
        }

        /** 从 {@code 第 X 页 共 Y 页} / {@code X/Y} / {@code Page X of Y} 里抽总页数（抽不到返回 null）。 */
        static Integer totalPagesOf(String text) {
            String value = text == null ? "" : text.strip();
            Matcher cn = CN_PAGE.matcher(value);
            if (cn.matches() && cn.group(3) != null) {
                return Integer.parseInt(cn.group(3));
            }
            Matcher slash = SLASH.matcher(value);
            if (slash.matches()) {
                return Integer.parseInt(slash.group(2));
            }
            Matcher en = EN_PAGE.matcher(value);
            if (en.matches() && en.group(3) != null) {
                return Integer.parseInt(en.group(3));
            }
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C3 空白与不可见字符归一
    // ══════════════════════════════════════════════════════════════

    /**
     * 空白与不可见字符归一：换行统一（构造期已做）、删除零宽字符、全角空格→半角、
     * 行首尾 {@code strip}、连续 ≥3 空格压缩、连续空行压到 1 行。
     *
     * <p><b>表格块内不压缩连续空格</b>（表格用空格对齐时压缩会破坏视觉对齐）——
     * 表格行只做行首尾 strip。
     */
    public static final class C3Whitespace implements CleanRule {

        private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u2060]");
        private static final Pattern MANY_SPACES = Pattern.compile("[ \\t\\x0B\\f]{3,}");

        @Override
        public String id() {
            return "c3";
        }

        @Override
        public String label() {
            return "空白与不可见字符归一（表格块不压缩）";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            for (CleanWork.Page page : cleanablePages(work)) {
                List<CleanWork.Line> lines = page.lines();
                boolean previousBlank = false;
                for (int i = 0; i < lines.size(); i++) {
                    CleanWork.Line line = lines.get(i);
                    if (line.removed()) {
                        continue;
                    }
                    String original = line.text();
                    boolean table = work.inTable(page, i);
                    String text = ZERO_WIDTH.matcher(original).replaceAll("");
                    text = text.replace('\u3000', ' ');
                    text = text.strip();
                    if (!table) {
                        text = MANY_SPACES.matcher(text).replaceAll(" ");
                    }
                    // 连续空行压到 1 行（空行是段落分隔，保留一个就够）
                    if (text.isEmpty() && previousBlank) {
                        work.remove(page, line, this);
                        continue;
                    }
                    if (!text.equals(original)) {
                        line.setText(text);
                        work.normalize(page, line, this, original);
                    }
                    previousBlank = text.isEmpty();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C4 断行合并 / 连字符修复
    // ══════════════════════════════════════════════════════════════

    /**
     * 断行合并：① 上一行行尾无标点且当前行不是条款/列表/标题起始 ⇒ 合并；
     * ② 上一行以 {@code -}/{@code ‐} 结尾且当前行首为小写字母 ⇒ 去掉连字符再合并。
     *
     * <p>合并结果写在<b>保留的那一行</b>上，被合并的行标 {@code removed} 并记进 {@code removed_lines}
     * （复核时能看到"哪一行被并进了谁"）。中英混排时按边界字符决定是否补空格：
     * 两侧都是 ASCII 字母数字才补空格，中文相邻直接相接。
     */
    public static final class C4LineMerge implements CleanRule {

        private static final String HYPHENS = "-\u2010\u2011";

        @Override
        public String id() {
            return "c4";
        }

        @Override
        public String label() {
            return "断行合并与连字符修复";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            for (CleanWork.Page page : cleanablePages(work)) {
                List<CleanWork.Line> lines = page.lines();
                for (int i = 1; i < lines.size(); i++) {
                    CleanWork.Line previous = previousLive(page, i);
                    CleanWork.Line current = lines.get(i);
                    if (previous == null || current.removed()) {
                        continue;
                    }
                    int previousIndex = lines.indexOf(previous);
                    if (previous.text().isBlank() || current.text().isBlank()) {
                        continue;   // 空行是段落边界，不合并
                    }
                    if (work.inTable(page, previousIndex) || work.inTable(page, i)) {
                        continue;   // 表格块内不合并（设计 §3.3.2 C5 与 C3 的同一条纪律）
                    }
                    if (TextStructure.endsWithSentencePunct(previous.text())) {
                        continue;
                    }
                    if (TextStructure.forcesLineBreak(current.text())) {
                        continue;
                    }
                    boolean hyphen = endsWithHyphen(previous.text()) && startsWithLowerLetter(current.text());
                    String before = previous.text();
                    String merged;
                    if (hyphen) {
                        // 断词修复：**直接相接**（中间加空格会把一个单词拆成两个）
                        merged = before.substring(0, before.length() - 1) + current.text().strip();
                    } else {
                        merged = join(before, current.text());
                    }
                    previous.setText(merged);
                    work.remove(page, current, this);
                    work.normalize(page, previous, this, before);
                }
            }
        }

        private static CleanWork.Line previousLive(CleanWork.Page page, int index) {
            for (int i = index - 1; i >= 0; i--) {
                CleanWork.Line line = page.lines().get(i);
                if (!line.removed()) {
                    return line;
                }
            }
            return null;
        }

        private static boolean endsWithHyphen(String text) {
            String value = text.strip();
            return !value.isEmpty() && HYPHENS.indexOf(value.charAt(value.length() - 1)) >= 0;
        }

        private static boolean startsWithLowerLetter(String text) {
            String value = text.strip();
            return !value.isEmpty() && Character.isLowerCase(value.charAt(0)) && value.charAt(0) < 128;
        }

        /** 合并两行：两侧都是 ASCII 字母/数字时补一个空格，其余（中文、标点）直接相接。 */
        static String join(String left, String right) {
            if (left.isEmpty()) {
                return right;
            }
            char a = left.charAt(left.length() - 1);
            char b = right.charAt(0);
            boolean asciiPair = a < 128 && b < 128 && (Character.isLetterOrDigit(a) || a == ',' || a == '.')
                    && (Character.isLetterOrDigit(b));
            return asciiPair ? left + " " + right : left + right;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C5 表格与正文分离（只标记，不改写文本）
    // ══════════════════════════════════════════════════════════════

    /**
     * 标记表格行区间：<b>本期只标记、不删内容</b>（设计 §3.3.2 C5 的取舍）——
     * 标记结果交给切分阶段整块处理，同时让 C3/C4 跳过表格块。
     */
    public static final class C5Table implements CleanRule {

        @Override
        public String id() {
            return "c5";
        }

        @Override
        public String label() {
            return "表格与正文分离（整块保留、只标记）";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            for (CleanWork.Page page : cleanablePages(work)) {
                List<String> texts = new ArrayList<>();
                for (CleanWork.Line line : page.lines()) {
                    texts.add(line.text());
                }
                for (int[] range : TextStructure.pipeTableRanges(texts)) {
                    int chars = 0;
                    for (int i = range[0]; i <= range[1]; i++) {
                        page.lines().get(i).markTable();
                        chars += page.lines().get(i).text().length();
                    }
                    work.markTable(page, range[0], range[1]);
                    work.addTableChars(chars);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C6 段级去重
    // ══════════════════════════════════════════════════════════════

    /**
     * 归一化后<b>完全相同</b>且长度 ≥ {@value #MIN_PARAGRAPH_CHARS} 字的段落，同一文档内出现 ≥2 次：
     * 保留首次、后续标记 {@code duplicate_of}（<b>默认只标记不删</b>；
     * {@code ai.clean.dedup-drop=true} 才真的删）。
     */
    public static final class C6ParagraphDedup implements CleanRule {

        /** 参与去重的最短段落长度（设计给的口径是 ≥20 字）。 */
        static final int MIN_PARAGRAPH_CHARS = 20;

        @Override
        public String id() {
            return "c6";
        }

        @Override
        public String label() {
            return "段级去重（默认只标记）";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            Map<String, String> firstSeen = new LinkedHashMap<>();
            for (CleanWork.Page page : cleanablePages(work)) {
                for (List<Integer> paragraph : Paragraphs.of(work, page)) {
                    StringBuilder sb = new StringBuilder();
                    for (int index : paragraph) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(page.lines().get(index).text().strip());
                    }
                    String text = sb.toString();
                    String key = text.replaceAll("\\s+", "");
                    if (key.length() < MIN_PARAGRAPH_CHARS) {
                        continue;
                    }
                    String first = firstSeen.get(key);
                    if (first == null) {
                        firstSeen.put(key, page.pageNo() + ":" + paragraph.get(0));
                        continue;
                    }
                    work.addDuplicateParagraphs(1);
                    for (int index : paragraph) {
                        CleanWork.Line line = page.lines().get(index);
                        line.markDuplicate(first);
                        if (work.options().dedupDrop()) {
                            work.remove(page, line, this);
                        }
                    }
                    work.fired(this, paragraph.size());
                }
            }
        }
    }

    /** 段落划分：连续非空行成段；空行即边界（与切分的段落口径一致）。 */
    static final class Paragraphs {

        static List<List<Integer>> of(CleanWork work, CleanWork.Page page) {
            List<List<Integer>> out = new ArrayList<>();
            List<Integer> current = new ArrayList<>();
            for (int i = 0; i < page.lines().size(); i++) {
                CleanWork.Line line = page.lines().get(i);
                if (line.removed() || line.text().isBlank()) {
                    if (!current.isEmpty()) {
                        out.add(List.copyOf(current));
                        current.clear();
                    }
                    continue;
                }
                current.add(i);
            }
            if (!current.isEmpty()) {
                out.add(List.copyOf(current));
            }
            return out;
        }

        private Paragraphs() {
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C7 编码归一
    // ══════════════════════════════════════════════════════════════

    /**
     * 编码归一：全角 ASCII 区（{@code U+FF01~U+FF5E}）→ 半角，<b>但保留下面的"句读类"全角标点</b>；
     * 统计替代字符 {@code \uFFFD} 的数量（<b>只统计、不静默删</b>）。
     *
     * <p>保留集 = 设计 §3.3.2 C7 ② 明写的 {@code ，。；：？！（）}：
     * <ul>
     *   <li>「。』（U+3002）本来就不在全角 ASCII 区，天然保留；</li>
     *   <li>{@code ，；：？！（）} 在区间内，按设计<b>保留</b>——正文可读性与"原文即所见"优先，
     *       检索时的全角/半角折叠由 {@code RetrievalService.fold} 在打分副本上做（既有行为，不改）；</li>
     *   <li>其余（字母数字 + {@code ．％／－} 等机器可读符号）折成半角——金额 {@code ７，９８２，３００．００}
     *       因此变成 {@code 7，982，300.00}（数字与小数点半角化，千分位逗号保留全角），
     *       既改善数字校验，又不改动句读。</li>
     * </ul>
     *
     * <p>归一结果<b>单列</b>在 {@code normalized[]}（它不是"删除"）。
     */
    public static final class C7Encoding implements CleanRule {

        private static final char FULLWIDTH_START = '\uFF01';
        private static final char FULLWIDTH_END = '\uFF5E';
        private static final char FULLWIDTH_OFFSET = 0xFEE0;
        private static final char IDEOGRAPHIC_SPACE = '\u3000';
        private static final char REPLACEMENT = '\uFFFD';

        /** 保留原样的全角标点（设计 C7 ② 的原话）：{@code ，；：？！（）}。 */
        private static final String KEEP_FULLWIDTH = "，；：？！（）";

        @Override
        public String id() {
            return "c7";
        }

        @Override
        public String label() {
            return "编码归一（全角字母数字→半角，句读类标点保留，统计替代字符）";
        }

        @Override
        public boolean defaultEnabled() {
            return true;
        }

        @Override
        public void apply(CleanWork work) {
            for (CleanWork.Page page : cleanablePages(work)) {
                for (CleanWork.Line line : page.lines()) {
                    String original = line.text();
                    StringBuilder sb = new StringBuilder(original.length());
                    int replacements = 0;
                    for (int i = 0; i < original.length(); i++) {
                        char c = original.charAt(i);
                        if (c == REPLACEMENT) {
                            replacements++;
                        }
                        if (c >= FULLWIDTH_START && c <= FULLWIDTH_END && KEEP_FULLWIDTH.indexOf(c) < 0) {
                            sb.append((char) (c - FULLWIDTH_OFFSET));
                        } else if (c == IDEOGRAPHIC_SPACE) {
                            // 全角空格归一去 C3（它还带"连续空白压缩"的语义），这里保持原样
                            sb.append(c);
                        } else {
                            sb.append(c);
                        }
                    }
                    work.addReplacementChars(replacements);
                    String text = sb.toString();
                    if (!text.equals(original)) {
                        line.setText(text);
                        work.normalize(page, line, this, original);
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // C8 水印剔除（默认关）
    // ══════════════════════════════════════════════════════════════

    /**
     * 水印剔除（<b>默认关</b>，合规风险高于收益）：只删<b>命中正则白名单</b>的整行，
     * 删掉的行一律进 {@code removed_lines}。白名单为空 ⇒ 本规则不生效。
     */
    public static final class C8Watermark implements CleanRule {

        @Override
        public String id() {
            return "c8";
        }

        @Override
        public String label() {
            return "水印剔除（正则白名单，默认关）";
        }

        @Override
        public boolean defaultEnabled() {
            return false;
        }

        @Override
        public void apply(CleanWork work) {
            List<Pattern> patterns = work.options().compiledWatermarks();
            if (patterns.isEmpty()) {
                return;
            }
            for (CleanWork.Page page : cleanablePages(work)) {
                for (int i = 0; i < page.lines().size(); i++) {
                    CleanWork.Line line = page.lines().get(i);
                    if (line.removed() || line.text().isBlank() || work.inTable(page, i)) {
                        continue;
                    }
                    for (Pattern pattern : patterns) {
                        if (pattern.matcher(line.text()).find()) {
                            work.remove(page, line, this);
                            break;
                        }
                    }
                }
            }
        }
    }
}
