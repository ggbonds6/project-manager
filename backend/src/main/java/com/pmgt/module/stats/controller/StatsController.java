package com.pmgt.module.stats.controller;

import com.pmgt.common.api.R;
import com.pmgt.module.stats.service.StatsQuery;
import com.pmgt.module.stats.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 项目统计聚合接口（供统计页 ECharts 使用）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public R<Map<String, Object>> summary(StatsQuery q) {
        return R.ok(statsService.summary(q));
    }

    @GetMapping("/distributions")
    public R<Map<String, Object>> distributions(StatsQuery q) {
        return R.ok(statsService.distributions(q));
    }

    @GetMapping("/year-money")
    public R<Map<String, Object>> yearMoney(StatsQuery q) {
        return R.ok(statsService.yearMoney(q));
    }

    @GetMapping("/dept-ranking")
    public R<Map<String, Object>> deptRanking(StatsQuery q) {
        return R.ok(statsService.deptRanking(q));
    }
}
