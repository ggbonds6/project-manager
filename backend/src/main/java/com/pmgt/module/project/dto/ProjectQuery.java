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
    private String type;
    private String status;
    private String ownerUnit;
    private Long managerUserId;
    /** 立项年份（approveDate 或创建年度，取 approveDate.year） */
    private Integer year;
}
