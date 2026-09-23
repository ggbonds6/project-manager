package com.pmgt.module.ai.query;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeTokenService} 的签发/校验测试（§11.2）。
 *
 * <p>这一层是受控查询的<b>唯一授权凭据</b>，所以重点钉四件事：
 * 范围确实写进了 token、有效期上限与超时链对齐（300s，配置再大也夹回）、
 * 用户 JWT 不能拿来冒充、过期与无效<b>可区分</b>。
 */
class ScopeTokenServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";

    private static ScopeTokenService service() {
        return new ScopeTokenService(SECRET, ScopeTokenService.MAX_TTL_SECONDS);
    }

    @Test
    void 签发的令牌能解析出用户与项目范围() {
        ScopeTokenService tokens = service();

        ScopeTokenService.IssuedScope issued = tokens.issue(9L, "张三", List.of(1L, 2L, 3L));
        AiQueryScope scope = tokens.parse(issued.token());

        assertEquals(9L, scope.userId());
        assertEquals("张三", scope.userName());
        assertEquals(List.of(1L, 2L, 3L), scope.projects());
        assertEquals(issued.jti(), scope.jti());
        // jti 必须真的在 token 里（回调计数靠它关联回这次问答）
        assertTrue(issued.jti() != null && issued.jti().length() >= 16, issued.jti());
    }

    @Test
    void 项目列表去重且忽略null() {
        AiQueryScope scope = service().parse(service().issue(9L, "张三",
                java.util.Arrays.asList(1L, 1L, null, 2L)).token());

        assertEquals(List.of(1L, 2L), scope.projects());
    }

    @Test
    void 没有项目时范围为空表示什么都查不到而不是全部() {
        ScopeTokenService tokens = service();

        AiQueryScope scope = tokens.parse(tokens.issue(9L, "张三", null).token());

        assertTrue(scope.projects().isEmpty());
        assertTrue(!scope.contains(1L));
        assertTrue(scope.describe().contains("无"), scope.describe());
    }

    @Test
    void 有效期上限300秒且配置再大也被夹住() {
        // 300 是"配置能配到的最大值"：与超时链对齐（前端 180s + AI_CHAT_TIMEOUT 180s
        // ⇒ 一轮问答最长 3 分钟以上 ⇒ 令牌必须 > 一轮问答上限，取 300s 留余量）
        assertEquals(300, ScopeTokenService.MAX_TTL_SECONDS);
        assertEquals(300, new ScopeTokenService(SECRET, 300).ttlSeconds());
        long exp = expiryOf(new ScopeTokenService(SECRET, 300).issue(9L, "张三", List.of(1L)).token());
        long now = System.currentTimeMillis();
        assertTrue(exp - now <= 300_000L + 2_000L, "exp 必须 ≤300s，实际 " + (exp - now) + "ms");
        assertTrue(exp > now, "令牌不能一签发就过期");
        // 300s 必须明显长于前端 180s 的问答超时，否则问答后段的回调仍会 401
        assertTrue(exp - now > 180_000L, "令牌有效期必须大于前端 180s 的问答超时");
    }

    @Test
    void 配置超过300秒被夹回300秒() {
        // 安全参数不靠配置保证：配 301s / 3600s 都必须夹到 300s，只能配小不能配大
        assertEquals(300, new ScopeTokenService(SECRET, 301).ttlSeconds());
        assertEquals(300, new ScopeTokenService(SECRET, 3600).ttlSeconds());

        long exp = expiryOf(new ScopeTokenService(SECRET, 3600).issue(9L, "张三", List.of(1L)).token());
        long now = System.currentTimeMillis();
        assertTrue(exp - now <= 300_000L + 2_000L, "配 3600s 也必须夹回 300s，实际 " + (exp - now) + "ms");
    }

    @Test
    void 配置更小则按配置生效() {
        // 允许配小（想把凭据窗口收得更紧的部署可以这么做）
        assertEquals(60, new ScopeTokenService(SECRET, 60).ttlSeconds());
        // 非法值（0/负数）退回上限，而不是签出一个"立即过期"或"永不过期"的令牌
        assertEquals(300, new ScopeTokenService(SECRET, 0).ttlSeconds());
        assertEquals(300, new ScopeTokenService(SECRET, -5).ttlSeconds());
    }

    @Test
    void 用户JWT不能当作用域令牌使用() {
        // 用同一把密钥签一个"用户 JWT 形状"的令牌（有 sub/role，没有 typ=ai_scope）
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        String userToken = Jwts.builder()
                .subject("9")
                .claim("account", "zhangsan")
                .claim("name", "张三")
                .claim("role", "MANAGER")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3600_000L))
                .signWith(key)
                .compact();

        AiQueryException e = assertThrows(AiQueryException.class, () -> service().parse(userToken));

        assertEquals(401, e.getStatus());
        assertTrue(e.getMessage().contains("不是作用域令牌"), e.getMessage());
    }

    @Test
    void 过期令牌被拒绝且文案与无效令牌可区分() {
        // 构造一枚"已经过期"的令牌（iat 在 6 分钟前、exp 在 10 秒前）：不依赖服务配置
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date past = new Date(System.currentTimeMillis() - 10_000L);
        String expired = Jwts.builder()
                .subject("9")
                .claim(ScopeTokenService.CLAIM_TYPE, ScopeTokenService.TYPE_VALUE)
                .claim(ScopeTokenService.CLAIM_PROJECTS, List.of(1L))
                .issuedAt(new Date(past.getTime() - 360_000L))
                .expiration(past)
                .signWith(key)
                .compact();

        AiQueryException e = assertThrows(AiQueryException.class, () -> service().parse(expired));

        assertEquals(401, e.getStatus());
        // 关键：这是"这次问答跑太久"，不是"你没有权限"，更不是"系统里没有数据"
        assertTrue(e.getMessage().contains("已过期"), e.getMessage());
        assertTrue(e.getMessage().contains("重新发起提问"), e.getMessage());
        assertTrue(e.getMessage().contains("不是「系统里没有数据」"), e.getMessage());
        assertTrue(!e.getMessage().contains("无权查看"), "不能把过期说成无权：" + e.getMessage());
        // 带上签发时刻与已过时长，排障/转述都够用
        assertTrue(e.getMessage().contains("令牌签发于"), e.getMessage());
    }

    @Test
    void 无效令牌的文案不含已过期以免误导模型() {
        AiQueryException e = assertThrows(AiQueryException.class, () -> service().parse("not-a-jwt"));

        assertEquals(401, e.getStatus());
        assertTrue(e.getMessage().contains("无效"), e.getMessage());
        assertTrue(!e.getMessage().contains("已过期"), "无效与过期必须能分开：" + e.getMessage());
    }

    @Test
    void 别的密钥签的令牌被拒绝() {
        ScopeTokenService other = new ScopeTokenService(
                "another-secret-0123456789abcdefghijklmnopqrstuvwxyz", ScopeTokenService.MAX_TTL_SECONDS);

        AiQueryException e = assertThrows(AiQueryException.class,
                () -> service().parse(other.issue(9L, "张三", List.of(1L)).token()));

        assertEquals(401, e.getStatus());
    }

    @Test
    void 空令牌与乱码被拒绝() {
        assertEquals(401, assertThrows(AiQueryException.class, () -> service().parse(null)).getStatus());
        assertEquals(401, assertThrows(AiQueryException.class, () -> service().parse("   ")).getStatus());
        assertEquals(401, assertThrows(AiQueryException.class, () -> service().parse("not-a-jwt")).getStatus());
    }

    @Test
    void toString不回显令牌原文() {
        ScopeTokenService.IssuedScope issued = service().issue(9L, "张三", List.of(1L));

        String text = issued.toString();

        assertTrue(text.contains("token=***"), text);
        assertNotEquals(-1, text.indexOf("token=***"));
        assertTrue(!text.contains(issued.token().substring(0, 20)), "toString 不能带出令牌：" + text);
    }

    private static long expiryOf(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
                .getPayload().getExpiration().getTime();
    }
}
