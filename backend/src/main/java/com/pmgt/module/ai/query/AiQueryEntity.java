package com.pmgt.module.ai.query;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 四个受控查询实体及其<b>结构化字段白名单</b>（§11.3 / §11.4）。
 *
 * <h2>为什么字段清单要写死在代码里</h2>
 * <p>契约明确"<b>不收 SQL / 表达式 / 字段名拼接</b>"。白名单是这句话唯一的落地方式：
 * <ul>
 *   <li>请求里出现清单外的键 → 400（不是忽略），并在消息里列出该 entity 支持的字段 + 示例——
 *       §11.6 要求"错误必须教会模型"，模型看到"支持哪些字段"才能自己改对；</li>
 *   <li>每个字段的取值也收窄（枚举/整数），使"模型编出来的值"同样能被指出来；</li>
 *   <li>这份清单同时是<b>给模型看的说明书</b>：{@link #supportHint()} 直接就是 400 的原文，
 *       不存在"文档写一套、代码认一套"的漂移。</li>
 * </ul>
 *
 * <p>枚举取值与既有接口保持一致（{@code ProjectService} 的项目状态/类型、
 * {@code PaymentController} 的付款节点/状态），不另立一套。
 */
public enum AiQueryEntity {

    PROJECTS("projects",
            fields(
                    f("projectId", "整数", "项目 id；只能查本次授权范围内的项目", null),
                    f("name", "字符串", "项目名称，模糊匹配", null),
                    f("status", "字符串", "项目状态", List.of("RUN", "DONE", "PAUSE", "STOP")),
                    f("type", "字符串", "项目类型", List.of("HW", "SW")),
                    f("year", "整数", "立项年度（按 approve_date 的年份）", null)
            ),
            "{\"filters\":{\"status\":\"RUN\",\"year\":2026},\"limit\":20}"),

    CONTRACTS("contracts",
            fields(
                    f("projectId", "整数", "项目 id；只返回该项目（含其父级总项目共享）的合同", null),
                    f("vendorName", "字符串", "供应商（乙方）名称，模糊匹配", null)
            ),
            "{\"filters\":{\"projectId\":12},\"limit\":20}"),

    PAYMENTS("payments",
            fields(
                    f("projectId", "整数", "项目 id；只返回该项目及其可见合同上的付款", null),
                    f("nodeCode", "字符串", "付款节点编码", List.of("PREPAY", "ARRIVAL", "FIRST_ACCEPT",
                            "FINAL_ACCEPT", "WARRANTY")),
                    f("status", "字符串", "付款状态", List.of("UNPAID", "PART", "PAID"))
            ),
            "{\"filters\":{\"projectId\":12,\"status\":\"PAID\"},\"limit\":20}"),

    STATS("stats",
            fields(
                    f("projectId", "整数", "项目 id；只统计该项目", null),
                    required(f("kind", "字符串", "统计口径（必填）", List.of("phase_attachment_count",
                            "type_distribution", "year_amount")))
            ),
            "{\"filters\":{\"projectId\":12,\"kind\":\"phase_attachment_count\"},\"limit\":20}");

    /** 统计口径：各阶段附件数（§11.4 明确点名的那一个）。 */
    public static final String KIND_PHASE_ATTACHMENT_COUNT = "phase_attachment_count";
    /** 统计口径：项目类型分布。 */
    public static final String KIND_TYPE_DISTRIBUTION = "type_distribution";
    /** 统计口径：年度资金。 */
    public static final String KIND_YEAR_AMOUNT = "year_amount";

    private final String key;
    private final Map<String, FieldSpec> fields;
    private final String example;

    AiQueryEntity(String key, Map<String, FieldSpec> fields, String example) {
        this.key = key;
        this.fields = fields;
        this.example = example;
    }

    public String key() {
        return key;
    }

    /** 字段白名单（保持声明顺序，便于错误消息可读）。 */
    public Map<String, FieldSpec> fields() {
        return fields;
    }

    public boolean supports(String field) {
        return field != null && fields.containsKey(field);
    }

    /**
     * 400 的完整提示：列出支持字段（含类型/可选值/必填）+ 一个可直接照抄的示例。
     *
     * <p>刻意把示例也带上：模型最需要的不是"错在哪"，而是"改成什么能过"。
     */
    public String supportHint() {
        String list = fields.values().stream()
                .map(FieldSpec::describe)
                .collect(Collectors.joining("、"));
        return "entity=" + key + " 支持的查询字段：" + list + "；示例：" + example;
    }

    /** 全部实体名（未知 entity 的 400 用）。 */
    public static String allKeys() {
        return Arrays.stream(values()).map(AiQueryEntity::key).collect(Collectors.joining(" / "));
    }

    /** 由路径变量解析实体；未知/为空返回 empty（调用方报 400）。 */
    public static Optional<AiQueryEntity> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (AiQueryEntity e : values()) {
            if (e.key.equals(key)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    // ── 字段规格构造小工具（让上面的清单一眼能读） ──────────────────

    private static FieldSpec f(String name, String type, String note, List<String> allowedValues) {
        return new FieldSpec(name, type, false, allowedValues, note);
    }

    private static FieldSpec required(FieldSpec spec) {
        return new FieldSpec(spec.name(), spec.type(), true, spec.allowedValues(), spec.note());
    }

    private static Map<String, FieldSpec> fields(FieldSpec... specs) {
        Map<String, FieldSpec> map = new LinkedHashMap<>();
        for (FieldSpec s : specs) {
            map.put(s.name(), s);
        }
        // 不能用 Map.copyOf：它不保证迭代顺序，而字段清单的展示顺序就是错误消息的可读性
        return Collections.unmodifiableMap(map);
    }

    /**
     * 一个可接受字段的规格。
     *
     * @param name          字段名（请求里的键）
     * @param type          展示用类型：整数 / 字符串
     * @param required      是否必填
     * @param allowedValues 枚举取值（null 表示任意合法值）
     * @param note          给模型看的一句说明
     */
    public record FieldSpec(String name, String type, boolean required, List<String> allowedValues, String note) {

        public boolean isEnum() {
            return allowedValues != null && !allowedValues.isEmpty();
        }

        /** 错误消息里的"字段（类型，说明，可选值，必填）"一段。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(name).append("（").append(type);
            if (note != null && !note.isBlank()) {
                sb.append("，").append(note);
            }
            if (isEnum()) {
                sb.append("，可选值：").append(String.join(" / ", allowedValues));
            }
            if (required) {
                sb.append("，必填");
            } else {
                sb.append("，可选");
            }
            return sb.append("）").toString();
        }
    }
}
