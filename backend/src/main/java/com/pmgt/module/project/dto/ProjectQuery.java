package com.pmgt.module.project.dto;

import lombok.Data;

/**
 * 项目列表查询：分页 + 筛选。
 */
@Data
public class ProjectQuery {

    private Integer page = 1;
    private Integer size = 20;

    /** 关键字：项目名称/编号/供应商 */
    private String keyword;
    /** 支持多选：逗号分隔，如 "HW,SW" */
    private String type;
    /** 支持多选：逗号分隔，如 "RUN,PAUSE" */
    private String status;
    private String ownerUnit;
    private Long managerUserId;
    /** 立项年份，支持多选：逗号分隔的字符串，如 "2024,2025" */
    private String year;

    /** 指定父项目 id 时返回其子项目；不传则只返回顶层项目 */
    private Long parentId;
}
