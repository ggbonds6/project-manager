package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目 ↔ 合同关联（V12）。
 *
 * <p>为什么需要它：原先靠 {@code project.contract_id} 单指针挂合同，一个项目只能有一份合同；
 * 而业务上一个项目可签多份（施工主合同 / 监理服务 / 第三方测评 / 预算编制 / 方案评估）。
 * 本表为**权威关联**；{@code project.contract_id} 收窄为「主合同」指针，供既有展示与统计口径继续使用。
 */
@Data
@TableName("project_contract")
public class ProjectContract implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long contractId;
    private LocalDateTime createTime;
}
