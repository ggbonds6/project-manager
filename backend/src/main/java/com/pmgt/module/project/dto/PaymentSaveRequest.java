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
}
