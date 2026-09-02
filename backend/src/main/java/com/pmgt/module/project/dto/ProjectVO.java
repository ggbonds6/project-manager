package com.pmgt.module.project.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目列表项（含计算字段：当前阶段、整体进度、累计实付）。
 */
@Data
public class ProjectVO {

    private Long id;
    private String code;
    private String name;
    private String type;
    private String status;
    private String ownerUnit;
    private Long ownerDeptId;
    private String ownerDeptName;
    private Long managerUserId;
    private String managerName;
    private String vendorName;
    private BigDecimal budgetAmount;
    private BigDecimal contractAmount;
    private BigDecimal paidAmount;
    private LocalDate approveDate;
    private LocalDate planFinishDate;
    private LocalDate actualFinishDate;
    private String currentPhaseName;
    private Integer overallProgress;
    private LocalDateTime updateTime;
}
