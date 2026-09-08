package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_overview")
public class ProjectOverview extends BaseEntity {

    private Long projectId;
    /** 项目介绍（Markdown） */
    private String introMd;
    /** 功能模块清单（二级 JSON） */
    private String modulesJson;
}
