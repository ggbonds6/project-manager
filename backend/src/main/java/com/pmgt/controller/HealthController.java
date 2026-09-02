package com.pmgt.controller;

import com.pmgt.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", "project-manager-backend");
        info.put("time", java.time.LocalDateTime.now().toString());
        try (Connection c = dataSource.getConnection()) {
            info.put("db", "up");
        } catch (Exception e) {
            info.put("db", "down: " + e.getMessage());
        }
        return R.ok(info);
    }
}
