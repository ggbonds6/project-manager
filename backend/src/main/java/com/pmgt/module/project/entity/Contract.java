package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同：可被一个或多个(子)项目引用（project.contract_id 指向同一 contract 即共享）。
 *
 * <p>V10 起补充"政府合同常见字段"：合同类型、甲方、关键日期、合同状态、
 * 付款账户、验收标准、质保信息。乙方沿用 {@code vendorName}（供应商=承接单位）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract")
public class Contract extends BaseEntity {

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

    // ── V10 新增 ──────────────────────────────────────────────
    /** 合同类型（字典 CONTRACT_TYPE）：施工合同/第三方测评/方案评估/监理/设计/预算编制… */
    private String contractType;
    /** 甲方（建设单位/采购人） */
    private String partyA;
    private LocalDate signDate;
    private LocalDate effectiveDate;
    /** 工期开始 */
    private LocalDate startDate;
    /** 工期结束 */
    private LocalDate endDate;
    /** 合同状态（字典 CONTRACT_STATUS） */
    private String contractStatus;
    // 付款账户：政府合同付款前必须核对收款信息
    private String payeeName;
    private String payeeBank;
    private String payeeAccount;
    private String acceptanceStandard;
    private Integer warrantyMonths;
    private BigDecimal warrantyAmount;
    private BigDecimal settleAmount;
}
