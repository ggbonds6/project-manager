package com.pmgt.ai.module.clean;

import com.pmgt.ai.common.config.AiSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗器整体（{@link RuleBasedTextCleaner}）：<b>关闭时逐字不变</b>、开启时的产物与统计、
 * 纯函数（同输入同输出）。
 *
 * <p>这里钉的是"清洗阶段本身"的口径；每条规则的正反例在 {@code CleanRulesTest}。
 */
class TextCleanerTest {

    private static CleanContext context(String... pageTexts) {
        return context(0, pageTexts);
    }

    private static CleanContext context(int cleanRev, String... pageTexts) {
        List<CleanContext.PageInput> pages = new ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) {
            pages.add(new CleanContext.PageInput(i + 1, "text-layer", pageTexts[i], List.of()));
        }
        return new CleanContext("abc123", "测试.pdf", pageTexts.length, cleanRev, pages);
    }

    // ══════════════════════════════════════════════════════════════
    // 关闭 = 逐字不变（回归）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认关闭（ai.clean.enabled=false）：文本逐字不变、统计全 0、痕迹为空——与接入前完全一致")
    void disabledCleanerChangesNothing() {
        AiSettings settings = new AiSettings();
        assertFalse(settings.getClean().isEnabled(), "默认必须是关（升级不该悄悄改切片文本）");
        TextCleaner cleaner = new RuleBasedTextCleaner(settings);
        String raw = "某页眉\n　 正文   甲。\n\n\n\n正文乙 7，982，300.00 元。";

        CleanResult result = cleaner.cleanDocument(context(raw, "第二页正文。"));

        assertFalse(result.enabled());
        assertFalse(cleaner.enabled());
        assertEquals(raw, result.pages().get(0).safeAfter(), "关闭时页面文本必须逐字不变");
        assertEquals(result.pages().get(0).before(), result.pages().get(0).safeAfter());
        assertEquals(0, result.stats().removedChars());
        assertEquals(0, result.stats().removedLines());
        assertEquals(0.0, result.stats().removedCharsRatio());
        assertTrue(result.removed().isEmpty());
        assertTrue(result.normalized().isEmpty());
        assertTrue(result.rulesFired().isEmpty(), "关闭时不允许有任何规则命中记录");
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("ai.clean.enabled=false"), result.reason());
    }

    @Test
    @DisplayName("纯函数：同输入 + 同 clean_rev 跑两遍，逐字节相同（可缓存、可复算、可对比）")
    void cleanerIsPure() {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(3);
        settings.getClean().setEnabled(true);
        TextCleaner cleaner = new RuleBasedTextCleaner(settings);
        CleanContext context = context(3, "某页眉 1\n正文甲   乙。", "某页眉 2\n正文丙。");

        CleanResult first = cleaner.cleanDocument(context);
        CleanResult second = cleaner.cleanDocument(context);

        assertEquals(first.text(), second.text());
        assertEquals(first.removed().size(), second.removed().size());
        assertEquals(first.rulesFired(), second.rulesFired());
        assertEquals(3, first.cleanRev());
    }

    // ══════════════════════════════════════════════════════════════
    // 产物：摘要 ≤4000 字符、全文带前后对照
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("clean_diff 摘要 ≤4000 字符且带 quality 口径（removed_chars_ratio / rules_fired / 样本）")
    void summaryJsonIsBoundedAndCarriesQualityFields() {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(2);
        settings.getClean().setEnabled(true);
        TextCleaner cleaner = new RuleBasedTextCleaner(settings);

        String[] pages = new String[200];
        for (int i = 0; i < pages.length; i++) {
            // 每页正文用**互不相同的汉字**（否则 C1 会把"去数字后一样"的重复行当页眉/页脚删掉，
            // 那是 C1 的职责，不该混进"清洗质量口径"这条用例里）
            char unique = (char) ('\u4e00' + i);
            pages[i] = "某某项目招标文件 第 " + (i + 1) + " 页\n第 " + (i + 1) + " 页\n正文段落 "
                    + i + "：" + String.valueOf(unique).repeat(80) + "。";
        }
        CleanResult result = cleaner.cleanDocument(context(2, pages));

        String summary = RuleBasedTextCleaner.summaryJson(result);
        assertTrue(summary.length() <= 4000, "摘要必须 ≤4000 字符（主系统 DB 的口径）：" + summary.length());
        assertTrue(summary.contains("\"removed_chars_ratio\""), summary.substring(0, 200));
        assertTrue(summary.contains("\"rules_fired\""));
        assertTrue(summary.contains("\"removed_samples\""), "必须给被删行样本供人工复核");
        assertTrue(summary.contains("work/clean/"), "摘要要写明全文在哪（DB 只存摘要）");
        assertTrue(result.stats().removedCharsRatio() > 0);
        assertTrue(result.stats().pagesAllRemoved() == 0, "正文不该被整页删空");

        String full = RuleBasedTextCleaner.fullTraceJson(result);
        assertTrue(full.contains("\"before\"") && full.contains("\"after\""),
                "全文必须带前后对照（这是清洗唯一能自证'删对了'的东西）");
        assertTrue(full.length() > summary.length(), "全文应比摘要长");
    }

    @Test
    @DisplayName("关闭时摘要也如实：enabled=false + reason，不含伪造的 stats 增量")
    void summaryOfDisabledRunIsHonest() {
        CleanResult result = new RuleBasedTextCleaner(new AiSettings()).cleanDocument(context("正文。"));
        String summary = RuleBasedTextCleaner.summaryJson(result);
        assertTrue(summary.contains("\"enabled\":false"), summary);
        assertTrue(summary.contains("ai.clean.enabled=false"), summary);
        assertTrue(summary.contains("\"removed_lines\":0"), summary);
    }

    // ══════════════════════════════════════════════════════════════
    // 配置快照
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("规则清单为空 ⇒ 用规则自己的默认开关（C1~C7 开、C8 关）；未知 id 不静默当成开")
    void blankRuleListFallsBackToRuleDefaults() {
        AiSettings settings = new AiSettings();
        settings.getClean().setRules("  ");
        CleanOptions options = CleanOptions.of(settings);
        assertEquals(7, options.enabledRules().size(), options.enabledRules().toString());
        assertTrue(options.enabledRules().contains("c1"));
        assertFalse(options.enabledRules().contains("c8"), "水印默认关");

        settings.getClean().setRules("c1,zz");
        CleanOptions withUnknown = CleanOptions.of(settings);
        assertEquals(List.of("zz"), withUnknown.unknownRules());
        assertFalse(withUnknown.ruleEnabled(CleanRules.byId("c2")), "没列出来的规则一律不开");
    }

    @Test
    @DisplayName("清洗配置快照与 AiSettings 解耦：改了 settings 不影响已经建好的 cleaner（纯函数前提）")
    void optionsAreASnapshot() {
        AiSettings settings = new AiSettings();
        settings.getClean().setEnabled(true);
        TextCleaner cleaner = new RuleBasedTextCleaner(settings);
        settings.getClean().setEnabled(false);
        assertTrue(cleaner.enabled(), "快照一旦建好就不再随可变配置漂移");
        assertTrue(new RuleBasedTextCleaner(new AiSettings()).options().unknownRules().isEmpty(),
                "默认配置里没有未知规则 id（拼错会在启动期 WARN，不静默当成开）");
    }

    @Test
    @DisplayName("页面来源为空按已知路径处理（历史文档 source 可能为空，不能因此整份不清洗）")
    void blankSourceIsTreatedAsSupported() {
        assertTrue(CleanContext.supportedSource(null));
        assertTrue(CleanContext.supportedSource(""));
        assertTrue(CleanContext.supportedSource("text-layer"));
        assertTrue(CleanContext.supportedSource("platform"));
        assertFalse(CleanContext.supportedSource("docx"));
        assertTrue(CleanContext.SUPPORTED_SOURCES.contains("platform"));
    }
}
