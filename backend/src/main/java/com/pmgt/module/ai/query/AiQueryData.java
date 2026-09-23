package com.pmgt.module.ai.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受控查询的 {@code data} 段（§11.4，字段名与大小写<b>照抄契约</b>）：
 *
 * <pre>
 * { "rows": [], "unit": "个", "caliber": "口径说明", "data_time": "2026-09-23 16:40:00",
 *   "scope": "项目 12（含 3 个子项目）" }
 * </pre>
 *
 * <p>{@code data_time} 是 snake_case：RPC 这一侧（主系统 ↔ AI 服务）契约整体是 snake_case
 * （{@code biz_query} / {@code scope_token} / {@code doc_ids}），与面向浏览器的 §9 camelCase
 * 契约是两套，不要"顺手统一"成 camelCase。
 *
 * <p>{@code caliber} / {@code data_time} / {@code scope} 是<b>硬要求</b>（§11.4）：
 * 模型必须能原话转述，否则用户拿到的数字无法审计。所以三者一律非空。
 */
@Data
public class AiQueryData {

    /** 空结果时的固定文案（§11.6 原话；AI 侧也用它判定"没数据"而不是"调用失败"）。 */
    public static final String EMPTY_MESSAGE = "该范围内没有匹配数据";

    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 单位（"个" / "元"）；纯枚举型结果给"个"。 */
    private String unit;

    /** 口径说明（含税/是否含子项目/按立项年度…）。 */
    private String caliber;

    /** 数据时间点（{@code yyyy-MM-dd HH:mm:ss}）。 */
    @JsonProperty("data_time")
    private String dataTime;

    /** 作用域说明。 */
    private String scope;

    /**
     * 空结果提示：只在 {@code rows} 为空时出现（{@code NON_NULL} 保证有数据时不出现该字段）。
     *
     * <p>契约 §11.4 没写这个字段，加它是为了 §11.6 的要求"空结果要能让模型区分没数据与调用失败"：
     * 对 200 响应来说，唯一能承载这句话的地方就是这里（HTTP 状态与 {@code code} 都表达"成功"）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String message;

    /** 组装一份结果；行数为空时自动补 {@link #EMPTY_MESSAGE}。 */
    public static AiQueryData of(List<Map<String, Object>> rows, String unit, String caliber,
                                 String dataTime, String scope) {
        AiQueryData data = new AiQueryData();
        data.rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        data.unit = unit;
        data.caliber = caliber;
        data.dataTime = dataTime;
        data.scope = scope;
        if (data.rows.isEmpty()) {
            data.message = EMPTY_MESSAGE;
        }
        return data;
    }
}
