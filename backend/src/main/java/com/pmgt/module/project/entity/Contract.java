package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 合同：可被一个或多个(子)项目引用（project.contract_id 指向同一 contract 即共享）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract")
public class Contract extends BaseEntity {

    private String name;
    private String contractNo;
    private String vendorName;
    private String vendorContact;
    private String bidType;
    private BigDecimal bidAmount;
    private BigDecimal contractAmount;
    private BigDecimal changeAmount;
    private BigDecimal planAmount;
    private String scopeRemark;
    private String remark;
}
