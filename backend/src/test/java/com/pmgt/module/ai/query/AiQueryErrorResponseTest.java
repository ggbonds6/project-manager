package com.pmgt.module.ai.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控查询的<b>错误响应原文</b>测试（§11.6 的"错误必须教会模型"）。
 *
 * <p>为什么单独测一层：主系统对浏览器前端是「HTTP 200 + 信封 code」，而受控查询必须给
 * <b>真实 HTTP 状态码</b>（调用方 AI 服务按状态分流：403 是"授权范围"，400 是"参数错，按提示改"）。
 * 这个差异一旦被后人"顺手统一"成 200，AI 侧会把越权误判成"参数不对、可以重试"，
 * 甚至把"没有权限"说成"系统里没有"——所以把状态码与文案逐字钉在这里。
 */
class AiQueryErrorResponseTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScopeTokenFilter filter = new ScopeTokenFilter(
            new ScopeTokenService(SECRET, ScopeTokenService.MAX_TTL_SECONDS), mapper);

    // ── 401：作用域令牌缺失/无效（过滤器层，真实 HTTP 401） ──────────────

    @Test
    void 缺作用域令牌时返回真实401与信封() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/query/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("缺少作用域令牌"), body);
        assertTrue(body.contains("Authorization: Bearer"), body);
        assertTrue(body.contains("\"data\":null"), body);
        // 鉴权失败必须止步于过滤器：业务链一次都不该执行
        assertNull(response.getHeader("ETag"));
    }

    @Test
    void 拿用户JWT当作用域令牌时返回401而不是放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/query/projects");
        // 形状对但用途不对（没有 typ=ai_scope）：必须被拒
        request.addHeader("Authorization", "Bearer not-a-real-scope-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"), response.getContentAsString());
    }

    /**
     * 过期令牌的真实响应原文。
     *
     * <p>这是本轮放宽上限（120s→300s）要消灭的那类错答：模型看到"已过期"必须说
     * "本次系统数据没查到、请重新提问"，而不是"无权查看"或"系统里没有"。
     */
    @Test
    void 过期令牌返回真实401且文案与无权可区分() throws Exception {
        String expired = expiredToken();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/query/stats");
        request.addHeader("Authorization", "Bearer " + expired);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("已过期"), body);
        assertTrue(body.contains("重新发起提问"), body);
        assertTrue(body.contains("不是「系统里没有数据」"), body);
        assertFalse(body.contains("无权"), body);
    }

    private static String expiredToken() {
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys
                .hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Date past = new java.util.Date(System.currentTimeMillis() - 10_000L);
        return io.jsonwebtoken.Jwts.builder()
                .subject("9")
                .claim(ScopeTokenService.CLAIM_TYPE, ScopeTokenService.TYPE_VALUE)
                .claim(ScopeTokenService.CLAIM_PROJECTS, java.util.List.of(1L))
                .issuedAt(new java.util.Date(past.getTime() - 360_000L))
                .expiration(past)
                .signWith(key)
                .compact();
    }

    @Test
    void 合法令牌放行并写入作用域上下文() throws Exception {
        String token = new ScopeTokenService(SECRET, ScopeTokenService.MAX_TTL_SECONDS)
                .issue(9L, "张三", java.util.List.of(1L, 2L)).token();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/query/projects");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), "过滤器不该改成功路径的状态");
        assertNotNull(chain.getRequest(), "合法令牌必须放行到业务链");
        // 请求结束后必须清理 ThreadLocal（否则线程复用会串范围）
        assertNull(AiQueryScopeContext.get());
    }

    @Test
    void 只拦截受控查询路径() {
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/ai/chat");
        MockHttpServletRequest mine = new MockHttpServletRequest("POST", "/api/ai/query/stats");

        assertTrue(filter.shouldNotFilter(other), "既有 /api/ai/chat 的鉴权不受影响");
        assertTrue(!filter.shouldNotFilter(mine));
    }

    // ── 403 / 400：业务层（Http 状态与信封 code 一致，且文案是 §11.6 的原话） ──

    @Test
    void 越权返回真实403且文案是契约原话() {
        ResponseEntity<com.pmgt.common.api.R<Void>> response =
                new GlobalExceptionHandler().handleAiQuery(
                        AiQueryException.forbidden(AiQueryService.OUT_OF_SCOPE_MESSAGE));

        assertEquals(403, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().getCode());
        assertEquals("该项目不在本次授权范围（scope）内", response.getBody().getMessage());
        // 不得泄漏任何数据
        assertNull(response.getBody().getData());
    }

    @Test
    void filters非法返回真实400且带上支持字段与示例() {
        String message = "entity=stats 不支持查询字段「sql」（本接口只接受结构化字段，不接受 SQL / 表达式 / 字段名拼接）。"
                + AiQueryEntity.STATS.supportHint();

        ResponseEntity<com.pmgt.common.api.R<Void>> response =
                new GlobalExceptionHandler().handleAiQuery(AiQueryException.badRequest(message));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("projectId"), response.getBody().getMessage());
        assertTrue(response.getBody().getMessage().contains("kind"), response.getBody().getMessage());
        assertTrue(response.getBody().getMessage().contains("示例"), response.getBody().getMessage());
    }

    @Test
    void 令牌无效返回真实401() {
        ResponseEntity<com.pmgt.common.api.R<Void>> response =
                new GlobalExceptionHandler().handleAiQuery(AiQueryException.unauthorized("作用域令牌无效或已过期"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals(401, response.getBody().getCode());
    }

    // ── 成功响应：字段名严格按 §11.4（data_time 是 snake_case） ─────────

    @Test
    void 成功响应JSON字段名严格按契约() throws Exception {
        AiQueryData data = AiQueryData.of(java.util.List.of(java.util.Map.of("phaseName", "初验")),
                "个", "口径说明", "2026-09-23 16:40:00", "项目 12（本次授权范围内）");

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("\"data_time\":\"2026-09-23 16:40:00\""), json);
        assertTrue(!json.contains("dataTime"), "RPC 契约是 data_time（snake_case），不能写成 camelCase：" + json);
        assertTrue(json.contains("\"rows\""), json);
        assertTrue(json.contains("\"unit\":\"个\""), json);
        assertTrue(json.contains("\"caliber\""), json);
        assertTrue(json.contains("\"scope\""), json);
        // 有数据时不出现空结果提示（NON_NULL）
        assertTrue(!json.contains("message"), json);
    }

    @Test
    void 空结果响应带契约原话的空结果提示() throws Exception {
        AiQueryData data = AiQueryData.of(java.util.List.of(), "个", "口径说明",
                "2026-09-23 16:40:00", "项目 12（本次授权范围内）");

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("\"rows\":[]"), json);
        assertTrue(json.contains("\"message\":\"该范围内没有匹配数据\""), json);
    }
}
