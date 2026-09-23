package com.pmgt.module.ai.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.api.R;
import com.pmgt.common.security.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 受控查询（{@code /api/ai/query/**}）的<b>独立</b>鉴权过滤器。
 *
 * <h2>为什么必须独立于用户 JWT 过滤器</h2>
 * <p>调用方是 AI 服务，这条链路上<b>没有用户登录态</b>：没有浏览器、没有用户 JWT、
 * {@code AuthContext} 是空的。所以：
 * <ul>
 *   <li>{@code AuthFilter} 对 {@code /api/ai/query/**} 直接旁路（它只认作用域令牌）；</li>
 *   <li>本过滤器只认 {@code Authorization: Bearer <scope_token>}，
 *       校验通过后把范围写进 {@link AiQueryScopeContext}；</li>
 *   <li>既有接口的鉴权（{@code AuthFilter} + {@code RoleInterceptor}）一行没改，
 *       两个过滤器作用的路径集合不相交，谁也不会把对方的口子开大。</li>
 * </ul>
 *
 * <p>注意这里<b>不</b>接受 URL 上的 {@code ?token=}（用户 JWT 那条链路的兼容写法）：
 * 作用域令牌只该出现在请求头里，落到 URL / 网关访问日志里就等于泄漏凭据。
 *
 * <p>响应用<b>真实 HTTP 401</b>（不是既有的「HTTP 200 + code 401」）：调用方 AI 服务按
 * HTTP 状态分流，且它的错误文案要能原样转述给模型（见 {@link AiQueryException} 的类注释）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ScopeTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeTokenFilter.class);

    /**
     * 受控查询接口路径前缀（{@code AiQueryController} 的映射在它之下）。
     *
     * <p>取 {@link ApiPaths} 的同一个常量：{@code AuthFilter} 用它"旁路"，本过滤器用它"接管"，
     * 两处必须永远指向同一个集合。
     */
    public static final String PATH_PREFIX = ApiPaths.SERVICE_QUERY_PREFIX;

    private final ScopeTokenService scopeTokens;
    private final ObjectMapper objectMapper;

    public ScopeTokenFilter(ScopeTokenService scopeTokens, ObjectMapper objectMapper) {
        this.scopeTokens = scopeTokens;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.isServiceQuery(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            if ("OPTIONS".equals(request.getMethod())) {
                // 预检请求不带 Authorization，直接放行给 CORS 处理（与 AuthFilter 同口径）
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            AiQueryScope scope;
            try {
                scope = scopeTokens.parse(token);
            } catch (AiQueryException e) {
                // 只记"哪条路径被拒"，绝不记 token 本身
                log.warn("[ai-query] 鉴权失败 path={} 原因={}", request.getRequestURI(), e.getMessage());
                writeError(response, e.getStatus(), e.getMessage());
                return;
            }
            AiQueryScopeContext.set(scope);
            chain.doFilter(request, response);
        } finally {
            AiQueryScopeContext.clear();
        }
    }

    /** 输出与 {@code GlobalExceptionHandler} 一致的信封，但 HTTP 状态码是真实的。 */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(status, message));
    }
}
