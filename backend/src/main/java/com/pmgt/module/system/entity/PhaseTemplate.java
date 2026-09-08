package com.pmgt.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("phase_template")
public class PhaseTemplate extends BaseEntity {

    private Long tplId;
    private String projectType;
    private String phaseName;
    private Integer sortNo;
    private Integer weight;
    private String payNode;
    private String description;
    private String attachTypeHints;
    private Integer skipable;
    /** 阶段说明：本阶段要做什么（Markdown/长文本） */
    private String guide;
    /** 关键材料/产出物清单 */
    private String keyMaterials;
}
