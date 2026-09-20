package com.pmgt.ai.module.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code checks.py} 确定性校验的回归测试（{@code ai-service/tests/test_checks.py} 的 1:1 重写）。
 *
 * <p>这些断言原本只存在于模块注释和一次性手工脚本里——注释<b>只有人读得到</b>，
 * 所以同类错误（大小写金额不一致、比例合计不对）会一次次重新出现。这里把它们固化成可自动发现的断言。
 *
 * <p>所有断言都对着<b>当前实现</b>写（先读代码、再定期望值）；发现注释与实现不一致的地方，
 * 在对应测试里显式注明，并在测试名/注释里写清"这条挡的是什么坑"。
 *
 * <p><b>纯离线、无 IO</b>：只喂字符串、只看返回值，不碰网关、不碰磁盘。
 */
class ChecksTest {

    // ══════════════════════════════════════════════════════════════════
    // 中文大写金额解析
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：注释里唯一被实测过的样例（合同上的「柒佰柒拾捌万元整」）被解析错。
     *
     * <p>真实场景：平台 OCR 能完整读出这串大写，一旦解析器算错，大小写互校就会
     * 把"对的合同"判成不一致（或者反过来），复核人员从此不再信任这条检查。
     */
    @Test
    @DisplayName("test_cn_amount_documented_sample")
    void test_cn_amount_documented_sample() {
        assertNum("7780000", Checks.cnAmountToNumber("柒佰柒拾捌万元整"));
    }

    /**
     * 挡的是：万/亿分段拼接算错（大额合同必现）。
     *
     * <p>实现里 {@code 万}/{@code 亿} 会把"当前段"结算后累加，段与段之间是<b>加法</b>：
     * 壹亿贰仟万 = 1 亿 + 2 千万 = 120,000,000，不是 1.2 亿之外的别的数。
     */
    @Test
    @DisplayName("test_cn_amount_section_join")
    void test_cn_amount_section_join() {
        assertNum("120000000", Checks.cnAmountToNumber("壹亿贰仟万元整"));
        // 逐级单位：叁仟贰佰壹拾 = 3000 + 200 + 10
        assertNum("3210", Checks.cnAmountToNumber("叁仟贰佰壹拾元整"));
        // 「拾万」省略前导「壹」（合同里很常见）→ 实现按 `number or 1` 处理 = 100,000
        assertNum("100000", Checks.cnAmountToNumber("拾万元整"));
    }

    /**
     * 挡的是：解析不出来时<b>猜一个数</b>。
     *
     * <p>注释写得很清楚："宁可说'没看懂'，也不要猜一个数"。
     * 装饰字（人民币/元/整）、空串、以及金额为零（total == 0）都必须返回 null，
     * 否则大小写互校会把 null 当成 0 去和阿拉伯数字比，制造假失败。
     */
    @Test
    @DisplayName("test_cn_amount_unparsable_returns_none")
    void test_cn_amount_unparsable_returns_none() {
        assertNull(Checks.cnAmountToNumber(""));
        assertNull(Checks.cnAmountToNumber("人民币元整"));
        assertNull(Checks.cnAmountToNumber("零元整"));
    }

    /**
     * 挡的是：阿拉伯金额串（带千分位/全角）被当成非法而丢掉。
     *
     * <p>Python 实现只显式替换了千分位 {@code ,，} 与全角小数点 {@code ．}，<b>没有</b>做全角→半角数字替换；
     * 全角数字能通过是因为 Python 的 {@code Decimal} 自身接受 Unicode 数字（{@code Decimal("１２３") == 123}）。
     * 这是 docstring「统一全角」与实现之间的一处<b>轻微不一致</b>：这里按实测行为断言。
     * （Java 的 {@code BigDecimal} 不接受全角数字，故 {@link Checks#toDecimal(String)} 里补了 Nd 数字归一化，
     * 只为对齐本测试的期望值，见类注释"已知缺口"第 2 条。）
     */
    @Test
    @DisplayName("test_to_decimal_normalizes")
    void test_to_decimal_normalizes() {
        assertNum("1234567.89", Checks.toDecimal("1,234,567.89"));
        assertNum("123", Checks.toDecimal("１２３"));
        assertNum("1234", Checks.toDecimal("１，２３４"));   // 全角逗号被替换掉
        assertNum("12.5", Checks.toDecimal("12．5"));        // 全角小数点被替换掉
        assertNum("7780000.00", Checks.toDecimal(" 7,780,000.00 "));
        assertNull(Checks.toDecimal("abc"));
        assertNull(Checks.toDecimal(""));
    }

    // ══════════════════════════════════════════════════════════════════
    // 金额大小写互校
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：金额被识别错却报"通过"（或反过来制造假警）。
     *
     * <p>这是 checks.py 里最有价值的一条：大小写两个来源独立，错一个就会露出来。
     */
    @Test
    @DisplayName("test_check_amount_case_pass_and_fail")
    void test_check_amount_case_pass_and_fail() {
        Map<String, Object> ok = Checks.checkAmountCase(
                List.of("合同总价 ¥7,780,000.00（大写：柒佰柒拾捌万元整）。"));
        assertEquals("金额大小写互校", ok.get("name"));
        assertEquals("pass", ok.get("status"));
        assertEquals("pass", item(ok, 0).get("status"));
        assertEquals(1, item(ok, 0).get("page"));

        Map<String, Object> bad = Checks.checkAmountCase(
                List.of("合同总价 ¥7,780,001.00（大写：柒佰柒拾捌万元整）。"));
        assertEquals("fail", bad.get("status"));
        assertEquals("fail", item(bad, 0).get("status"));
        // detail 要把两个数都摊开给复核人员看，否则"哪里不一致"还得自己找
        assertTrue(String.valueOf(item(bad, 0).get("detail")).contains("7,780,000"),
                "detail 里应有大写金额折算出的 7,780,000：" + item(bad, 0).get("detail"));
        assertTrue(String.valueOf(item(bad, 0).get("detail")).contains("7,780,001.00"),
                "detail 里应摊开阿拉伯数字原文：" + item(bad, 0).get("detail"));
    }

    /**
     * 挡的是：没有可配对的大小写金额时硬给一个结论。
     *
     * <p>实现口径是 skip（"文中未找到可配对的大小写金额"），而不是 pass——
     * "没检查"和"检查通过"在复核界面上是两回事。
     */
    @Test
    @DisplayName("test_check_amount_case_skips_without_pair")
    void test_check_amount_case_skips_without_pair() {
        assertEquals("skip", Checks.checkAmountCase(List.of("本合同自双方签字之日起生效。")).get("status"));
        // 只有大写、没有阿拉伯数字 → 无从互校，同样 skip
        assertEquals("skip", Checks.checkAmountCase(List.of("金额为柒佰柒拾捌万元整。")).get("status"));
        assertEquals("skip", Checks.checkAmountCase(List.of()).get("status"));
    }

    /** 挡的是：页码对不上（前端按 page 跳转，错一页等于把人带到错的地方）。 */
    @Test
    @DisplayName("test_check_amount_case_page_number_is_one_based")
    void test_check_amount_case_page_number_is_one_based() {
        Map<String, Object> result = Checks.checkAmountCase(
                List.of("第一页没有金额", "第二页 ¥10.00（壹拾元整）"));
        assertEquals("pass", result.get("status"));
        assertEquals(2, item(result, 0).get("page"));
    }

    /**
     * 挡的是：以为"大小写互校"是全页配对。
     *
     * <p>实现按「。」「；」「;」和换行切分、<b>只在同一句/同一行内</b>配对
     * （注释：大小写金额通常在同一句里）。
     * 所以把这句拆成两句后，这条检查会 skip 而不是误报 fail——这是刻意的口径，
     * 写清楚免得有人"顺手"改成全页配对引入大量假失败。
     */
    @Test
    @DisplayName("test_check_amount_case_requires_same_sentence")
    void test_check_amount_case_requires_same_sentence() {
        Map<String, Object> result = Checks.checkAmountCase(
                List.of("总价 ¥7,780,000.00。大写：柒佰柒拾捌万元整。"));
        assertEquals("skip", result.get("status"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 同段比例合计
    // ══════════════════════════════════════════════════════════════════

    /** 挡的是：付款比例合计不是 100% 却没人提示（审计最常抓的问题之一）。 */
    @Test
    @DisplayName("test_check_percent_sum_pass_and_warn")
    void test_check_percent_sum_pass_and_warn() {
        Map<String, Object> ok = Checks.checkPercentSum(List.of("付款：已付 70%，竣工后 30%。"));
        assertEquals("pass", ok.get("status"));
        assertEquals("pass", item(ok, 0).get("status"));

        Map<String, Object> bad = Checks.checkPercentSum(List.of("付款：已付 70%，竣工后 20%。"));
        assertEquals("warn", bad.get("status"));
        assertEquals("warn", item(bad, 0).get("status"));
    }

    /**
     * 挡的是：容差被改大/改小（实现是 |合计 - 100| &lt;= 1，即 ±1 个百分点）。
     *
     * <p>容差是有意的：OCR 会把 99.5% 读成 99%，卡死在 100 会产生大量假警。
     */
    @Test
    @DisplayName("test_check_percent_sum_tolerance_is_one_point")
    void test_check_percent_sum_tolerance_is_one_point() {
        assertEquals("pass", Checks.checkPercentSum(List.of("已付 70%，尾款 29%。")).get("status"));
        assertEquals("warn", Checks.checkPercentSum(List.of("已付 70%，尾款 28%。")).get("status"));
    }

    /**
     * 挡的是：把切分粒度从"段落"改成"句子"（注释里明确写过的实测坑）。
     *
     * <p>「已付 70%」与「竣工后 30%」常在同一段的两个句子里：按句切就各只剩 1 个比例、
     * 整条检查白白跳过。所以单换行（同段）必须能合起来算 100%；
     * 而空行分隔的两段各自只有 1 个比例，只能 skip。
     */
    @Test
    @DisplayName("test_check_percent_sum_splits_by_paragraph_not_sentence")
    void test_check_percent_sum_splits_by_paragraph_not_sentence() {
        assertEquals("pass", Checks.checkPercentSum(List.of("已付 70%\n竣工后 30%")).get("status"));
        assertEquals("skip", Checks.checkPercentSum(List.of("已付 70%\n\n竣工后 30%")).get("status"));
    }

    /** 挡的是：全角百分号（扫描件里很常见）被漏掉。 */
    @Test
    @DisplayName("test_check_percent_sum_accepts_full_width_sign")
    void test_check_percent_sum_accepts_full_width_sign() {
        assertEquals("pass", Checks.checkPercentSum(List.of("已付 70％，竣工后 30％")).get("status"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 数值写法一致性
    // ══════════════════════════════════════════════════════════════════

    /** 挡的是：同一数值千分位写法混用（1,234 / 1234）不被发现。 */
    @Test
    @DisplayName("test_number_style_flags_thousand_separator_mixing")
    void test_number_style_flags_thousand_separator_mixing() {
        Map<String, Object> result = Checks.checkNumberStyle(List.of("金额 1,234 元", "同一金额 1234 元"));
        assertEquals("warn", result.get("status"));
        assertTrue(String.valueOf(item(result, 0).get("detail")).contains("1,234"),
                "detail 里应列出两种写法：" + item(result, 0).get("detail"));
    }

    /**
     * ⚠️ 注释与实现<b>不一致</b>，这里固化的是实测行为。
     *
     * <p>模块 docstring 举例说「7,780,000.00」与「7780000」混用会被这条检查抓到，
     * 但实现用 {@code str(Decimal(...))} 当字典键：{@code Decimal("7780000.00")} → {@code "7780000.00"}，
     * 与 {@code "7780000"} 是两个不同的键，于是<b>不会</b>被报告为"同一数值的多种写法"。
     * 也就是说这条检查只认"值相同且字符串形态也相同（仅千分位/全角差异）"的混用。
     *
     * <p>固化当前行为是为了：哪天有人按 docstring 去修（改成按数值归一键），
     * 这条测试会失败并迫使他回来读这段说明；目前它是"已知缺口"而不是"保证"。
     */
    @Test
    @DisplayName("test_number_style_misses_trailing_zero_variants")
    void test_number_style_misses_trailing_zero_variants() {
        Map<String, Object> result = Checks.checkNumberStyle(List.of("总价 7,780,000.00 元", "总价 7780000 元"));
        assertEquals("pass", result.get("status"));
    }

    /**
     * 【Java 移植新增】挡的是：Java 的 {@code \d} 默认只认 ASCII 数字，把全角数字串漏掉。
     *
     * <p>Python 的 {@code re} 对 str 是 Unicode 语义（{@code \d} 能匹配「０-９」），
     * 而 {@code check_number_style} 的正则本来就显式写了 {@code [0-9０-９]}；
     * 这条用例保证两侧口径一致：「１２３４」与「1234」是同一个数值的两种写法。
     */
    @Test
    @DisplayName("(Java 新增) 全角数字串与半角视为同一数值的两种写法")
    void test_number_style_unicode_digits_match_python() {
        Map<String, Object> result = Checks.checkNumberStyle(List.of("金额 １２３４ 元", "同一金额 1234 元"));
        assertEquals("warn", result.get("status"));
        assertTrue(String.valueOf(item(result, 0).get("detail")).contains("１２３４"),
                "detail 里应列出全角写法：" + item(result, 0).get("detail"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 答案数字可溯源（反幻觉）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：模型答案里凭空出现原文没有的数字（"编造"的机器校验）。
     *
     * <p>注意实现是按<b>数值</b>比较（Decimal 相等，与写法/末尾零无关），
     * 所以 {@code 7,780,000} 与 {@code ¥7,780,000.00} 视为同一个数——这也正是我们想要的。
     * （Java 的 {@code BigDecimal.equals} 是值+标度比较，实现里用 {@code stripTrailingZeros()}
     * 归一 + 数值比较来对齐 Python 语义。）
     */
    @Test
    @DisplayName("test_verify_numbers_in_source_pass_and_warn")
    void test_verify_numbers_in_source_pass_and_warn() {
        List<String> pages = List.of("合同总价 ¥7,780,000.00 元。");
        Map<String, Object> ok = Checks.verifyNumbersInSource("合同总价为 7,780,000 元。", pages);
        assertEquals("pass", ok.get("status"));
        assertEquals(List.of(), ok.get("items"));

        Map<String, Object> bad = Checks.verifyNumbersInSource("合同总价为 9,999,999 元。", pages);
        assertEquals("warn", bad.get("status"));
        assertTrue(String.valueOf(item(bad, 0).get("detail")).contains("9,999,999"),
                "detail 里应给出未找到的数字：" + item(bad, 0).get("detail"));
    }

    /** 挡的是：答案里没有数字时也给 pass（"没检查"必须显式是 skip）。 */
    @Test
    @DisplayName("test_verify_numbers_in_source_skips_answer_without_numbers")
    void test_verify_numbers_in_source_skips_answer_without_numbers() {
        assertEquals("skip",
                Checks.verifyNumbersInSource("无法判断。", List.of("任意原文")).get("status"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 输出契约
    // ══════════════════════════════════════════════════════════════════

    /**
     * 挡的是：检查结果的结构漂了，前端按 {@code status} 着色的那套跟着崩。
     *
     * <p>契约：{@code {name, status, detail, items}}，status 只能是 pass/warn/fail/skip。
     */
    @Test
    @DisplayName("test_check_document_output_contract")
    void test_check_document_output_contract() {
        List<Map<String, Object>> result = Checks.checkDocument(List.of("无金额无比例的一段文字。"));
        assertEquals(List.of("金额大小写互校", "同段比例合计", "数值写法一致性"),
                result.stream().map(c -> c.get("name")).toList());
        for (Map<String, Object> item : result) {
            assertTrue(item.keySet().containsAll(Set.of("name", "status", "detail", "items")),
                    "缺少契约字段：" + item.keySet());
            assertTrue(Set.of("pass", "warn", "fail", "skip").contains(item.get("status")),
                    "status 越界：" + item.get("status"));
        }
    }

    // ── 断言辅助 ────────────────────────────────────────────────────

    /**
     * 数值比较（不是 {@code BigDecimal.equals}）：Python 的 {@code Decimal ==} 是数值相等，
     * {@code Decimal("7780000.00") == Decimal("7780000")} 为真——用 equals 会因标度不同而假失败。
     */
    private static void assertNum(String expected, BigDecimal actual) {
        assertNotNull(actual, "期望 " + expected + "，实际是 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "期望 " + expected + "，实际 " + actual);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> checkResult, int index) {
        return (Map<String, Object>) ((List<?>) checkResult.get("items")).get(index);
    }
}
