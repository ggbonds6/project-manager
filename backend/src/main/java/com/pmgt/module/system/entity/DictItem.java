package com.pmgt.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dict_item")
public class DictItem extends BaseEntity {

    private String dictType;
    private String code;
    private String name;
    private Integer sortNo;
}
