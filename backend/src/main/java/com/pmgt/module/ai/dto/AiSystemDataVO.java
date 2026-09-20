package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #9 的「来自系统数据」条目。
 *
 * <p>P0 <b>恒为空数组</b>：受控查询工具（预算/付款/统计）属 P2，见方案 §5 与 §8.2。
 * 保留该字段是为了让前端契约现在就定型，P2 落地时不必改前端。
 */
@Data
public class AiSystemDataVO {

    private String label;
    private String value;
    private String unit;
    /** 口径说明。 */
    private String caliber;
    /** 数据时间点。 */
    private String dataTime;
    /** 作用域说明。 */
    private String scope;
}
