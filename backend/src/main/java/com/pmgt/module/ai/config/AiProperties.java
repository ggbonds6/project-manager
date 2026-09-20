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

    /** 问答类请求超时（秒）。实测一次带工具调用的问答约 10s，留足余量。 */
    private int chatTimeoutSeconds = 60;

    /** 其它请求超时（秒）：健康探测/文档列表/任务轮询/上传登记都不该慢。 */
    private int timeoutSeconds = 10;

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
