package com.pmgt.module.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建/编辑项目基本信息。type 必填；code 为空时后端自动生成。
 */
@Data
public class ProjectSaveRequest {

    private String code;

    @NotBlank(message = "项目名称不能为空")
    private String name;

    @NotBlank(message = "项目类型不能为空")
    private String type;

    private String status;

    private String ownerUnit;
    private Long managerUserId;
    private List<Long> memberIds;
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
    private String contentSummary;
    private String projectSource;
    private String remark;

    /** 父(总)项目 id；为空表示顶层项目 */
    private Long parentId;
}
