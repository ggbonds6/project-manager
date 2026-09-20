package com.pmgt.ai.module.check;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性校验：把"可信度"从主观打分换成<b>可复现的检查</b>。
 *
 * <p>本类是 {@code ai-service/src/pm_ai/checks.py} 的 1:1 移植（行为对齐优先于代码美观），
 * 返回结构的键名与取值（{@code name}/{@code status}/{@code detail}/{@code items}，
 * status ∈ pass|warn|fail|skip）是主系统与前端在读的契约，<b>一个字段都不能改</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>原做法是让<b>模型自评置信度</b>（0.00–1.00）。它有用（能排序复核优先级），但有两个硬伤：
 * <ol>
 *   <li><b>不可验证</b>——0.87 是怎么来的？没人知道，也无法复现；</li>
 *   <li><b>实测会失灵</b>——曾出现"OCR 平均置信度 0.97 的文件里金额被认错"，分数高不等于内容对。</li>
 * </ol>
 * 所以再加一层<b>代码算出来的、可复现的判断</b>：
 * <table border="1">
 *   <caption>四条检查</caption>
 *   <tr><th>检查</th><th>判据</th><th>为什么可信</th></tr>
 *   <tr><td>金额大小写互校</td><td>阿拉伯数字金额 vs 中文大写金额是否同一个数</td>
 *       <td>两个独立来源互相印证，<b>错一个就会露出来</b></td></tr>
 *   <tr><td>同一数值写法一致</td><td>同一数字在文中出现多次时写法是否一致</td>
 *       <td>见「7,780,000.00」与「7780000」混用会误读</td></tr>
 *   <tr><td>比例合计</td><td>同一段里各期比例之和是否接近 100%</td><td>算术，与语义无关</td></tr>
 *   <tr><td>答案数字可溯源</td><td><b>模型答案里的每个数字是否真能在原文找到</b></td>
 *       <td>这条直接卡"编造"——比让模型自评可靠得多</td></tr>
 * </table>
 * <p>任何一条都<b>不修改原文</b>，只做"指出"。
 *
 * <h2>已实测的两条口径（踩坑结论，别"顺手改得更合理"）</h2>
 * <ul>
 *   <li><b>大小写只在同一句内配对</b>：按「。」「；」「;」和换行切句，同一句里同时有大写与阿拉伯数字才核对，
 *       拆成两句即 {@code skip}（而不是全页配对——那样会引入大量假失败）；</li>
 *   <li><b>比例按「段落」而非句子切分</b>：实测合同里「已付 70%」与「竣工后 30%」常在同一段的两个句子里，
 *       按句切就各只剩 1 个比例、整条检查白白跳过；同段也可能混入无关比例（进度、完成率），
 *       所以不通过时只给 {@code warn}（提示），不下结论；容差固定 ±1 个百分点
 *       （OCR 会把 99.5% 读成 99%，卡死在 100 会产生大量假警）。</li>
 * </ul>
 *
 * <h2>已知缺口（<b>行为照旧，不要偷偷修</b>；要修时先让对应测试失败）</h2>
 * <ol>
 *   <li>{@link #checkNumberStyle(List)} <b>抓不到</b>「7,780,000.00」与「7780000」混用：
 *       它用 {@code Decimal} 的字符串形态当字典键，{@code Decimal("7780000.00")} → {@code "7780000.00"}，
 *       与 {@code "7780000"} 是两个键，于是不会被报成"同一数值的多种写法"。
 *       也就是说这条检查只认"值相同<b>且</b>字符串形态也相同（仅千分位/全角差异）"的混用。
 *       模块说明里的那个例子是<b>过头承诺</b>——测试 {@code test_number_style_misses_trailing_zero_variants}
 *       固化当前行为，谁按说明去改成数值归一键，谁就会看到它失败并回来读这段。</li>
 *   <li>{@link #toDecimal(String)} 的说明写着"统一全角"，但实现里<b>并没有</b>全角数字→半角的显式替换
 *       （Python 侧能通过是因为 {@code Decimal} 自身接受 Unicode 数字）。
 *       Java 的 {@code BigDecimal} <b>不</b>接受全角数字，所以这里显式补了一层 Nd 类数字归一化，
 *       目的只是对齐 Python 的实测行为（{@code toDecimal("１２３") == 123}），不是"修好"这条检查。</li>
 * </ol>
 *
 * <h2>Java 正则与 Python 的 Unicode 差异（本类的处理）</h2>
 * <p>Python 3 的 {@code str} 正则里 {@code \d} / {@code \s} 默认是 Unicode 语义（{@code \d} 能匹配全角
 * 「０-９」、{@code \s} 能匹配全角空格 U+3000）；Java 默认是 ASCII 语义。差异会直接改变行为
 * （全角金额/百分号漏检），所以本类所有正则一律加 {@link Pattern#UNICODE_CHARACTER_CLASS}。
 * 另外 Python 的 {@code Decimal ==} 是<b>数值比较</b>（{@code Decimal("1.0") == Decimal("1")}），
 * 而 Java 的 {@code BigDecimal.equals} 是<b>值+标度</b>比较（不等）——本类一律用 {@code compareTo == 0}
 * 与 {@code stripTrailingZeros()} 归一，以对齐 Python 语义。
 */
public final class Checks {

    private Checks() {
        // 纯函数工具类：Python 侧就是模块级函数，不需要实例状态；DocumentReader 与测试直接静态调用。
    }

    /** 状态取值域（契约，不可改）。 */
    public static final String PASS = "pass";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";
    public static final String SKIP = "skip";

    private static final BigDecimal HUNDRED = new BigDecimal(100);
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** Python: {@code re.UNICODE} 等价物——{@code \d}/\s 走 Unicode 语义，见类注释。 */
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;

    // ── 中文大写金额解析 ─────────────────────────────────────────────

    private static final Map<Character, Integer> CN_DIGIT = Map.ofEntries(
            Map.entry('零', 0),
            Map.entry('壹', 1),
            Map.entry('贰', 2),
            Map.entry('叁', 3),
            Map.entry('肆', 4),
            Map.entry('伍', 5),
            Map.entry('陆', 6),
            Map.entry('柒', 7),
            Map.entry('捌', 8),
            Map.entry('玖', 9));

    private static final Map<Character, Integer> CN_UNIT = Map.of(
            '拾', 10,
            '佰', 100,
            '仟', 1000);

    private static final Map<Character, Integer> CN_SECTION = Map.of(
            '万', 10_000,
            '亿', 100_000_000);

    /** 连续的中文大写数字（含单位）。 */
    private static final Pattern CN_AMOUNT_RE =
            Pattern.compile("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]{2,}", U);

    /** 阿拉伯数字金额：带千分位或有小数位。 */
    private static final Pattern NUM_AMOUNT_RE =
            Pattern.compile("(\\d{1,3}(?:[,，]\\d{3})+(?:[.．]\\d{1,2})?|\\d+[.．]\\d{1,2})", U);

    private static final Pattern PERCENT_RE =
            Pattern.compile("(\\d+(?:[.．]\\d+)?)\\s*[%％]", U);

    /** 大小写互校的切句口径：「。」「；」「;」与换行（同句内才配对）。 */
    private static final Pattern SENTENCE_SPLIT_RE = Pattern.compile("[。；;\\n]");

    /** 比例合计的切分口径：<b>段落</b>（空行分隔），不是句子。 */
    private static final Pattern PARAGRAPH_SPLIT_RE = Pattern.compile("\\n\\s*\\n", U);

    /** 数值写法一致性：一个数字串（含全角、千分位、小数点）。 */
    private static final Pattern STYLE_NUMBER_RE =
            Pattern.compile("[0-9０-９][0-9０-９,，.．]*", U);

    /** 答案/原文里的任意数字（带可选币种符号）。 */
    private static final Pattern ANY_NUMBER_RE =
            Pattern.compile("[¥￥]?\\s*(\\d{1,3}(?:[,，]\\d{3})+(?:[.．]\\d{1,2})?|\\d+(?:[.．]\\d{1,2})?)", U);

    // ── 中文大写金额解析 ─────────────────────────────────────────────

    /**
     * 中文大写金额 → 数字。{@code 柒佰柒拾捌万元整} → {@code 7780000}。
     *
     * <p>不认识的字符直接跳过（"人民币"/"元"/"整"等），不影响结果。
     * 解析失败返回 {@code null}——<b>宁可说"没看懂"，也不要猜一个数</b>。
     * 装饰字（人民币/元/整）、空串、以及金额为零（{@code total == 0}）都返回 {@code null}，
     * 否则大小写互校会把 {@code null} 当成 0 去和阿拉伯数字比，制造假失败。
     */
    public static BigDecimal cnAmountToNumber(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String raw = text.replace("整", "").replace("正", "");
        BigDecimal total = BigDecimal.ZERO;    // 已结算的「万/亿」段
        BigDecimal section = BigDecimal.ZERO;  // 当前段
        BigDecimal number = BigDecimal.ZERO;   // 当前数字
        boolean seen = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            Integer digit = CN_DIGIT.get(ch);
            if (digit != null) {
                number = BigDecimal.valueOf(digit);
                seen = true;
                continue;
            }
            Integer unit = CN_UNIT.get(ch);
            if (unit != null) {
                // "拾万"这类省略了前导"壹" → number or 1
                BigDecimal head = number.signum() == 0 ? ONE : number;
                section = section.add(head.multiply(BigDecimal.valueOf(unit)));
                number = BigDecimal.ZERO;
                seen = true;
                continue;
            }
            Integer sectionUnit = CN_SECTION.get(ch);
            if (sectionUnit != null) {
                section = section.add(number).multiply(BigDecimal.valueOf(sectionUnit));
                total = total.add(section);
                section = BigDecimal.ZERO;
                number = BigDecimal.ZERO;
                seen = true;
            }
            // 其余字符（人民币/元/角/分…）忽略
        }
        total = total.add(section).add(number);
        if (!seen || total.signum() == 0) {
            return null;
        }
        return total;
    }

    /**
     * 阿拉伯数字串 → {@link BigDecimal}（去千分位、统一全角）。
     *
     * <p>⚠️ 已知缺口见类注释第 2 条：说明里的"统一全角"名不副实，这里只显式替换了
     * 千分位 {@code ,，} 与全角小数点 {@code ．}，外加一层为了对齐 Python 行为的 Nd 数字归一化。
     * 非法串返回 {@code null}（Python 是 {@code InvalidOperation} → {@code None}）。
     */
    public static BigDecimal toDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(",", "")
                .replace("，", "")
                .replace("．", ".")
                .strip();  // Python 的 strip() 去 Unicode 空白，Java 要用 strip() 而不是 trim()
        if (s.isEmpty()) {
            return null;
        }
        // Python 的 Decimal("１２３") 能直接得到 123（Decimal 接受 Unicode 数字），Java 的 BigDecimal 不接受；
        // 这里显式归一化 Nd 类数字字符，目的只是对齐 Python 的实测行为。
        s = normalizeUnicodeDigits(s);
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把任意文字里的 Nd 类（十进制数字）字符统一成 ASCII 0-9；其它字符原样保留。 */
    private static String normalizeUnicodeDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d = Character.getType(ch) == Character.DECIMAL_DIGIT_NUMBER ? Character.digit(ch, 10) : -1;
            sb.append(d >= 0 ? (char) ('0' + d) : ch);
        }
        return sb.toString();
    }

    // ── 单条检查 ────────────────────────────────────────────────────

    /**
     * 金额大小写互校——<b>最有价值的一条</b>。
     *
     * <p>同一处金额通常会同时写「¥7,780,000.00」和「柒佰柒拾捌万元整」。两个来源独立，
     * 一旦其中一个被识别/摘录错，这里立刻能看出来。
     *
     * <p>配对粒度是<b>同一句/同一行</b>：按「。」「；」「;」与换行切分后再找配对。
     * 刻意不做全页配对（那样会引入大量假失败）；没有可配对的就 {@code skip}——
     * "没检查"和"检查通过"在复核界面上是两回事。
     */
    public static Map<String, Object> checkAmountCase(List<String> pages) {
        List<Map<String, Object>> items = new ArrayList<>();
        int checked = 0;
        List<String> pageList = pages == null ? List.of() : pages;
        for (int idx = 0; idx < pageList.size(); idx++) {
            int pageNo = idx + 1;
            String text = pageList.get(idx) == null ? "" : pageList.get(idx);
            for (String seg : SENTENCE_SPLIT_RE.split(text, -1)) {
                List<String> cn = findAll(CN_AMOUNT_RE, seg, 0);
                List<String> ar = findAll(NUM_AMOUNT_RE, seg, 0);
                if (cn.isEmpty() || ar.isEmpty()) {
                    continue;
                }
                BigDecimal cnNum = null;
                for (String c : cn) {
                    BigDecimal n = cnAmountToNumber(c);
                    if (n != null) {
                        cnNum = n;
                        break;
                    }
                }
                List<BigDecimal> arNums = new ArrayList<>();
                List<String> arRaws = new ArrayList<>();
                for (String a : ar) {
                    BigDecimal n = toDecimal(a);
                    if (n != null) {
                        arNums.add(n);
                        arRaws.add(a);
                    }
                }
                if (cnNum == null || arNums.isEmpty()) {
                    continue;
                }
                checked++;
                String match = null;
                for (int i = 0; i < arNums.size(); i++) {
                    // Python 的 Decimal == 是数值比较；BigDecimal.equals 是值+标度比较，必须用 compareTo
                    if (arNums.get(i).compareTo(cnNum) == 0) {
                        match = arRaws.get(i);
                        break;
                    }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("page", pageNo);
                if (match != null) {
                    item.put("status", PASS);
                    item.put("detail", match + " ↔ " + cn.get(0) + "（一致）");
                } else {
                    // detail 要把两个数都摊开给复核人员看，否则"哪里不一致"还得自己找
                    item.put("status", FAIL);
                    item.put("detail", "大写「" + cn.get(0) + "」= " + formatWithCommas(cnNum)
                            + "，但阿拉伯数字写作 " + pythonListRepr(arRaws)
                            + " —— **不一致，必须人工核对**");
                }
                items.add(item);
            }
        }
        if (checked == 0) {
            return result("金额大小写互校", SKIP, "文中未找到可配对的大小写金额", List.of());
        }
        long fails = items.stream().filter(i -> FAIL.equals(i.get("status"))).count();
        String detail = "共核对 " + checked + " 处" + (fails > 0 ? "，**" + fails + " 处不一致**" : "，全部一致");
        return result("金额大小写互校", fails > 0 ? FAIL : PASS, detail, items);
    }

    /**
     * 比例合计：同一段里的各期比例之和是否接近 100%。
     *
     * <p>⚠️ 切分粒度是<b>段落</b>而不是句子——实测合同里「已付 70%」与「竣工后 30%」
     * 就在同一段的两个句子里，按句切会漏掉。但同段也可能混入无关比例（进度、完成率），
     * 所以不通过时只给 {@code warn}（提示），不下结论。容差固定 ±1 个百分点。
     */
    public static Map<String, Object> checkPercentSum(List<String> pages) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> pageList = pages == null ? List.of() : pages;
        for (int idx = 0; idx < pageList.size(); idx++) {
            int pageNo = idx + 1;
            String text = pageList.get(idx) == null ? "" : pageList.get(idx);
            for (String seg : PARAGRAPH_SPLIT_RE.split(text, -1)) {
                List<BigDecimal> nums = new ArrayList<>();
                for (String raw : findAll(PERCENT_RE, seg, 1)) {
                    BigDecimal n = toDecimal(raw);
                    if (n != null) {
                        nums.add(n);
                    }
                }
                if (nums.size() < 2) {
                    continue;
                }
                BigDecimal total = BigDecimal.ZERO;
                for (BigDecimal n : nums) {
                    total = total.add(n);
                }
                // |合计 - 100| <= 1（±1 个百分点；OCR 会把 99.5% 读成 99%，卡死会产生大量假警）
                boolean ok = total.subtract(HUNDRED).abs().compareTo(ONE) <= 0;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("page", pageNo);
                item.put("status", ok ? PASS : WARN);
                item.put("detail", "「" + clipCodePoints(seg.strip(), 60) + "…」比例合计 "
                        + stripTrailingZeros(total) + "%"
                        + (ok ? "" : "（不等于 100%，也可能只是同段的无关比例，请自行判断）"));
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return result("同段比例合计", SKIP, "未找到同段多比例的情形", List.of());
        }
        boolean anyWarn = items.stream().anyMatch(i -> WARN.equals(i.get("status")));
        return result("同段比例合计", anyWarn ? WARN : PASS, "检查 " + items.size() + " 段", items);
    }

    /**
     * 同一数值的<b>写法是否一致</b>（全角/半角、千分位混用会误读）。
     *
     * <p>⚠️ 已知缺口见类注释第 1 条：用 {@code Decimal} 的字符串形态当键，
     * 所以「7,780,000.00」与「7780000」<b>不会</b>被判为同一数值的多种写法（测试固化了该行为）。
     */
    public static Map<String, Object> checkNumberStyle(List<String> pages) {
        Map<String, Set<String>> styles = new LinkedHashMap<>();
        List<String> pageList = pages == null ? List.of() : pages;
        for (String text : pageList) {
            for (String raw : findAll(STYLE_NUMBER_RE, text == null ? "" : text, 0)) {
                if (raw.length() < 4) {
                    continue;
                }
                // Python 在这里显式做了全角→半角替换（照搬，虽然 toDecimal 现在也能归一）；
                // 这一句不是多余的：它说明了"写法"的比较基数是什么。
                BigDecimal num = toDecimal(raw.replace("０", "0")
                        .replace("１", "1")
                        .replace("２", "2")
                        .replace("３", "3")
                        .replace("４", "4")
                        .replace("５", "5")
                        .replace("６", "6")
                        .replace("７", "7")
                        .replace("８", "8")
                        .replace("９", "9"));
                if (num == null) {
                    continue;
                }
                // 键 = Python 的 str(Decimal)：值相同但字符串形态不同（末尾零）仍是不同的键——已知缺口，别改
                styles.computeIfAbsent(num.toString(), k -> new TreeSet<>()).add(raw);
            }
        }
        Map<String, List<String>> mixed = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : styles.entrySet()) {
            if (e.getValue().size() > 1) {
                mixed.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        if (mixed.isEmpty()) {
            return result("数值写法一致性", PASS, "未发现同一数值的多种写法", List.of());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : mixed.entrySet()) {
            if (items.size() >= 12) {  // Python: list(mixed.items())[:12]
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", WARN);
            item.put("detail", e.getKey() + " 出现了 " + e.getValue().size()
                    + " 种写法：" + String.join(" / ", e.getValue()));
            items.add(item);
        }
        return result("数值写法一致性", WARN,
                mixed.size() + " 个数值存在多种写法（可能是全角/千分位混用）", items);
    }

    /**
     * 对原文跑全部检查。{@code pages} 是<b>逐页文本</b>（页码即 list 下标 + 1）。
     *
     * <p>顺序与 Python 一致：金额大小写互校 → 同段比例合计 → 数值写法一致性。
     */
    public static List<Map<String, Object>> checkDocument(List<String> pages) {
        return List.of(checkAmountCase(pages), checkPercentSum(pages), checkNumberStyle(pages));
    }

    // ── 输出侧：答案里的数字能不能在原文找到 ──────────────────────────

    /**
     * 抽出文中全部数字（去重、剔除 0）。
     *
     * <p>用 {@code stripTrailingZeros()} 归一后入集合：Python 的 {@code set[Decimal]} 走<b>数值</b>
     * 相等（{@code 7,780,000} 与 {@code 7,780,000.00} 是同一个数），而 Java 的
     * {@code BigDecimal.equals} 会把它们当成两个——不归一会直接造成假 warn。
     */
    public static Set<BigDecimal> normalizedNumbers(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        for (String raw : findAll(ANY_NUMBER_RE, text, 1)) {
            BigDecimal n = toDecimal(raw);
            if (n != null && n.signum() != 0) {
                out.add(n.stripTrailingZeros());
            }
        }
        return out;
    }

    /**
     * <b>反幻觉</b>：模型答案里的每个数字，是否真能在原文里找到。
     *
     * <p>这是"禁止虚构"的机器校验——比模型自评置信度可靠。找不到的数字单独列出来，
     * 提示"可能是推断/计算得来，或可能是编造"（<b>推断也可能是合理的</b>，例如按比例反推总额，
     * 所以状态是 {@code warn} 而不是 {@code fail}，由人判断）。
     *
     * <p>比较按<b>数值</b>（与写法/末尾零无关），这正是想要的口径。
     * 答案里没有数字时显式 {@code skip}，而不是 {@code pass}。
     */
    public static Map<String, Object> verifyNumbersInSource(String answer, List<String> pages) {
        List<String> pageList = pages == null ? List.of() : pages;
        Set<BigDecimal> src = normalizedNumbers(String.join("\n", pageList));
        Set<BigDecimal> ans = normalizedNumbers(answer);
        if (ans.isEmpty()) {
            return result("答案数字可溯源", SKIP, "答案中没有数字", List.of());
        }
        List<BigDecimal> missing = ans.stream().filter(n -> !src.contains(n)).sorted().toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (BigDecimal n : missing.stream().limit(20).toList()) {  // Python: missing[:20]
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", WARN);
            item.put("detail", formatWithCommas(n) + " 未在原文中找到（可能是推断/计算，也可能是编造）");
            items.add(item);
        }
        String detail = "答案含 " + ans.size() + " 个数值，其中 " + (ans.size() - missing.size())
                + " 个在原文中找到" + (missing.isEmpty() ? "" : "，**" + missing.size() + " 个未找到**");
        return result("答案数字可溯源", missing.isEmpty() ? PASS : WARN, detail, items);
    }

    // ── 内部工具 ────────────────────────────────────────────────────

    /** 输出契约：{@code {name, status, detail, items}}——前端按 status 着色，键名不能变。 */
    private static Map<String, Object> result(String name, String status, String detail,
                                              List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("status", status);
        out.put("detail", detail);
        out.put("items", new ArrayList<>(items));
        return out;
    }

    private static List<String> findAll(Pattern pattern, String text, int group) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(group));
        }
        return out;
    }

    /** Python {@code f"{n:,}"}：千分位分组，且不进科学计数法。 */
    private static String formatWithCommas(BigDecimal n) {
        String s = n.toPlainString();
        boolean negative = s.startsWith("-");
        if (negative) {
            s = s.substring(1);
        }
        int dot = s.indexOf('.');
        String intPart = dot < 0 ? s : s.substring(0, dot);
        String fracPart = dot < 0 ? "" : s.substring(dot);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = intPart.length() - 1; i >= 0; i--) {
            sb.append(intPart.charAt(i));
            count++;
            if (count % 3 == 0 && i > 0) {
                sb.append(',');
            }
        }
        return (negative ? "-" : "") + sb.reverse() + fracPart;
    }

    /** Python {@code f"{decimal.normalize()}"}：去掉末尾零（注意 100 → "1E+2"，Python 也是如此）。 */
    private static String stripTrailingZeros(BigDecimal n) {
        if (n.signum() == 0) {
            return "0";
        }
        return n.stripTrailingZeros().toString();
    }

    /** Python 的 list repr（{@code ['7,780,001.00']}）：detail 里要把原文数字摊开给人看。 */
    private static String pythonListRepr(List<String> raws) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < raws.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(raws.get(i)).append('\'');
        }
        return sb.append(']').toString();
    }

    /** Python 的 {@code s[:60]} 按字符（码点）截断——Java 的 substring 按 UTF-16 单元，非 BMP 会截坏。 */
    private static String clipCodePoints(String s, int max) {
        String value = Objects.requireNonNullElse(s, "");
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
