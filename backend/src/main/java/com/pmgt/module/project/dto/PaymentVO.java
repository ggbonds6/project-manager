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
        return vo;
    }
}
