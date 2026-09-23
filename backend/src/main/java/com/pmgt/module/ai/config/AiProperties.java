package com.pmgt.module.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 能力服务（ai-backend）接入配置，前缀 {@code pm.ai}。
 *
 * <p>用类而不是散落的 {@code @Value}：这些值成组出现（开关 + 地址 + 令牌 + 两个超时），
 * 而且 {@link #enabled} 关闭时要能一次性解释「为什么不可用」，集中在一个类里最好读。
 */
@ConfigurationProperties(prefix = "pm.ai")
public class AiProperties {

    /**
     * 总开关（默认开）。
     *
     * <p>关闭后 {@code GET /api/ai/health} 仍可用，但返回 {@code available=false} 并说明原因；
     * 其余接口直接给明确业务错误。这样运维可以在没部署 AI 服务的环境里把入口关掉，
     * 而不是让用户看到一堆连接超时。
     */
    private boolean enabled = true;

    /** AI 服务基址；内网地址（浏览器永远不直连它，见方案 §4.3）。 */
    private String baseUrl = "http://127.0.0.1:8100";

    /**
     * 服务间令牌（与用户 JWT 分离）。
     *
     * <p>⚠️ 截至本次实现，AI 服务侧<b>没有任何入站鉴权</b>（无 Filter/Interceptor、
     * 无 Spring Security 依赖）。本配置会按 {@code Authorization: Bearer <token>} 发出，
     * 空值时连请求头都不加——也就是说<b>默认情况下这条调用链上没有任何鉴权</b>，
     * 安全性完全依赖网络隔离。这是已登记的缺口，不在本次主系统改动范围内擅自「修」。
     */
    private String token = "";

    /**
     * 问答类请求超时（秒），即「主系统 → AI {@code /chat}」这一跳的读超时。
     *
     * <p>默认 180：与前端问答超时对齐。整条链必须满足
     * <b>{@code scope_token ≥ 主系统 → AI ≥ 前端}，且四者都不超过 nginx</b>：
     * <pre>
     *   前端 180s ｜ nginx proxy_read_timeout 300s ｜ 主系统→AI 180s ｜ scope_token 300s
     * </pre>
     * 原来这里是 60s：前端已放宽到 180s、token 300s，唯独这一跳还是 60s，
     * 长问答会在这里被掐断（用户报的"容易超时"正是它）。
     * 注意它<b>不能超过 nginx 的 {@code proxy_read_timeout}</b>——否则超时错误会先被网关
     * 变成 504，用户看到的是"网关错误"而不是「AI 服务不可用」，排障方向直接跑偏。
     */
    private int chatTimeoutSeconds = 180;

    /** 其它请求超时（秒）：健康探测/文档列表/任务轮询/上传登记都不该慢。 */
    private int timeoutSeconds = 10;

    /**
     * P2 受控查询的回调地址（§11.2 的 {@code biz_query.url}）。
     *
     * <p>主系统调 AI 的 {@code /chat} 时把这个地址交给它，AI 侧需要业务事实时
     * {@code POST {url}/{entity}} 回调回来。所以它必须是<b>从 AI 容器可达的主系统地址</b>：
     * <ul>
     *   <li>主系统的 {@code pm-backend} 只在 compose 内网 expose 8080，对外只有 nginx 的
     *       {@code WEB_PORT}，因此通常走 nginx 的 {@code /api/} 反代：
     *       同宿主机 {@code http://host.docker.internal:<WEB_PORT>/api/ai/query}；</li>
     *   <li>AI 在另一台机器时填那台能访问到的主系统入口（如 {@code http://10.254.212.106:8080/api/ai/query}）；</li>
     *   <li><b>留空 = 不带 {@code biz_query}</b>：AI 侧据此不注册
     *       {@code query_business_data} 工具，行为与引入 P2 前完全一致（可安全分批发版）。</li>
     * </ul>
     *
     * <p>默认值给 {@code host.docker.internal:8080}（与部署编排里 {@code WEB_PORT} 的默认一致），
     * 这样本机/单机 compose 场景开箱可用；生产按实际入口覆盖。
     */
    private String queryCallbackUrl = "http://host.docker.internal:8080/api/ai/query";

    /**
     * 附件上传成功后是否<b>自动触发</b>解析入库（默认开，方案 §3.3）。
     *
     * <p>为什么要有这个开关：解析要走平台 OCR，是<b>花钱且耗时</b>的动作。
     * 演示与生产都需要能一句话关掉（平台网关维护中、或批量导入历史附件不想触发几百次解析），
     * 而不必改代码或停掉 AI 服务。
     *
     * <p>与 {@link #enabled} 的关系：只有 {@code enabled=true && autoParse=true} 才触发；
     * 关闭时行为与「没有这个功能」完全一致——不触发、也不写任何 AI 状态。
     */
    private boolean autoParse = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getChatTimeoutSeconds() {
        return chatTimeoutSeconds;
    }

    public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
        this.chatTimeoutSeconds = chatTimeoutSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getQueryCallbackUrl() {
        return queryCallbackUrl;
    }

    public void setQueryCallbackUrl(String queryCallbackUrl) {
        this.queryCallbackUrl = queryCallbackUrl;
    }

    public boolean isAutoParse() {
        return autoParse;
    }

    public void setAutoParse(boolean autoParse) {
        this.autoParse = autoParse;
    }

    /** 关闭原因（给前端展示的中文说明，避免只给一个 false）。 */
    public String disabledReason() {
        return "AI 能力服务已在主系统配置中关闭（pm.ai.enabled=false）";
    }
}
