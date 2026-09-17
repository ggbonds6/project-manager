package com.pmgt.module.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PaymentSaveRequest {

    @NotNull(message = "所属项目不能为空")
    private Long projectId;

    /** 归属合同 id；为空时后端取该(子)项目及其父级共享合同 */
    private Long contractId;

    @NotBlank(message = "付款节点不能为空")
    private String nodeCode;

    private String nodeName;
    private String conditionDesc;
    private BigDecimal planAmount;
    private LocalDate planDate;
    private BigDecimal paidAmount;
    private LocalDate paidDate;
    private String status;
    private String remark;

    /** 付款方式(字典 PAY_METHOD) */
    private String payMethod;
    /** 经办人 */
    private String handler;
    /** 发票号 */
    private String invoiceNo;
    /** 记账凭证号 / 报销单号 */
    private String voucherNo;
    /** 收款账户快照（新增时前端默认从合同带入） */
    private String payeeName;
    private String payeeBank;
    private String payeeAccount;
}
