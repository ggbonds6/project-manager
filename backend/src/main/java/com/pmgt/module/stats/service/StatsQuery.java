package com.pmgt.module.stats.service;

import lombok.Data;

/**
 * 统计/仪表盘筛选条件（与项目列表一致的维度，不含分页）。
 */
@Data
public class StatsQuery {

    private Integer year;
    private String type;
    private String status;
    private String ownerUnit;
    private Long managerUserId;
}
