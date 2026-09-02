package com.pmgt.module.log.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pmgt.common.api.R;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.entity.OperateLog;
import com.pmgt.module.log.mapper.OperateLogMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 操作日志查询（管理员）。
 */
@RequireRole({Role.ADMIN})
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogController {

    private final OperateLogMapper operateLogMapper;

    public AdminLogController(OperateLogMapper operateLogMapper) {
        this.operateLogMapper = operateLogMapper;
    }

    @GetMapping
    public R<Page<OperateLog>> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String userName,
                                    @RequestParam(required = false) String bizType,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate begin,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LambdaQueryWrapper<OperateLog> qw = new LambdaQueryWrapper<OperateLog>()
                .orderByDesc(OperateLog::getId);
        if (StringUtils.hasText(userName)) {
            qw.like(OperateLog::getUserName, userName);
        }
        if (StringUtils.hasText(bizType)) {
            qw.eq(OperateLog::getBizType, bizType);
        }
        if (StringUtils.hasText(action)) {
            qw.like(OperateLog::getAction, action);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(OperateLog::getDetail, keyword);
        }
        if (begin != null) {
            qw.ge(OperateLog::getCreateTime, LocalDateTime.of(begin, java.time.LocalTime.MIN));
        }
        if (end != null) {
            qw.le(OperateLog::getCreateTime, LocalDateTime.of(end, java.time.LocalTime.MAX));
        }
        return R.ok(operateLogMapper.selectPage(new Page<>(page, Math.min(size, 100)), qw));
    }
}
