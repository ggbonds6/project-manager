package com.pmgt.module.ai.client;

import java.util.List;
import java.util.Map;

/**
 * AI 服务响应体（{@code {code, data, detail}}）的读取工具。
 *
 * <p>为什么用 {@code Map} + 静态工具而不是为 AI 服务的每条响应写 DTO：
 * <ul>
 *   <li><b>唯一目的是适配</b>：AI 服务契约会演进（例如它自己都在加 {@code citations}），
 *       用 Map 读取时新增字段自动被忽略，不会因为「多了一个字段」就反序列化失败；
 *       DTO 反而要把每个字段都写全，漏一个就是运行期报错；</li>
 *   <li><b>字段缺失必须能表达「就是没有」</b>：这是本次实现的核心要求之一
 *       （{@code citations} 缺失时给空数组、不允许伪造），Map 的 {@code get} 天然如此；</li>
 *   <li>映射成本集中在本类与各 Service 的 {@code toXxx} 方法里，仍然只有一处可改。</li>
 * </ul>
 *
 * <p>所有取值方法都做类型容错：AI 服务把数字写成字符串（{@code "10"}）时不该炸。
 */
public final class AiJson {

    private AiJson() {
    }

    /** 取出 data 段；不是 Map（或为 null）时返回空 Map，调用方不必判空。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 取出列表段；不是 List 时返回空列表。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    /** 字符串取值；null 时返回空串（避免调用方到处判空）。 */
    public static String text(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 是否包含该键（用于区分「字段缺失」与「字段为空值」）。 */
    public static boolean has(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key);
    }

    /** 布尔取值：兼容 true / "true" / 1 / "1"。 */
    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        if (map == null || !map.containsKey(key)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        if (s.isEmpty()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    /** 整数取值：数字直取，字符串尝试解析，失败给默认值（AI 服务偶尔把整数写成字符串）。 */
    public static int intValue(Map<String, Object> map, String key, int fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 长整数取值（耗时毫秒这类可能超 int 的量）。 */
    public static long longValue(Map<String, Object> map, String key, long fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return (long) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 小数取值（0.0 而不是 null，便于直接塞进 VO 的 double 字段）。 */
    public static double doubleValue(Map<String, Object> map, String key, double fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 可空字符串：空串归一成 null（前端契约里「没有」统一用 null 表达）。 */
    public static String nullableText(Map<String, Object> map, String key) {
        String value = text(map, key);
        return value.isBlank() ? null : value;
    }

    /** 截断（留痕摘要、错误信息这类有列宽限制的字段用）。 */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
