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

    private ApiPaths() {
    }

    /** 该 URI 是否落在受控查询接口上（精确匹配前缀本身或前缀 + "/..."）。 */
    public static boolean isServiceQuery(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals(SERVICE_QUERY_PREFIX) || uri.startsWith(SERVICE_QUERY_PREFIX + "/");
    }
}
