package com.pmgt.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("phase_tpl")
public class PhaseTpl extends BaseEntity {

    private String projectType;
    private String name;
    private Integer builtin;
    private Integer isDefault;
    private Integer enabled;
    private Integer sortNo;
    private String remark;
}
