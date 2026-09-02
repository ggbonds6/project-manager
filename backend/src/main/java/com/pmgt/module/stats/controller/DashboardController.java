package com.pmgt.module.stats.controller;

import com.pmgt.common.api.R;
import com.pmgt.common.security.AuthContext;
import com.pmgt.module.stats.service.StatsQuery;
import com.pmgt.module.stats.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页工作台聚合接口（当前登录用户相关）。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final StatsService statsService;

    public DashboardController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    public R<Map<String, Object>> dashboard() {
        return R.ok(statsService.dashboard(AuthContext.userId().orElse(null)));
    }
}
