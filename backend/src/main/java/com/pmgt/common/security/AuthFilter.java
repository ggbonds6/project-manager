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
 * - 服务间受控查询（/api/ai/query/**）旁路，改由 ScopeTokenFilter 用作用域令牌鉴权；
 * - 其余 /api/** 必须携带合法 Bearer token，否则返回 401。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login", "/api/health");

    /**
     * 服务间受控查询前缀（§11.2）——常量在 {@link ApiPaths}，与接管它的
     * {@code ScopeTokenFilter} 引用同一个值。
     *
     * <p>它必须在这里旁路：调用方是 AI 服务，链路没有用户登录态，用户 JWT 过滤器把它
     * 一律 401 就没人能进了。旁路不等于"不鉴权"——{@code ScopeTokenFilter}
     * （Order 更大即更晚执行）只对同一前缀生效，用作用域令牌独立校验。
     * 两个过滤器的路径集合不相交，因此既有接口的鉴权行为完全不变。
     */

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
            if (ApiPaths.isServiceQuery(uri)) {
                // 服务间受控查询：没有用户登录态，鉴权交给 ScopeTokenFilter（作用域令牌）
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
