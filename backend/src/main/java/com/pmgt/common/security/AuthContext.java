package com.pmgt.common.security;

import java.util.Optional;

/**
 * 当前登录用户上下文（由 AuthFilter 写入，请求结束后清理）。
 */
public final class AuthContext {

    public record Current(Long userId, String account, String name, Role role) {
    }

    private static final ThreadLocal<Current> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Current current) {
        HOLDER.set(current);
    }

    public static Current get() {
        return HOLDER.get();
    }

    public static Optional<Long> userId() {
        Current c = HOLDER.get();
        return c == null ? Optional.empty() : Optional.ofNullable(c.userId());
    }

    public static String userName() {
        Current c = HOLDER.get();
        return c == null ? "" : c.name();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
