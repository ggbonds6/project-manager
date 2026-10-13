package com.pmgt.module.ai.pipeline.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * 运行级 {@code pipeline_token} 的签发与校验（claim 响应里返回给 AI 服务）。
 *
 * <h2>它<b>不是</b>这条链路的鉴权凭据</h2>
 * <p>契约写死的鉴权方式是请求头 {@code X-AI-Service-Token}（部署期配置的服务间令牌，
 * 见 {@code AiPipelineTokenFilter}）。{@code pipeline_token} 是<b>运行级</b>的关联凭据：
 * <ul>
 *   <li><b>用途一（关联）</b>：AI 侧把它写进自己的日志/任务记录，排障时一眼能对上
 *       "主系统的哪次 run ↔ AI 侧的哪个 upload-task"；</li>
 *   <li><b>用途二（纵深防御）</b>：能力够的话，AI 侧后续在 run 级请求里带
 *       {@code X-AI-Pipeline-Token}，主系统会校验它<b>是否属于这个 run</b>——
 *       这样"令牌配错但服务能连上"的情况下也不会串写到别人的 run（→ 403）。
 *       为兼容"AI 侧不带这个头"的现状，<b>缺失不拒绝</b>；带了就必须对得上。</li>
 * </ul>
 *
 * <h2>与用户 JWT / scope_token 的隔离</h2>
 * <p>三者的密钥相同（{@code app.jwt.secret}，不引入第二套密钥），但 claims 里的
 * 用途标记不同：本类是 {@code typ=ai_pipeline}，受控查询是 {@code typ=ai_scope}，
 * 用户 JWT 没有 {@code typ} 而必须带 {@code role}。
 * 因此本令牌<b>当不了用户 JWT</b>（{@code JwtUtil.parse} 读 {@code role} 会失败 →
 * AuthFilter 401），也当不了 scope_token（用途标记不符 → 401）。
 */
@Component
public class AiPipelineTokenService {

    /** claims 里的用途标记。 */
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_VALUE = "ai_pipeline";

    /**
     * 令牌有效期（秒），固定 24h。
     *
     * <p>为什么远比租约（90s）长：租约会被心跳不断续期，而令牌在 claim 时一次性签发，
     * 若按租约长度签发，一次十几分钟的解析跑到一半就会"令牌过期"→ 403，
     * 把"业务上合法"的操作判成攻击。它本来就<b>不是</b>主鉴权，
     * 有效期长一点不会扩大攻击面（真正的门票是 X-AI-Service-Token）。
     */
    public static final int TTL_SECONDS = 24 * 3600;

    private final SecretKey key;

    public AiPipelineTokenService(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 为一次 run 签发运行级令牌（{@code sub = run_id}）。 */
    public String issue(String runId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(runId)
                .claim(CLAIM_TYPE, TYPE_VALUE)
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TTL_SECONDS * 1000L))
                .signWith(key)
                .compact();
    }

    /**
     * 校验令牌是否属于该 run。
     *
     * @return null 表示"没有携带"（调用方按"不带就不校验"处理）；
     *         true/false 表示携带了且校验结论明确
     * @throws com.pmgt.module.ai.pipeline.AiPipelineException 403：形状/签名/有效期不对，或不属于该 run
     */
    public Boolean verifyForRun(String token, String runId) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token.trim()).getPayload();
        } catch (Exception e) {
            // 只说"无效"，不回显 token 片段，也不回显底层异常文本（防凭据泄漏与实现细节泄漏）
            throw com.pmgt.module.ai.pipeline.AiPipelineException.forbidden(
                    "X-AI-Pipeline-Token 无效（签名不符、格式损坏或已过期）：请使用 claim 响应里返回的 pipeline_token");
        }
        if (!TYPE_VALUE.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw com.pmgt.module.ai.pipeline.AiPipelineException.forbidden(
                    "该令牌不是流水线运行令牌（pipeline_token）：claim 响应里返回的才是");
        }
        String subject = claims.getSubject();
        if (subject == null || !subject.equals(runId)) {
            throw com.pmgt.module.ai.pipeline.AiPipelineException.forbidden(
                    "pipeline_token 不属于本次 run：" + runId + "（令牌内的 run 与该路径不一致，拒绝跨 run 写入）");
        }
        return Boolean.TRUE;
    }
}
