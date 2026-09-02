package com.pmgt.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 基于 @RequireRole 的接口级角色校验。
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public RoleInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole require = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (require == null) {
            require = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (require == null || require.value().length == 0) {
            return true;
        }
        Role[] allowed = require.value();
        AuthContext.Current current = AuthContext.get();
        Role role = current == null ? null : current.role();
        if (role != null && Arrays.asList(allowed).contains(role)) {
            return true;
        }
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(403, "无权限执行该操作"));
        return false;
    }
}
