package com.pmgt.module.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ContractSaveRequest {

    @NotBlank(message = "合同名称不能为空")
    private String name;

    private String contractNo;
    private String vendorName;
    private String vendorContact;
    private String bidType;
    private BigDecimal bidAmount;
    private BigDecimal contractAmount;
    private BigDecimal changeAmount;
    private BigDecimal planAmount;
    private String scopeRemark;
    private String remark;

    /** 覆盖的(子)项目 id 列表（共享合同的多个子项目都放这里） */
    private List<Long> projectIds;
}
