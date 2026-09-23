package com.pmgt.module.ai.query;

import com.pmgt.common.api.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 受控查询（结构化问答）的 RPC 入口——§11.3 / §11.4。
 *
 * <pre>
 * POST /api/ai/query/{entity}      entity ∈ projects | contracts | payments | stats
 * Header: Authorization: Bearer &lt;scope_token&gt;
 * Body:   {"filters": {...}, "limit": 20}
 * 200:    {"code":0,"data":{"rows":[],"unit":"个","caliber":"…","data_time":"…","scope":"…"}}
 * </pre>
 *
 * <h2>它为什么不在 {@code AiController} 里</h2>
 * <p>{@code AiController} 是<b>浏览器</b>调用的（用户 JWT、按 §9 的 camelCase 契约）；
 * 本接口是<b>AI 服务</b>调用的（作用域令牌、按 §11 的 snake_case 契约、真实 HTTP 状态码）。
 * 两者鉴权链、契约、调用方都不同，放一个类里迟早有人把 {@code @RequireRole}
 * 或用户 JWT 的约定顺手带过来。
 *
 * <h2>安全边界（§11.2）</h2>
 * <ul>
 *   <li>本接口<b>不</b>读用户登录态：调用方没有登录态，唯一凭据是 {@code scope_token}；</li>
 *   <li>范围由 {@link ScopeTokenFilter} 解析后放进 {@link AiQueryScopeContext}，
 *       业务层只认它；请求体里出现的 {@code projectId} 若不在范围内一律 403（§11.6）；</li>
 *   <li>字段白名单在 {@link AiQueryService} 内——不收 SQL / 表达式 / 字段名拼接。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/query")
public class AiQueryController {

    private final AiQueryService queryService;

    public AiQueryController(AiQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 执行一次受控查询。
     *
     * <p>成功（含空结果）都是 {@code code=0} 的 200；越权 403、filters 非法 400、
     * 令牌无效 401 都是<b>真实 HTTP 状态码</b> + 同一句话的信封（见 {@link AiQueryException}）。
     */
    @PostMapping("/{entity}")
    public R<AiQueryData> query(@PathVariable String entity,
                               @RequestBody(required = false) AiQueryRequest request) {
        return R.ok(queryService.query(entity, request));
    }
}
