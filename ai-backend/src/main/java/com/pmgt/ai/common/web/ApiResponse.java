package com.pmgt.ai.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成功响应：{@code {"code": 0, "data": {...}}}（与 Python 版逐字段一致）。
 *
 * <p>统一响应形状的意义：主系统接入时只需实现一次解析；`code` 恒为 0 表示成功，
 * 失败走 {@link ApiException} + {@link GlobalExceptionHandler}（HTTP 状态码 + detail）。
 */
public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }
}
