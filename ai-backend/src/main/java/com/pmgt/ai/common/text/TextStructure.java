package com.pmgt.ai.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本结构的<b>共用判据</b>（清洗 C4/C5 与切分 §3.4.1/§3.4.2 共用一处，避免两套正则各自漂移）。
 *
 * <p>为什么必须共用：切分要"表格整块不截断"，前提是清洗阶段标记的表格区间与切分阶段认的表格
 * 是<b>同一批</b>；一旦两处规则不同，就会出现"清洗说这是表格、切分把它当正文合并"，
 * 而这类错位在结果里看不出来（只表现为"表格片断行"）。
 *
 * <p>全部判据都是<b>纯函数 + 无状态</b>，可以在单测里逐条钉正反例。
 */
public final class TextStructure {

    /** markdown 标题：{@code #}~{@code ######} + 至少一个空格。 */
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");

    /** 编号标题：{@code 第三章 / 第 3 节 / 3.2 / 3.2.1 标题}。 */
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^第\\s*[一二三四五六七八九十百千0-9]+\\s*[章节篇]\\s*\\S.*$|^\\d+(\\.\\d+){1,3}\\s*\\S.*$");

    /** 条款起始：{@code 第X条 / 3. / 3.2 / （一） / (1) / 一、}。 */
    private static final Pattern CLAUSE_START = Pattern.compile(
            "^第\\s*[一二三四五六七八九十百千0-9]+\\s*条.*$"
                    + "|^\\d+(\\.\\d+)*[\\.、]?\\s*\\S.*$"
                    + "|^[（(][一二三四五六七八九十0-9]+[）)]\\s*.*$"
                    + "|^[一二三四五六七八九十]+、\\s*\\S.*$");

    /** 列表/项目符号起始（断行合并的禁区，见设计 §3.3.2 C4 的风险对策）。 */
    private static final Pattern BULLET_START = Pattern.compile("^[-*·•▪◦○●－—]+\\s*\\S.*$|^[①-⑳]\\s*.*$");

    /** 句末标点（含中英文）：断行合并与重叠回退都以它为界。 */
    private static final String SENTENCE_END = "。！？；!?;…";

    /** 标题短行上限（设计 §3.4.1 的"短行（≤30 字）+ 独占一行 + 无句末标点"）。 */
    public static final int SHORT_HEADING_MAX_CHARS = 30;

    private TextStructure() {
    }

    /** markdown 标题的层级（1~6）；不是标题返回 0。 */
    public static int markdownHeadingLevel(String line) {
        String value = line == null ? "" : line.strip();
        if (!MD_HEADING.matcher(value).matches()) {
            return 0;
        }
        int level = 0;
        while (level < value.length() && value.charAt(level) == '#') {
            level++;
        }
        return Math.min(6, Math.max(1, level));
    }

    /**
     * 标题层级：markdown 优先（1~6）；{@code 第X章} 记 1、{@code 第X节} 记 2、
     * 编号标题按点分段数 + 1；"短行 + 无句末标点"记 6（最细一级，只用作边界、不改变章节栈）。
     *
     * <p>不是标题返回 0。
     */
    public static int headingLevel(String line) {
        String value = line == null ? "" : line.strip();
        if (value.isEmpty()) {
            return 0;
        }
        int md = markdownHeadingLevel(value);
        if (md > 0) {
            return md;
        }
        if (value.matches("^第\\s*[一二三四五六七八九十百千0-9]+\\s*章\\s*\\S.*$")) {
            return 1;
        }
        if (value.matches("^第\\s*[一二三四五六七八九十百千0-9]+\\s*[节篇]\\s*\\S.*$")) {
            return 2;
        }
        if (NUMBERED_HEADING.matcher(value).matches()) {
            int dots = 0;
            for (int i = 0; i < value.length() && (Character.isDigit(value.charAt(i)) || value.charAt(i) == '.'); i++) {
                if (value.charAt(i) == '.') {
                    dots++;
                }
            }
            return Math.min(6, 1 + Math.max(1, dots));
        }
        // 短行 + 独占一行（调用方保证）+ 无句末标点 ⇒ 视作最细一级标题（只做边界）
        if (value.length() <= SHORT_HEADING_MAX_CHARS && !endsWithSentencePunct(value)
                && !isClauseStart(value) && value.length() >= 2) {
            return 6;
        }
        return 0;
    }

    /** 是否是"强边界"（标题或条款起始）：切分不在它与后续正文之间切。 */
    public static boolean isBoundary(String line) {
        return headingLevel(line) > 0 || isClauseStart(line) || isBulletStart(line);
    }

    /** 条款起始行（切分时优先独立成片）。 */
    public static boolean isClauseStart(String line) {
        String value = line == null ? "" : line.strip();
        return !value.isEmpty() && CLAUSE_START.matcher(value).matches();
    }

    /** 列表/项目符号起始行（断行合并的禁区）。 */
    public static boolean isBulletStart(String line) {
        String value = line == null ? "" : line.strip();
        return !value.isEmpty() && BULLET_START.matcher(value).matches();
    }

    /**
     * 行尾是否是句末标点（含冒号与右括号：{@code 付款期限（三十日）：} 这类行之后通常是新的一段）。
     *
     * <p>它同时服务两处：C4 的"行尾有标点就不合并"与重叠窗口的"按句回退"。
     */
    public static boolean endsWithSentencePunct(String line) {
        String value = line == null ? "" : line.strip();
        if (value.isEmpty()) {
            return false;
        }
        char last = value.charAt(value.length() - 1);
        return SENTENCE_END.indexOf(last) >= 0 || last == '：' || last == ':'
                || last == '）' || last == '】' || last == '"' || last == '”';
    }

    /** 换行合并的"强制断行"判据：条款起始、列表符号、标题都不许被并进上一行（设计 §3.3.2 C4）。 */
    public static boolean forcesLineBreak(String line) {
        return isClauseStart(line) || isBulletStart(line) || headingLevel(line) > 0;
    }

    /** 句子切分（超过 {@code maxLen} 的段落二次切时用）：在句末标点后断开，保留标点。 */
    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SENTENCE_END.indexOf(c) >= 0 && !(c == '…')) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** 是否是 markdown 表格行（竖线 ≥ 2 个：{@code |a|b|}）。 */
    public static boolean isPipeRow(String line) {
        String value = line == null ? "" : line;
        int pipes = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '|') {
                pipes++;
            }
        }
        return pipes >= 2;
    }

    /** 是否是 markdown 表格的分隔行（{@code |---|:--:|}）。 */
    public static boolean isTableSeparator(String line) {
        String value = line == null ? "" : line.strip();
        if (!isPipeRow(value)) {
            return false;
        }
        String body = value.replace("|", "").replace(" ", "").replace("\t", "");
        if (body.isEmpty()) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '-' && c != ':' && c != '+') {
                return false;
            }
        }
        return body.indexOf('-') >= 0;
    }

    /**
     * 识别连续竖线行构成的表格块（0-based 行区间，含两端）。
     *
     * <p>判据：连续 {@link #isPipeRow} 行 ≥ 2 行，且（含分隔行 或 行数 ≥ 3）。
     * 只认结构、不猜语义——平台 {@code blocks[label=table]} 的 bbox 目前无法与"行"对齐
     * （{@code BlockRef} 不带行文本/行号），所以本函数不认识"没有竖线的表格"，
     * 这类表格按正文处理（宁可少标记，也不把正文误判成表格）。
     */
    public static List<int[]> pipeTableRanges(List<String> lines) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= lines.size(); i++) {
            boolean pipe = i < lines.size() && isPipeRow(lines.get(i));
            if (pipe) {
                if (start < 0) {
                    start = i;
                }
                continue;
            }
            if (start >= 0) {
                int end = i - 1;
                if (end - start + 1 >= 2 && qualifiesAsTable(lines, start, end)) {
                    out.add(new int[] {start, end});
                }
                start = -1;
            }
        }
        return out;
    }

    private static boolean qualifiesAsTable(List<String> lines, int start, int end) {
        for (int i = start; i <= end; i++) {
            if (isTableSeparator(lines.get(i))) {
                return true;
            }
        }
        return end - start + 1 >= 3;
    }

    /** 表格首行（表头）摘要：去掉竖线后的前若干字符，用于切片前缀 {@code 【表格：…】}。 */
    public static String tableHeader(String firstRow, int maxChars) {
        String value = firstRow == null ? "" : firstRow.replace('|', ' ').strip().replaceAll("\\s+", " ");
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    /** 归一化用于"跨页重复行"比较：折空白 + 去数字 + 转小写（设计 §3.3.2 C1 的判据）。 */
    public static String repeatKey(String line) {
        String value = line == null ? "" : line;
        return value.replaceAll("\\s+", "").replaceAll("\\d+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
