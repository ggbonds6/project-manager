package com.pmgt.module.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** 项目分工保存请求。id 为空表示新增。 */
@Data
public class DivisionSaveRequest {

    private Long id;

    @NotNull(message = "项目不能为空")
    private Long projectId;

    /** 父级分工 id（空 = 顶层模块） */
    private Long parentId;

    @NotBlank(message = "模块名称不能为空")
    private String name;

    /** OWNER 甲方 / VENDOR 乙方 / BOTH 双方 */
    private String ownerSide;

    private String ownerName;
    private String vendorOwner;

    private LocalDate planDevDate;
    private LocalDate planTestDate;
    private LocalDate planOnlineDate;

    private Integer progress;
    private String status;
    private String remark;
    private Integer sortNo;
}
