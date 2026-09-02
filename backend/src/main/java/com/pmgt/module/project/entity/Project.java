package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    private String code;
    private String name;
    private String type;
    private String status;
    private String ownerUnit;
    private Long ownerDeptId;
    private Long managerUserId;
    private String memberIds;
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
    private LocalDate approveDate;
    private LocalDate planStartDate;
    private LocalDate planFinishDate;
    private LocalDate actualFinishDate;
    private String contentSummary;
    private String projectSource;
    private String remark;
    private Long createBy;
}
