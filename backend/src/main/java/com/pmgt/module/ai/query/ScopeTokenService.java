package com.pmgt.module.ai.query;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 作用域令牌（{@code scope_token}）的签发与校验——§11.2 的<b>唯一授权凭据</b>。
 *
 * <h2>它解决什么问题</h2>
 * <p>P2 的事实问答是「反向回调」：AI 服务在推理过程中回调主系统的
 * {@code /api/ai/query/{entity}}。这条链路上<b>没有用户登录态</b>，也不能让 AI 服务
 * 拿用户 JWT（那等于把用户的全权凭据交给第三方服务）。于是主系统每次问答现签一枚
 * <b>短时效、范围受限</b>的令牌：
 * <pre>
 * claims: sub = 提问用户 id，projects = [该用户可访问的项目 id]，iat = 签发时刻，exp ≤ 300s
 * </pre>
 * 查询接口只认它里面的 {@code projects}；请求体里出现的任何 {@code projectId}
 * 若不在这个列表内，一律 403（见 {@link AiQueryService}）。
 *
 * <h2>与用户 JWT 的关系（同密钥、不同用途）</h2>
 * <ul>
 *   <li><b>同一把密钥</b>：{@code app.jwt.secret}（{@code JWT_SECRET}）。不引入第二套密钥，
 *       运维不需要多管一个密钥；</li>
 *   <li><b>用途隔离</b>：本服务只在 claims 里写 {@code typ=ai_scope}，解析时<b>强制校验</b>该标记。
 *       因此用户 JWT（{@code typ} 缺失）当作用域令牌用会被直接拒绝；
 *       反向亦然（用户 JWT 校验在读 {@code role} 时失败）；</li>
 *   <li><b>独立鉴权链</b>：{@code /api/ai/query/**} 不经用户 JWT 过滤器
 *       （见 {@code AuthFilter} 的旁路与 {@link ScopeTokenFilter}），既有接口的鉴权不受影响。</li>
 * </ul>
 *
 * <h2>为什么上限是 300s（而不是更短的 120s）</h2>
 * <p>上限必须<b>比一轮问答的最长耗时更长</b>，否则问答后段的回调会 401，而模型会把
 * "授权过期"说成"无权查看"、甚至说成"系统里没有数据"——<b>答案直接错</b>，这正是 §11 反复要避免的。
 * 推导链：
 * <pre>
 *   前端问答超时 ≈ 180s（已放宽）
 *   + 主系统 AI_CHAT_TIMEOUT 建议 180s
 *   ⇒ 一轮问答最长可能跑 3 分钟以上
 *   ⇒ scope_token 必须 &gt; 一轮问答上限，取 300s 留余量
 * </pre>
 * 上限仍然<b>写死在代码里</b>（配置只能配小、配大了会被夹回）：凭据寿命不该因为改错一行
 * YAML 而变长。令牌依旧是"每次问答现签、用完即弃"，只是不再短于一轮问答。
 *
 * <p>另外：过期会与"无效"分开报（见 {@link #parse}），并在文案里说明是"授权过期、
 * 请重新提问"，让模型不可能把它误读成"没有权限"或"系统里没有"。
 */
@Component
public class ScopeTokenService {

    /** claims 里的用途标记，防止用户 JWT / 作用域令牌互相冒用。 */
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_VALUE = "ai_scope";

    /** claims 里的项目白名单。 */
    public static final String CLAIM_PROJECTS = "projects";

    /**
     * 有效期硬上限（秒）：配置再大也不放行。
     *
     * <p>取 300 的推理链：<b>前端问答超时 180s + 主系统 AI_CHAT_TIMEOUT 180s ⇒ 一轮问答最长可能
     * 跑 3 分钟以上 ⇒ 令牌有效期必须大于一轮问答上限，故取 300s 留余量</b>。
     * 定得比一轮问答短会让问答后段的回调 401，而模型会把"授权过期"说成"无权查看/系统里没有"，
     * 答案直接错——这也是 §11.2 的 {@code exp≤120s} 在真实超时链下必须放宽的原因。
     */
    public static final int MAX_TTL_SECONDS = 300;

    /** 过期文案里展示签发时刻用（与主系统其它时间展示口径一致：本地时区、秒级）。 */
    private static final DateTimeFormatter ISSUED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SecretKey key;
    private final int ttlSeconds;

    public ScopeTokenService(@Value("${app.jwt.secret}") String secret,
                             @Value("${pm.ai.scope-token-ttl-seconds:300}") int ttlSeconds) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = clampTtl(ttlSeconds);
    }

    /** 实际生效的有效期（秒），供自检/日志展示。 */
    public int ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 签发一枚作用域令牌。
     *
     * @param userId     提问用户 id（可为 null：留痕会记成匿名，但范围仍然受限）
     * @param userName   提问用户姓名（仅用于服务间调用写 operate_log）
     * @param projectIds 本次授权的项目 id 白名单；null/空 = 本次问答什么项目都不许查
     * @return token 与其 jti（jti 用于把回调次数关联回这一次问答，见 {@link AiQueryUsageTracker}）
     */
    public IssuedScope issue(Long userId, String userName, List<Long> projectIds) {
        List<Long> projects = projectIds == null ? List.of()
                : projectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        String jti = UUID.randomUUID().toString().replace("-", "");
        Date now = new Date();
        String token = Jwts.builder()
                .subject(userId == null ? "" : String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_VALUE)
                .claim(CLAIM_PROJECTS, projects)
                .claim("name", userName == null ? "" : userName)
                .id(jti)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000L))
                .signWith(key)
                .compact();
        return new IssuedScope(token, new AiQueryScope(jti, userId, userName, projects));
    }

    /**
     * 校验并解析作用域令牌。
     *
     * <p><b>过期与无效必须分开报</b>（见 {@link #expiredMessage}）：两者对模型的含义完全不同——
     * "过期"是"这次问答跑太久，重新提问即可"，"无效"是"这个凭据根本不成立"。
     * 混成一句"无效或已过期"时，模型很容易说成"无权查看"甚至"系统里没有数据"，
     * 而后者是**答案直接错**（§11.6 的红线）。
     *
     * @throws AiQueryException 401：令牌缺失 / 已过期 / 无效 / 不是作用域令牌（缺 {@code typ}）
     */
    public AiQueryScope parse(String token) {
        if (!StringUtils.hasText(token)) {
            throw AiQueryException.unauthorized("缺少作用域令牌：请在 Authorization: Bearer <scope_token> 中携带");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw AiQueryException.unauthorized(expiredMessage(e.getClaims()));
        } catch (Exception e) {
            // 签名不符/结构损坏：只说"无效"。把 jwt 库的原始异常文本抛出去只会泄漏实现细节。
            throw AiQueryException.unauthorized("作用域令牌无效（签名不符或格式损坏）：受控查询只接受主系统"
                    + "本次问答现签的 scope_token（有效期 ≤ " + MAX_TTL_SECONDS + " 秒），请重新发起提问");
        }
        // 用途隔离：用户 JWT 没有 typ=ai_scope，不能被当成作用域令牌使用
        if (!TYPE_VALUE.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw AiQueryException.unauthorized(
                    "该令牌不是作用域令牌（scope_token）：受控查询只接受主系统签发的、带 " + CLAIM_TYPE + "="
                            + TYPE_VALUE + " 的短时效令牌");
        }
        Long userId = null;
        String sub = claims.getSubject();
        if (sub != null && !sub.isBlank()) {
            try {
                userId = Long.valueOf(sub.trim());
            } catch (NumberFormatException e) {
                userId = null;
            }
        }
        return new AiQueryScope(claims.getId(), userId, claims.get("name", String.class),
                longList(claims.get(CLAIM_PROJECTS)));
    }

    /**
     * 过期令牌的 401 文案——刻意<b>可区分</b>，并要求调用方按"没查到"而不是"没有权限/没有数据"处理。
     *
     * <p>带上签发时刻与已过时长：一眼能看出是"这次问答跑太久"而不是"有人在乱试"，
     * 也让模型能原话转述给用户（"本次问答耗时过长，请重新提问"）。
     */
    private String expiredMessage(Claims claims) {
        StringBuilder sb = new StringBuilder("scope_token 已过期：本次问答耗时超过了令牌有效期（")
                .append(ttlSeconds).append(" 秒）");
        Date issuedAt = claims == null ? null : claims.getIssuedAt();
        if (issuedAt != null) {
            long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - issuedAt.getTime()) / 1000L);
            sb.append("，令牌签发于 ").append(ISSUED_AT_FORMAT.format(issuedAt.toInstant()))
                    .append("（约 ").append(elapsedSeconds).append(" 秒前）");
        }
        return sb.append("。这是「授权过期」而不是「没有权限」，更不是「系统里没有数据」："
                + "请如实告知用户本次系统数据没查到，并请其重新发起提问。").toString();
    }

    /**
     * {@code projects} claim → {@code List<Long>}。
     *
     * <p>不能直接 {@code claims.get("projects", List.class)} 再强转：JSON 反序列化出来的元素
     * 是 {@code Integer}（小数字），直接 {@code (Long)} 会 {@code ClassCastException}——
     * 这是"签名校验通过却在解析时 500"的经典写法，必须按 {@link Number} 取。
     */
    private static List<Long> longList(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) {
                    out.add(n.longValue());
                } else if (item instanceof String s && !s.isBlank()) {
                    try {
                        out.add(Long.valueOf(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // 脏数据忽略即可：范围只会变窄，不会越权
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static int clampTtl(int configured) {
        if (configured <= 0) {
            return MAX_TTL_SECONDS;
        }
        return Math.min(configured, MAX_TTL_SECONDS);
    }

    /**
     * 一枚刚签发的令牌。
     *
     * @param token JWT 原文（只发给 AI 服务，绝不可写日志/留痕）
     * @param scope 令牌承载的授权范围（含 {@code jti}，签发方无需再解析回来）
     */
    public record IssuedScope(String token, AiQueryScope scope) {

        /** 便捷取 jti（{@link AiQueryUsageTracker} 的键）。 */
        public String jti() {
            return scope.jti();
        }

        /** 刻意覆盖：record 默认 toString 会连 token 一起打出来，日志一泄漏凭据就白签了。 */
        @Override
        public String toString() {
            return "IssuedScope[jti=" + scope.jti() + ", projects=" + scope.projects() + ", token=***]";
        }
    }
}
