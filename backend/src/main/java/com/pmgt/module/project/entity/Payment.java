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

    // ── 付款过程信息（V11）：资金情况以"付款"为主线，这些是付款当时的过程留痕 ──
    /** 付款方式(字典 PAY_METHOD) */
    private String payMethod;
    /** 经办人 */
    private String handler;
    /** 发票号（可多张，此处记主要发票号） */
    private String invoiceNo;
    /** 记账凭证号 / 报销单号 */
    private String voucherNo;
    /** 收款户名（快照，付款当时核对用） */
    private String payeeName;
    /** 收款开户行（快照） */
    private String payeeBank;
    /** 收款账号（快照） */
    private String payeeAccount;
}
