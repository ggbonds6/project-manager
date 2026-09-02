package com.pmgt.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("phase_template")
public class PhaseTemplate extends BaseEntity {

    private String projectType;
    private String phaseName;
    private Integer sortNo;
    private Integer weight;
    private String payNode;
    private String description;
    private String attachTypeHints;
    private Integer skipable;
}
