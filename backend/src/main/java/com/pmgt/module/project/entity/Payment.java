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
    /** 归属合同 id（共享合同时多个项目共享同一合同） */
    private Long contractId;
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
