package com.pmgt.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.api.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * JWT 认证过滤器：
 * - /api/auth/login、/api/health 等白名单直接放行；
 * - 其余 /api/** 必须携带合法 Bearer token，否则返回 401。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login", "/api/health");

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String uri = request.getRequestURI();
            if (!uri.startsWith("/api/") || WHITELIST.contains(uri) || "OPTIONS".equals(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            String token = null;
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7);
            } else if (request.getParameter("token") != null) {
                // 供 <a href> / <img> 等无法携带 Header 的下载/预览场景使用
                token = request.getParameter("token");
            }
            if (token == null || token.isBlank()) {
                write401(response, "未登录或缺少令牌");
                return;
            }
            try {
                AuthContext.Current current = jwtUtil.parse(token);
                AuthContext.set(current);
            } catch (Exception e) {
                write401(response, "登录已过期或令牌无效");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void write401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(401, message));
    }
}
