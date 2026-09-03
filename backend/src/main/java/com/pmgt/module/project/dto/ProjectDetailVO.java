package com.pmgt.module.project.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目详情聚合：基本信息 + 计算字段 + 阶段列表。
 */
@Data
public class ProjectDetailVO {

    private Long id;
    private String code;
    private String name;
    private String type;
    private String status;
    private String ownerUnit;
    /** 父(总)项目 */
    private Long parentId;
    private String parentName;
    /** 子项目数量（>0=总项目容器，无自身流程） */
    private Integer childCount;
    private Long managerUserId;
    private String managerName;
    private List<Long> memberIds;
    private List<String> memberNames;
    private String vendorName;
    private String vendorContact;
    private String approveNo;
    private BigDecimal budgetAmount;
    private String fundSource;
    private String bidType;
    private BigDecimal bidAmount;
    private String contractNo;
    private BigDecimal contractAmount;
    private BigDecimal changeAmount;
    private BigDecimal contractTotal;
    private LocalDate approveDate;
    private LocalDate planStartDate;
    private LocalDate planFinishDate;
    private LocalDate actualFinishDate;
    private String contentSummary;
    private String projectSource;
    private String remark;

    private String currentPhaseName;
    private Integer overallProgress;

    private List<PhaseVO> phases;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
