package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 项目分工：模块 / 子模块的负责方、负责人、计划时间与进度。
 *
 * <p>用 {@code parentId} 表示层级：为空是顶层模块，不为空是其子模块
 * （深度不限，但实践中两级足够）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_division")
public class ProjectDivision extends BaseEntity {

    private Long projectId;

    /** 父级分工 id（空 = 顶层模块） */
    private Long parentId;

    /** 模块 / 子模块名称 */
    private String name;

    /** 负责方：OWNER 甲方 / VENDOR 乙方 / BOTH 双方 */
    private String ownerSide;

    /** 甲方负责人 */
    private String ownerName;

    /** 乙方负责人 */
    private String vendorOwner;

    private LocalDate planDevDate;
    private LocalDate planTestDate;
    private LocalDate planOnlineDate;

    /** 当前进度（0-100） */
    private Integer progress;

    /** 状态（字典 DIVISION_STATUS）：TODO/DOING/DONE/RISK */
    private String status;

    private String remark;

    /** 同级排序号 */
    private Integer sortNo;
}
