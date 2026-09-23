package com.pmgt.module.ai.query;

import java.util.List;

/**
 * 一次服务间受控查询的授权范围（§11.2）。
 *
 * <p>它不是"当前登录用户"（{@code AuthContext}）的替代品：受控查询的调用方是 <b>AI 服务</b>，
 * 整条链路上没有用户登录态。能查什么，完全由主系统签发 {@code scope_token} 时写进去的
 * {@code projects} 决定——所以这个记录类是<b>唯一</b>的授权依据，业务代码不许再从请求体
 * 里"补充"任何范围。
 *
 * @param jti      令牌 id（{@code jti}）：把回调次数关联回本次问答用，见 {@link AiQueryUsageTracker}
 * @param userId   提问用户 id（{@code sub}）：仅用于留痕「谁问的」
 * @param userName 提问用户姓名：冗余进 token，供服务间调用写 {@code operate_log} 时不必再查库
 *                 （服务间请求没有 {@code AuthContext}，查库会把留痕变成两次往返）
 * @param projects 本次授权可访问的项目 id 列表；<b>空列表 = 什么都不许查</b>（不是"全部"）
 */
public record AiQueryScope(String jti, Long userId, String userName, List<Long> projects) {

    public AiQueryScope {
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    /**
     * 该项目是否在本次授权范围内。
     *
     * <p>刻意用"白名单包含"而不是"黑名单排除"：token 里没写的一律不可查，
     * 这样新增项目、脏数据都不会意外放开。
     */
    public boolean contains(Long projectId) {
        return projectId != null && projects.contains(projectId);
    }

    /** 留痕用的一句话范围描述（不含任何敏感信息）。 */
    public String describe() {
        if (projects.isEmpty()) {
            return "无（本次授权未包含任何项目）";
        }
        if (projects.size() == 1) {
            return "项目 " + projects.get(0);
        }
        return "共 " + projects.size() + " 个项目：" + brief(projects);
    }

    private static String brief(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(ids.size(), 20);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(ids.get(i));
        }
        if (ids.size() > limit) {
            sb.append('…');
        }
        return sb.toString();
    }
}
