package com.pmgt.module.ai.pipeline;

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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /api/ai/pipeline/**} 的<b>独立</b>鉴权过滤器：只认 {@code X-AI-Service-Token}。
 *
 * <h2>为什么与用户 JWT 过滤器分开</h2>
 * <p>调用方是 AI 服务这个进程（不是浏览器），链路上没有用户登录态：没有用户、没有
 * {@code AuthContext}、没有 {@code Authorization: Bearer}。所以：
 * <ul>
 *   <li>{@code AuthFilter} 对该前缀直接旁路（见 {@link ApiPaths#SERVICE_PIPELINE_PREFIX}）；</li>
 *   <li>本过滤器只认 {@code X-AI-Service-Token}，<b>不接受</b>用户 JWT：
 *       用户令牌在这一前缀上不会因为"能解析"就被放行（这是与被旁路的其它前缀最大的区别）；</li>
 *   <li>用户接口那一侧也不认本令牌：{@code X-AI-Service-Token} 不是 {@code Authorization}，
 *       {@code AuthFilter} 照样 401。两个方向都不通用，是有意为之。</li>
 * </ul>
 *
 * <h2>三条实现纪律</h2>
 * <ol>
 *   <li><b>fail closed</b>：{@code pm.ai.pipeline.service-token} 没配（空）⇒ 该前缀全部 401。
 *       宁可在部署时立刻看到 401，也不要留下一个"忘了配就无人鉴权"的写接口；</li>
 *   <li><b>常量时间比较</b>（{@link MessageDigest#isEqual}）：不用 {@code equals} 逐字符短路，
 *       避免把正确前缀长度泄漏成时间差；</li>
 *   <li><b>绝不记录令牌本身</b>：日志只写"哪条路径被拒、缺头还是值不对"。</li>
 * </ol>
 *
 * <p>响应用<b>真实 HTTP 401</b>（与 {@code ScopeTokenFilter} 同口径，不是既有的
 * "HTTP 200 + code 401"）：AI 服务按 HTTP 状态分流，401 表示"凭据问题、别重试，
 * 先修配置"。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AiPipelineTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AiPipelineTokenFilter.class);

    /** 服务间令牌的请求头名（与 AI 侧必须逐字一致）。 */
    public static final String HEADER = "X-AI-Service-Token";

    private final AiPipelineProperties props;
    private final ObjectMapper objectMapper;

    public AiPipelineTokenFilter(AiPipelineProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.isServicePipeline(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            // 预检请求不带自定义头，直接放行给 CORS 处理（与 AuthFilter/ScopeTokenFilter 同口径）
            chain.doFilter(request, response);
            return;
        }
        String configured = props.getServiceToken();
        if (!StringUtils.hasText(configured)) {
            log.warn("[ai-pipeline] 拒绝 {}：pm.ai.pipeline.service-token 未配置（该前缀 fail closed）",
                    request.getRequestURI());
            write401(response, "流水线回写令牌未配置：请在主系统配置 pm.ai.pipeline.service-token"
                    + "（环境变量 AI_SERVICE_TOKEN）后重试；当前该前缀拒绝全部调用（fail closed）");
            return;
        }
        String presented = request.getHeader(HEADER);
        if (!StringUtils.hasText(presented)) {
            log.warn("[ai-pipeline] 拒绝 {}：缺少请求头 {}", request.getRequestURI(), HEADER);
            write401(response, "缺少服务间令牌：请在请求头 " + HEADER + " 中携带 AI 服务令牌"
                    + "（注意：这一前缀不接受用户 JWT 的 Authorization 头）");
            return;
        }
        if (!constantTimeEquals(configured, presented)) {
            log.warn("[ai-pipeline] 拒绝 {}：{} 不正确", request.getRequestURI(), HEADER);
            write401(response, "服务间令牌不正确：" + HEADER + " 与主系统配置的令牌不一致，请检查两侧配置");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 常量时间比较（长度不同也要走完整个比较过程）。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void write401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(401, message));
    }
}
