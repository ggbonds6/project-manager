package com.pmgt.module.stats.service;

import lombok.Data;

import java.util.List;

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

    /**
     * 项目 id 白名单（可选）——P2 受控查询用它把统计限制在 {@code scope_token} 的范围内。
     *
     * <p>为什么加在这里而不是另写一套聚合：统计口径（核算单元、合同去重、年度归集）是
     * 有讲究的，复制一份必然漂移；加一个"范围收窄"输入，既有调用方不传就是原行为。
     *
     * <p>⚠️ 它只能<b>收窄</b>，不能放大：传进来的列表与过滤条件取交集，
     * 不会让任何原本查不到的项目出现。
     */
    private List<Long> projectIds;
}
