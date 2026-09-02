package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment")
public class Payment extends BaseEntity {

    private Long projectId;
    private String nodeCode;
    private String nodeName;
    private String conditionDesc;
    private BigDecimal planAmount;
    private LocalDate planDate;
    private BigDecimal paidAmount;
    private LocalDate paidDate;
    private String status;
    private String remark;
}
