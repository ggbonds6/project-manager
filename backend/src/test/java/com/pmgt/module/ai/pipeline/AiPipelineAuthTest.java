package com.pmgt.module.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.security.ApiPaths;
import com.pmgt.common.security.AuthFilter;
import com.pmgt.common.security.JwtUtil;
import com.pmgt.common.security.Role;
import com.pmgt.module.ai.pipeline.service.AiPipelineTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务间鉴权（契约：{@code X-AI-Service-Token}），<b>双向都不可混用</b>：
 * <ul>
 *   <li>用户 JWT <b>不能</b>调 {@code /api/ai/pipeline/**}（哪怕它是一枚完全合法的用户令牌）；</li>
 *   <li>AI 服务令牌<b>不能</b>调用户接口（{@code X-AI-Service-Token} 不是 {@code Authorization}）；</li>
 *   <li>运行级 {@code pipeline_token} 也<b>不能</b>当用户 JWT 用（同一把密钥，靠 claims 用途隔离）。</li>
 * </ul>
 * 三条都在这里逐条钉住：安全边界一旦被"顺手放宽"而没人发现，就等于没有边界。
 */
class AiPipelineAuthTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String SERVICE_TOKEN = "service-token-abc-0123456789";

    private final ObjectMapper mapper = new ObjectMapper();

    private AiPipelineProperties props(String token) {
        AiPipelineProperties props = new AiPipelineProperties();
        props.setServiceToken(token);
        return props;
    }

    private AiPipelineTokenFilter filter(String token) {
        return new AiPipelineTokenFilter(props(token), mapper);
    }

    // ── 缺/错令牌 → 真实 401 ────────────────────────────────────────

    @Test
    void 缺少服务间令牌时返回真实401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SERVICE_TOKEN).doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("X-AI-Service-Token"), body);
        assertTrue(body.contains("\"data\":null"), body);
    }

    @Test
    void 服务间令牌不正确时返回真实401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        request.addHeader(AiPipelineTokenFilter.HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SERVICE_TOKEN).doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("服务间令牌不正确"),
                response.getContentAsString());
    }

    @Test
    void 未配置令牌时该前缀全部拒绝而不是放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        request.addHeader(AiPipelineTokenFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("").doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus(), "fail closed：没配令牌就不能有无人鉴权的写接口");
        assertTrue(response.getContentAsString().contains("未配置"), response.getContentAsString());
    }

    @Test
    void 令牌正确时放行到业务链() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        request.addHeader(AiPipelineTokenFilter.HEADER, SERVICE_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SERVICE_TOKEN).doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), "过滤器不该改成功路径的状态");
        assertNotNull(chain.getRequest());
    }

    @Test
    void 只拦截流水线前缀() {
        AiPipelineTokenFilter filter = filter(SERVICE_TOKEN);
        MockHttpServletRequest pipeline = new MockHttpServletRequest("POST", "/api/ai/pipeline/runs/run-1/finish");
        MockHttpServletRequest query = new MockHttpServletRequest("POST", "/api/ai/query/stats");
        MockHttpServletRequest tasks = new MockHttpServletRequest("GET", "/api/ai/tasks/1/pipeline");
        MockHttpServletRequest dict = new MockHttpServletRequest("GET", "/api/ai/stage-dict");

        assertTrue(!filter.shouldNotFilter(pipeline));
        assertTrue(filter.shouldNotFilter(query), "受控查询由 ScopeTokenFilter 管，不能被本过滤器抢走");
        assertTrue(filter.shouldNotFilter(tasks), "前端只读接口走用户 JWT");
        assertTrue(filter.shouldNotFilter(dict), "字典接口刻意不在 /api/ai/pipeline 之下");
    }

    // ── 用户 JWT 不能调该前缀 ──────────────────────────────────────

    @Test
    void 合法用户JWT也不能调流水线前缀() throws Exception {
        String userJwt = new JwtUtil(SECRET, 168).createToken(9L, "admin", "管理员", Role.ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        request.addHeader("Authorization", "Bearer " + userJwt);   // 只带用户令牌，不带服务令牌
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SERVICE_TOKEN).doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus(), "该前缀只认 X-AI-Service-Token");
    }

    // ── AI 令牌不能调用户接口 ──────────────────────────────────────

    @Test
    void AI服务令牌不能调用户接口() throws Exception {
        AuthFilter authFilter = new AuthFilter(new JwtUtil(SECRET, 168), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/tasks");
        request.addHeader(AiPipelineTokenFilter.HEADER, SERVICE_TOKEN);   // 没有 Authorization
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "不能进业务链");
        // 用户接口沿用仓库既有约定：HTTP 200 + 信封 code 401（这一条对浏览器前端是有意保留的）
        assertTrue(response.getContentAsString().contains("\"code\":401"), response.getContentAsString());
    }

    @Test
    void AuthFilter对流水线前缀旁路且不再校验用户令牌() throws Exception {
        AuthFilter authFilter = new AuthFilter(new JwtUtil(SECRET, 168), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/pipeline/claim");
        request.addHeader(AiPipelineTokenFilter.HEADER, SERVICE_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "该前缀由 AiPipelineTokenFilter 接管，AuthFilter 必须放行");
    }

    @Test
    void 路径常量两处一致() {
        assertTrue(ApiPaths.isServicePipeline("/api/ai/pipeline"));
        assertTrue(ApiPaths.isServicePipeline("/api/ai/pipeline/runs/run-1"));
        assertTrue(!ApiPaths.isServicePipeline("/api/ai/pipeline-dict"));
        assertTrue(!ApiPaths.isServicePipeline("/api/ai/tasks/1/pipeline"));
        assertTrue(ApiPaths.isServiceQuery("/api/ai/query/stats"));
        assertTrue(!ApiPaths.isServiceQuery("/api/ai/pipeline/runs"));
    }

    // ── pipeline_token 的用途隔离 ──────────────────────────────────

    @Test
    void 运行级令牌不能当用户JWT用() {
        String pipelineToken = new AiPipelineTokenService(SECRET).issue("run-12");
        JwtUtil jwtUtil = new JwtUtil(SECRET, 168);

        // 同一把密钥签名，但 claims 里没有 role（用户 JWT 必读它）⇒ 解析必须失败
        assertThrows(Exception.class, () -> jwtUtil.parse(pipelineToken));
    }

    @Test
    void 运行级令牌不属于该run时403() {
        AiPipelineTokenService tokens = new AiPipelineTokenService(SECRET);
        String token = tokens.issue("run-12");

        assertEquals(Boolean.TRUE, tokens.verifyForRun(token, "run-12"));
        assertNull(tokens.verifyForRun(null, "run-12"), "不带就不校验（契约的门票是服务间令牌）");
        assertNull(tokens.verifyForRun("  ", "run-12"));

        AiPipelineException other = assertThrows(AiPipelineException.class,
                () -> tokens.verifyForRun(token, "run-99"));
        assertEquals(403, other.getStatus());
        assertTrue(other.getMessage().contains("不属于本次 run"), other.getMessage());

        AiPipelineException junk = assertThrows(AiPipelineException.class,
                () -> tokens.verifyForRun("not-a-jwt", "run-12"));
        assertEquals(403, junk.getStatus());
        assertTrue(junk.getMessage().contains("无效"), junk.getMessage());
    }

    @Test
    void 用户JWT当运行级令牌用会被拒() {
        String userJwt = new JwtUtil(SECRET, 168).createToken(9L, "admin", "管理员", Role.ADMIN);

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> new AiPipelineTokenService(SECRET).verifyForRun(userJwt, "run-12"));

        assertEquals(403, e.getStatus());
        assertTrue(e.getMessage().contains("不是流水线运行令牌"), e.getMessage());
    }
}
