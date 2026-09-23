package com.pmgt.module.ai.query;

/**
 * 当前请求的受控查询范围（由 {@link ScopeTokenFilter} 写入，请求结束后清理）。
 *
 * <p>与 {@code AuthContext} 分开是刻意的、也是本方案安全边界的一部分：
 * <ul>
 *   <li>{@code AuthContext} = <b>用户登录态</b>（浏览器 → 主系统的用户 JWT）；</li>
 *   <li>{@code AiQueryScopeContext} = <b>本次服务间调用的作用域令牌</b>（AI 服务 → 主系统的 scope_token）。</li>
 * </ul>
 * 受控查询链路上 {@code AuthContext} 一定是空的（没有用户登录），所以业务代码若想"顺手"
 * 从登录态取用户/权限，会直接取到 null 而不是错误的人——这让"忘了走 scope_token"变成一个
 * 立刻可见的错误，而不是一个静默的越权。
 */
public final class AiQueryScopeContext {

    private static final ThreadLocal<AiQueryScope> HOLDER = new ThreadLocal<>();

    private AiQueryScopeContext() {
    }

    public static void set(AiQueryScope scope) {
        HOLDER.set(scope);
    }

    /**
     * 取当前范围；未认证时返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常：过滤器的职责是拒绝请求，业务层拿不到范围说明
     * 请求根本没通过过滤器——那时抛错只会把"没带令牌"说成"系统繁忙"。
     */
    public static AiQueryScope get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
