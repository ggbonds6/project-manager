package com.pmgt.ai.module.clean;

import com.pmgt.ai.common.config.AiSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 清洗配置的<b>不可变快照</b>（{@code ai.clean.*} 绑定后的形态）。
 *
 * <p>为什么要把 {@link AiSettings.Clean} 再包一层：清洗规则是<b>纯函数</b>，
 * 而 {@code AiSettings} 是可变的 Spring 绑定对象——把可变对象传进纯函数，
 * "同输入同输出"这条不变量就靠不住了（绑定对象随时可能被改）。
 * 这里在构造期把值抄一份，规则只读快照。
 *
 * <p><b>启用集合的口径（唯一，别猜）</b>：
 * <ul>
 *   <li>{@code ai.clean.rules} 非空 ⇒ 完全按它（未列出的规则<b>一律不开</b>，包括 C1）；</li>
 *   <li>{@code ai.clean.rules} 为空 ⇒ 用每条规则自己的 {@link CleanRule#defaultEnabled()}（即 C1~C7 开、C8 关）。</li>
 * </ul>
 * 未知 id 只 WARN、不静默当成"开"（拼错 {@code c4} 的后果是"规则没生效"，必须看得见）。
 */
public record CleanOptions(boolean enabled,
                           Set<String> enabledRules,
                           double headerFooterRatio,
                           int headerFooterMinChars,
                           boolean dedupDrop,
                           List<String> watermarkPatterns) {

    /** 没有任何规则可选时用它"什么都不删"（{@code enabled=false} 的等价形态，测试与降级用）。 */
    public static CleanOptions disabled() {
        return new CleanOptions(false, Set.of(), 0.5, 4, false, List.of());
    }

    public static CleanOptions of(AiSettings settings) {
        AiSettings.Clean clean = settings == null ? new AiSettings.Clean() : settings.getClean();
        Set<String> rules = parseRules(clean.getRules());
        List<String> patterns = new ArrayList<>();
        for (String item : splitList(clean.getWatermarkPatterns())) {
            patterns.add(item);
        }
        return new CleanOptions(clean.isEnabled(), rules, clean.getHeaderFooterRatio(),
                Math.max(1, clean.getHeaderFooterMinChars()), clean.isDedupDrop(), List.copyOf(patterns));
    }

    /** 配置里的规则清单；空 ⇒ 用规则自己的默认开关。 */
    private static Set<String> parseRules(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            for (CleanRule rule : CleanRules.ALL) {
                if (rule.defaultEnabled()) {
                    out.add(rule.id());
                }
            }
            return out;
        }
        for (String item : splitList(raw)) {
            out.add(item.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw.split(",")) {
            String value = item.strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /** 这条规则本次是否生效（开关 + 清单两个条件都满足才算）。 */
    public boolean ruleEnabled(CleanRule rule) {
        return enabled && enabledRules.contains(rule.id());
    }

    /** 配置里出现了未知规则 id 吗（启动自检与产地备注用）。 */
    public List<String> unknownRules() {
        List<String> out = new ArrayList<>();
        for (String id : enabledRules) {
            boolean known = false;
            for (CleanRule rule : CleanRules.ALL) {
                if (rule.id().equals(id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                out.add(id);
            }
        }
        return out;
    }

    /** 规则 id → 人读标签（产物与自检里用，避免只出现 c1/c2 这种代号）。 */
    public List<String> enabledRuleLabels() {
        List<String> out = new ArrayList<>();
        for (CleanRule rule : CleanRules.ALL) {
            if (ruleEnabled(rule)) {
                out.add(rule.id() + "=" + rule.label());
            }
        }
        return out;
    }

    /** 水印正则（配置为空即空列表 ⇒ C8 不生效）。非法正则如实抛出，由调用方暴露。 */
    public List<Pattern> compiledWatermarks() {
        List<Pattern> out = new ArrayList<>();
        for (String item : watermarkPatterns) {
            out.add(Pattern.compile(item));
        }
        return out;
    }
}
