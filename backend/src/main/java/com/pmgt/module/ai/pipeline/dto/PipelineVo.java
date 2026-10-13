package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流水线响应 VO 的公共基类：<b>snake_case 主名 + camelCase 兼容别名</b>。
 *
 * <h2>为什么需要"两个名字"</h2>
 * <p>契约（docs/RAG流水线设计与并发管理.md §4 与两侧冻结的字段表）要求 JSON 字段用
 * <b>snake_case 主名</b>（{@code run_id}/{@code stage_key}/{@code item_failed}…），
 * 因为调用方是 AI 服务、以及设计文档逐字写的就是这些名字；
 * 而主系统前端在本仓库里全部使用 <b>camelCase</b>。两侧都要读同一份响应，
 * 于是约定：<b>主名 snake_case，同时带上 camelCase 别名</b>——前端不必写字段映射表，
 * AI 侧也不必改名。
 *
 * <h2>为什么用 {@code @JsonAnyGetter} 而不是每个字段手写一个 getter</h2>
 * <p>手写别名 getter 的问题不是"多写几行"，而是<b>一定会漏</b>：将来给某个 VO 加一个字段，
 * 加了 {@code @JsonProperty("new_field")} 却忘了加 {@code newField()} 别名，
 * 编译期没有任何提示，线上表现为"前端这一列永远空白"。这里改成
 * "凡是用 {@code @JsonProperty} 声明了 snake_case 的字段，自动补一个 camelCase 别名"，
 * 规则落在基类一处，新增字段<b>自动就带上别名</b>。
 *
 * <p>实现代价与边界（写清楚，免得被当成魔法）：
 * <ul>
 *   <li>用反射读本对象（含父类）的私有字段，逐个把 {@code a_b} → {@code aB}；
 *       响应体很小、调用频率是"阶段边界级"（每任务几十次），不是热路径；</li>
 *   <li>只处理类自己的字段，不递归其他对象（嵌套 VO 各自也是 {@code PipelineVo} 子类）；</li>
 *   <li>反射失败<b>只丢别名</b>，绝不阻断主字段序列化；</li>
 *   <li>别名与主名不可能重名（一个含下划线、一个不含），因此不会互相覆盖。</li>
 * </ul>
 */
public abstract class PipelineVo {

    /** 由 {@code @JsonProperty} 的 snake_case 名字派生的 camelCase 别名集合。 */
    @JsonAnyGetter
    public Map<String, Object> camelCaseAliases() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Class<?> type = getClass(); type != null && type != PipelineVo.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                JsonProperty declared = field.getAnnotation(JsonProperty.class);
                if (declared == null || declared.value().indexOf('_') < 0) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.putIfAbsent(toCamel(declared.value()), field.get(this));
                } catch (Exception e) {
                    // 别名是"顺手的兼容层"：取不到就只给主名，不能因此让整个响应 500
                }
            }
        }
        return out;
    }

    /** {@code item_failed} → {@code itemFailed}。 */
    static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
