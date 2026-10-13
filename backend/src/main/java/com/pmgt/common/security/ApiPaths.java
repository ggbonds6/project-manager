package com.pmgt.common.security;

/**
 * 接口路径常量（跨模块共享，避免同一个前缀在两处各写一遍）。
 */
public final class ApiPaths {

    /**
     * 服务间受控查询前缀（{@code POST /api/ai/query/{entity}}，契约见 docs/AI前端与集成方案.md §11.3）。
     *
     * <p>它被两处引用，且两处必须一致：
     * <ul>
     *   <li>{@code AuthFilter}：对该前缀<b>旁路</b>（这条链路没有用户登录态）；</li>
     *   <li>{@code ScopeTokenFilter}：只对该前缀生效（用作用域令牌独立鉴权）。</li>
     * </ul>
     * 常量放这里就是为了让"旁路"与"接管"永远指向同一个集合——
     * 谁改宽了谁窄了，都会立刻表现为"要么 401 打不开，要么无人鉴权"，
     * 而不是一个安静的口子。
     */
    public static final String SERVICE_QUERY_PREFIX = "/api/ai/query";

    /**
     * AI 服务<b>回写流水线</b>的前缀（{@code POST /api/ai/pipeline/**}，契约见
     * docs/RAG流水线设计与并发管理.md §4.4.3 方案 B）。
     *
     * <p>同样被两处引用、也必须一致：
     * <ul>
     *   <li>{@code AuthFilter}：对该前缀<b>旁路</b>（这条链路没有用户登录态）；</li>
     *   <li>{@code AiPipelineTokenFilter}：只对该前缀生效（用 {@code X-AI-Service-Token} 独立鉴权）。</li>
     * </ul>
     *
     * <p>与 {@link #SERVICE_QUERY_PREFIX} 的区别必须写清，否则后人会把两个口子合并：
     * 受控查询是<b>主系统读数据给 AI 服务</b>（凭据是每次问答现签的短时 {@code scope_token}，
     * 作用域受限）；本前缀是 <b>AI 服务写数据回主系统</b>（凭据是部署期配置的服务间令牌，
     * 调用方是 AI 服务这个进程本身）。前者需要"范围"，后者需要"身份"。
     */
    public static final String SERVICE_PIPELINE_PREFIX = "/api/ai/pipeline";

    private ApiPaths() {
    }

    /** 该 URI 是否落在受控查询接口上（精确匹配前缀本身或前缀 + "/..."）。 */
    public static boolean isServiceQuery(String uri) {
        return matches(SERVICE_QUERY_PREFIX, uri);
    }

    /** 该 URI 是否落在 AI 服务流水线回写接口上（精确匹配前缀本身或前缀 + "/..."）。 */
    public static boolean isServicePipeline(String uri) {
        return matches(SERVICE_PIPELINE_PREFIX, uri);
    }

    private static boolean matches(String prefix, String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }
}
