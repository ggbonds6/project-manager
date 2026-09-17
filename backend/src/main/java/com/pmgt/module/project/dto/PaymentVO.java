package com.pmgt.module.project.dto;

import com.pmgt.module.project.entity.Payment;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 付款记录展示项（含状态文字/计算金额）。
 */
@Data
public class PaymentVO {

    private Long id;
    private Long projectId;
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
    private LocalDateTime updateTime;

    /** 付款过程信息（V11） */
    private String payMethod;
    private String handler;
    private String invoiceNo;
    private String voucherNo;
    private String payeeName;
    private String payeeBank;
    private String payeeAccount;

    public static PaymentVO from(Payment p) {
        PaymentVO vo = new PaymentVO();
        vo.setId(p.getId());
        vo.setProjectId(p.getProjectId());
        vo.setContractId(p.getContractId());
        vo.setNodeCode(p.getNodeCode());
        vo.setNodeName(p.getNodeName());
        vo.setConditionDesc(p.getConditionDesc());
        vo.setPlanAmount(p.getPlanAmount());
        vo.setPlanDate(p.getPlanDate());
        vo.setPaidAmount(p.getPaidAmount() == null ? BigDecimal.ZERO : p.getPaidAmount());
        vo.setPaidDate(p.getPaidDate());
        vo.setStatus(p.getStatus());
        vo.setRemark(p.getRemark());
        vo.setUpdateTime(p.getUpdateTime());
        vo.setPayMethod(p.getPayMethod());
        vo.setHandler(p.getHandler());
        vo.setInvoiceNo(p.getInvoiceNo());
        vo.setVoucherNo(p.getVoucherNo());
        vo.setPayeeName(p.getPayeeName());
        vo.setPayeeBank(p.getPayeeBank());
        vo.setPayeeAccount(p.getPayeeAccount());
        return vo;
    }
}
