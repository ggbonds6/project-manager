package com.pmgt.ai.module.clean;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.store.StoredDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗规则的正反例（设计 §3.3.2 的 C1~C8，<b>逐条钉</b>）。
 *
 * <p>为什么逐条测：清洗是"删除类"操作，写错的代价是正文被删——而正文被删不会报错，
 * 只表现为"检索少召回"。所以每条规则都要有<b>正例</b>（该删的删了）与<b>反例</b>
 * （不该删的没删），尤其要钉住三件事：表格不被合并、页眉被剔除、条款换行不被合并。
 */
class CleanRulesTest {

    // ══════════════════════════════════════════════════════════════
    // C1 页眉/页脚
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1 正例：每页首行重复的页眉（≥50% 页）整行剔除；反例：正文中间的同一句子不动")
    void c1RemovesRepeatedHeaderButNotBodyLine() {
        CleanResult result = clean(options -> { }, pagesOf(
                "某某项目招标文件 第 1 页\n第一章 总则\n本项目采用公开招标方式。",
                "某某项目招标文件 第 2 页\n第二章 投标人资格\n投标人应具备相应资质。",
                "某某项目招标文件 第 3 页\n第三章 评标办法\n评标采用综合评分法。"));

        assertFalse(result.pages().get(0).safeAfter().contains("某某项目招标文件"),
                "页眉必须被剔除：" + result.pages().get(0).safeAfter());
        assertTrue(result.pages().get(1).safeAfter().contains("投标人应具备相应资质"));
        assertTrue(result.removed().stream().anyMatch(item -> "c1".equals(item.rule())),
                "被删的行必须留痕（供人工复核误删）：" + result.removed());
        assertTrue(result.firedByRule().getOrDefault("c1", 0) > 0);
    }

    @Test
    @DisplayName("C1 反例：只有 2 页时 <50% 页出现（每页各不同）不删；短行（<4 字）不参与判定")
    void c1DoesNotRemoveUniqueLinesOrShortLines() {
        CleanResult result = clean(options -> { }, pagesOf(
                "第 1 页\n甲方的义务如下。",
                "第 2 页\n乙方的义务如下。"));
        // 两页首行都不同（归一化去数字后分别为"第页甲方的义务如下。"与"第页乙方的义务如下。"）⇒ 不该删
        assertTrue(result.pages().get(0).safeAfter().contains("甲方的义务如下"));
        assertTrue(result.pages().get(1).safeAfter().contains("乙方的义务如下"));
        assertEquals(0, result.firedByRule().getOrDefault("c1", 0));

        CleanResult shortLines = clean(options -> { }, pagesOf("1\n正文甲", "1\n正文乙"));
        assertTrue(shortLines.pages().get(0).safeAfter().contains("正文甲"),
                "短行（1 字）不参与 C1 判定，且 C2 会按页码处理它");
    }

    // ══════════════════════════════════════════════════════════════
    // C2 页码行
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2 正例：四种页码写法都剔除；「共 Y 页」先抽成 total_pages 再删行")
    void c2RemovesPageNumbersAndKeepsTotalPages() {
        CleanResult result = clean(options -> { }, pagesOf(
                "第 3 页 共 12 页\n合同价款为一百万元。",
                "- 4 -\n付款方式为分期付款。"));
        assertEquals(12, result.stats().totalPages(), "「共 Y 页」必须抽成页级元数据再删行");
        assertFalse(result.pages().get(0).safeAfter().contains("第 3 页"));
        assertFalse(result.pages().get(1).safeAfter().contains("- 4 -"));
        assertTrue(result.pages().get(1).safeAfter().contains("付款方式为分期付款"));

        for (String page : List.of("7", "7 / 20", "Page 8 of 20", "第 9 页")) {
            CleanResult single = clean(options -> { }, pagesOf(page + "\n正文内容在这里。"));
            assertFalse(single.pages().get(0).safeAfter().contains(page),
                    "页码行必须剔除：" + page + " ⇒ " + single.pages().get(0).safeAfter());
        }
    }

    @Test
    @DisplayName("C2 反例：正文里带数字的行（含金额、条款号）一律不动")
    void c2KeepsTextWithNumbers() {
        String body = "合同金额为 7,982,300.00 元；3.2 付款方式见附件 2。";
        CleanResult result = clean(options -> { }, pagesOf(body + "\n上文完。"));
        assertTrue(result.pages().get(0).safeAfter().contains("7,982,300.00"));
        assertTrue(result.pages().get(0).safeAfter().contains("3.2 付款方式"));
    }

    // ══════════════════════════════════════════════════════════════
    // C3 空白归一
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3 正例：零宽字符删除、全角空格折半角、连续空格压缩、连续空行压成 1 行")
    void c3NormalizesWhitespace() {
        String raw = "甲方\u200B与\uFEFF乙方\u3000约定：  付款   期限为 30 日。\n\n\n\n乙方应按时付款。";
        CleanResult result = clean(options -> { }, pagesOf(raw));
        String after = result.pages().get(0).safeAfter();
        assertFalse(after.contains("\u200B"), "零宽字符必须删除");
        assertFalse(after.contains("\uFEFF"));
        assertTrue(after.contains("甲方与乙方 约定"), "全角空格折成半角：" + after);
        assertFalse(after.contains("   "), "连续 ≥3 空格必须压缩：" + after.replace(" ", "·"));
        assertFalse(after.contains("\n\n\n"), "连续空行压成 1 行：" + after.replace("\n", "\\n"));
        assertTrue(after.contains("乙方应按时付款。"));
        assertTrue(result.firedByRule().containsKey("c3"));
    }

    @Test
    @DisplayName("C3 反例：表格块内的连续空格<b>不压缩</b>（对齐不能毁）")
    void c3KeepsTableAlignment() {
        String table = "| 项目 |    金额 |\n| --- | --- |\n| 合计 |    100 |";
        CleanResult result = clean(options -> { }, pagesOf(table + "\n正文在下。"));
        String after = result.pages().get(0).safeAfter();
        assertTrue(after.contains("| 合计 |    100 |"), "表内对齐空格必须保留：" + after);
        assertTrue(result.stats().tableBlocks() >= 1, "表格块要被 C5 标记（C3 才能跳过它）");
    }

    // ══════════════════════════════════════════════════════════════
    // C4 断行合并 / 连字符
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C4 正例：行尾无标点则合并；英文连字符断词修复；反例：条款起始行强制断行")
    void c4MergesWrappedLinesButBreaksClauses() {
        CleanResult result = clean(options -> { }, pagesOf(
                "合同价款为人民\n币一百万元整。\n（一）付款方式为分期付款。\ninter-\nnational 标准。"));
        String after = result.pages().get(0).safeAfter();
        assertTrue(after.contains("合同价款为人民币一百万元整。"), "行尾无标点必须合并：" + after);
        assertTrue(after.contains("international 标准"), "连字符断词要去连字符合并：" + after);
        assertTrue(after.contains("（一）付款方式为分期付款。"), after);
        assertEquals(1, after.lines().filter(line -> line.contains("（一）")).count(),
                "条款起始行必须独占一行（不被并进上一行）");
    }

    @Test
    @DisplayName("C4 反例：行尾有句末标点不合并；空行是段落边界；表格行不参与合并")
    void c4KeepsSentenceEndsParagraphsAndTables() {
        CleanResult result = clean(options -> { }, pagesOf(
                "第一句已经结束。\n第二句另起。\n\n新段落第一行\n新段落第二行\n| a | b |\n| --- | --- |\n| c | d |"));
        String after = result.pages().get(0).safeAfter();
        assertTrue(after.contains("第一句已经结束。\n第二句另起。"), "有标点不合并：" + after);
        assertTrue(after.contains("\n\n新段落第一行"), "空行仍是段落边界：" + after);
        assertTrue(after.contains("| a | b |\n| --- | --- |\n| c | d |"), "表格行必须原样（不被合并）：" + after);
    }

    // ══════════════════════════════════════════════════════════════
    // C5 表格标记
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C5 正例：markdown 竖线表被标记为表格块（只标记，不改写文本）")
    void c5MarksPipeTable() {
        CleanResult result = clean(options -> { }, pagesOf(
                "| 序号 | 名称 |\n| --- | --- |\n| 1 | 甲方 |\n| 2 | 乙方 |"));
        assertEquals(1, result.stats().tableBlocks());
        assertEquals(4, result.pages().get(0).tables().get(0).rows());
        assertEquals(result.pages().get(0).before().strip(), result.pages().get(0).safeAfter().strip(),
                "C5 只标记不改写文本");
    }

    @Test
    @DisplayName("C5 反例：没有竖线结构的正文（含单竖线、短横线）不判为表格")
    void c5DoesNotMarkPlainText() {
        CleanResult result = clean(options -> { }, pagesOf(
                "甲方 | 乙方 是双方简称，不是表格。\n付款方式：分期。\n验收标准：国标。"));
        assertEquals(0, result.stats().tableBlocks(), "表格判据必须保守（宁可少标记）");
    }

    // ══════════════════════════════════════════════════════════════
    // C6 段级去重
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C6 默认只标记不删：重复段落保留并记 duplicate_of；dedup-drop=true 才真删")
    void c6MarksByDefaultAndDropsWhenConfigured() {
        String paragraph = "本合同一式肆份，甲乙双方各执贰份，具有同等法律效力。";
        // 重复段刻意放在"非页首/页末"位置：否则 C1 会把它当页眉删掉（那是 C1 的职责，不是 C6 的）
        String page1 = "第一节 说明\n\n" + paragraph + "\n\n第一段正文。";
        String page2 = "第二节 说明\n\n" + paragraph + "\n\n第二段正文。";
        CleanResult marked = clean(options -> { }, pagesOf(page1, page2));
        assertEquals(1, marked.stats().duplicateParagraphs(), marked.stats().toString());
        assertTrue(marked.pages().get(1).safeAfter().contains(paragraph),
                "默认只标记（重复段落可能承载真实事实，不能悄悄删）");
        assertTrue(marked.firedByRule().containsKey("c6"));

        CleanResult dropped = clean(options -> options.setDedupDrop(true), pagesOf(page1, page2));
        assertFalse(dropped.pages().get(1).safeAfter().contains(paragraph), "开启后应真删");
        assertTrue(dropped.pages().get(1).safeAfter().contains("第二段正文"));
    }

    @Test
    @DisplayName("C6 反例：短于 20 字的重复行不算重复段（页眉式短行不在这里处理）")
    void c6IgnoresShortParagraphs() {
        CleanResult result = clean(options -> { }, pagesOf("甲乙双方。\n正文一。", "甲乙双方。\n正文二。"));
        assertEquals(0, result.stats().duplicateParagraphs());
    }

    // ══════════════════════════════════════════════════════════════
    // C7 编码归一
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C7 正例：全角字母数字与小数点半角化；句读类全角标点（，：）按设计保留；替代字符只统计不删")
    void c7NormalizesFullWidthAscii() {
        CleanResult result = clean(options -> { }, pagesOf("中标金额：人民币 ７，９８２，３００．００ 元。\n有\uFFFD个乱码。"));
        String after = result.pages().get(0).safeAfter();
        assertTrue(after.contains("7，982，300.00"),
                "全角数字＋全角小数点必须半角化（千分位逗号按设计保留）：" + after);
        assertEquals(1, result.stats().replacementChars(), "替代字符要计数（不静默删）");
        assertTrue(after.contains("：") && after.contains("，"), "句读类全角标点保持原样：" + after);
        assertTrue(result.normalized().stream().anyMatch(item -> "c7".equals(item.rule())),
                "归一必须单列在 normalized[]（它不是删除）");
    }

    @Test
    @DisplayName("C7 反例：本来就是半角的文本不动（不产生 normalized 记录）")
    void c7LeavesHalfWidthTextAlone() {
        CleanResult result = clean(options -> { }, pagesOf("金额 1234.56 元, 无全角字符。"));
        assertEquals(0, result.normalized().size(), result.normalized().toString());
    }

    // ══════════════════════════════════════════════════════════════
    // C8 水印（默认关）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C8 默认关：即使配置里写了 c8，没有词表也不删任何行；给了词表才按白名单删")
    void c8IsOffByDefaultAndNeedsPatterns() {
        CleanResult withoutPatterns = clean(options -> options.setRules("c1,c2,c3,c4,c5,c6,c7,c8"),
                pagesOf("内部资料 严禁外传\n正文甲。"));
        assertTrue(withoutPatterns.pages().get(0).safeAfter().contains("内部资料"),
                "词表为空 ⇒ C8 不生效（默认关的实质）");

        CleanResult withPatterns = clean(options -> {
            options.setRules("c1,c2,c3,c4,c5,c6,c7,c8");
            options.setWatermarkPatterns("严禁外传,内部资料");
        }, pagesOf("内部资料 严禁外传\n正文甲。"));
        assertFalse(withPatterns.pages().get(0).safeAfter().contains("内部资料"),
                "命中白名单的整行要删：" + withPatterns.pages().get(0).safeAfter());
        assertTrue(withPatterns.pages().get(0).safeAfter().contains("正文甲"));
        assertTrue(withPatterns.removed().stream().anyMatch(item -> "c8".equals(item.rule())));
    }

    // ══════════════════════════════════════════════════════════════
    // 规则可开关 / 只作用于两路来源
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("规则可开关：清单里只留 c2 ⇒ c1 不再剔除页眉（配置驱动，不是写死）")
    void rulesAreIndependentlySwitchable() {
        CleanResult all = clean(options -> { }, pagesOf(
                "某某项目招标文件\n正文甲。", "某某项目招标文件\n正文乙。"));
        assertFalse(all.pages().get(0).safeAfter().contains("某某项目招标文件"), "默认清单里 c1 是开的");

        CleanResult onlyC2 = clean(options -> options.setRules("c2"), pagesOf(
                "某某项目招标文件\n3\n正文甲。", "某某项目招标文件\n4\n正文乙。"));
        assertTrue(onlyC2.pages().get(0).safeAfter().contains("某某项目招标文件"), "c1 关掉后页眉必须保留");
        assertFalse(onlyC2.pages().get(0).safeAfter().contains("3\n"), "c2 仍然生效");
    }

    @Test
    @DisplayName("规则只作用于文本层与 OCR markdown 两路：未知来源的页原样传下去并计入 pages_skipped")
    void rulesOnlyApplyToSupportedSources() {
        CleanContext context = new CleanContext("doc1", "a.pdf", 2, 1, List.of(
                new CleanContext.PageInput(1, "text-layer", "某某文件\n3\n正文甲。", List.of()),
                new CleanContext.PageInput(2, "docx", "某某文件\n3\n正文乙。", List.of())));
        RuleBasedTextCleaner cleaner = new RuleBasedTextCleaner(
                new CleanOptions(true, java.util.Set.of("c1", "c2"), 0.5, 4, false, List.of()));
        CleanResult result = cleaner.cleanDocument(context);

        assertEquals("正文甲。", result.pages().get(0).safeAfter(),
                "文本层：页眉（c1）与页码行（c2）都被剔除");
        assertTrue(result.pages().get(1).safeAfter().contains("某某文件"),
                "未知来源（docx）原样保留：" + result.pages().get(1).safeAfter());
        assertTrue(result.pages().get(1).safeAfter().contains("3"));
        assertEquals(1, result.stats().pagesSkipped());
        assertEquals(1, result.stats().pagesCleaned());
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    /** 构造一份"参与清洗"的文档（来源 text-layer）。 */
    static CleanContext cleanContext(String... pageTexts) {
        List<CleanContext.PageInput> pages = new ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) {
            pages.add(new CleanContext.PageInput(i + 1, "text-layer", pageTexts[i], List.of()));
        }
        return new CleanContext("abc123", "测试.pdf", pageTexts.length, 1, pages);
    }

    static CleanContext pagesOf(String... pageTexts) {
        return cleanContext(pageTexts);
    }

    static CleanResult clean(java.util.function.Consumer<AiSettings.Clean> customizer, CleanContext context) {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(1);
        settings.getClean().setEnabled(true);
        customizer.accept(settings.getClean());
        return new RuleBasedTextCleaner(settings).cleanDocument(context);
    }

    /** 顺带钉一个易被忽略的口径：{@code BlockRef} 只有 label+bbox，C5 不依赖它也能识别竖线表。 */
    static StoredDoc.BlockRef tableBlock() {
        return new StoredDoc.BlockRef("table", new double[] {0, 0, 100, 100});
    }

    @Test
    @DisplayName("C1/C2/C8 不碰表格块内的问题行；C7 对表格行也生效（编码归一不破坏结构）")
    void tableLinesAreExcludedFromRemovalRulesButNormalized() {
        // ⚠️ 表格保护来自 C5 的标记：关掉 C5 等于同时关掉"表格不被 C3/C4/C8 动"的保护（见类注释）
        CleanResult result = clean(options -> {
            options.setRules("c1,c2,c5,c7,c8");
            options.setWatermarkPatterns("内部资料");
        }, pagesOf("| 内部资料 | １２３ |\n| --- | --- |\n| 内部资料 | 456 |", "内部资料\n正文甲。"));
        String after = result.pages().get(0).safeAfter();
        assertTrue(after.contains("内部资料"), "表格内的行不被 C8 删：" + after);
        assertTrue(after.contains("| 123 |"), "表格内的编码归一仍生效：" + after);
        assertNotEquals(0, result.stats().tableBlocks());
    }
}
