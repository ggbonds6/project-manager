package com.pmgt.ai.module.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 主系统在 {@code /chat} 请求里给出的**受控查询通道**（契约见 {@code docs/AI前端与集成方案.md} §11.2）。
 *
 * <pre>
 * "biz_query": { "url": "http://&lt;主系统可达地址&gt;/api/ai/query",
 *                "scope_token": "&lt;短时效 JWT，exp ≤ 120s&gt;",
 *                "entities": ["projects", "contracts", "payments", "stats"] }
 * </pre>
 *
 * <h2>它为什么是<b>请求级</b>对象</h2>
 *
 * <p>地址与凭据都由主系统**每次问答现给**：AI 服务不直连库、不持有长期凭据、也不读任何配置文件里的库地址
 * （§11.1 的"反向回调"）。所以它不是 {@code @Component}，而是随请求一路<b>显式传参</b>
 * （{@code ChatController} → {@code QaService} → {@code ToolAgent} → {@code Tools#execute}），
 * 与 {@link CitationRegistry} 同一个套路：单例 Bean 绝不持有请求级状态。
 *
 * <p>⚠️ <b>{@code scopeToken} 是唯一授权凭据，任何日志、工具轨迹、响应体里都不得回显它</b>——
 * 本类刻意不加 {@code toString()}（record 默认的 {@code toString()} 会打出 token）以外的任何输出，
 * 且调用方只把它放进 HTTP Header。
 *
 * @param url        主系统受控查询接口的基地址，实际请求 {@code POST {url}/{entity}}
 * @param scopeToken 短时效 JWT（claims: sub=userId, projects=[...], exp≤120s）
 * @param entities   本次授权可查的实体清单（主系统给）；为空表示不额外收窄
 */
public record BizQuerySpec(
        String url,
        @JsonProperty("scope_token") @JsonAlias("scopeToken") String scopeToken,
        List<String> entities) {

    /**
     * 通道是否可用（地址与凭据都在）。
     *
     * <p>字段缺失时<b>不注册</b>该工具（{@link Tools#schemas(BizQuerySpec)}），行为与本版本前完全一致；
     * 字段传了但内容为空属于主系统侧配置错误——工具照常注册，每次调用返回结构化错误，
     * 让模型与人都能一眼看出"是通道没配好"，而不是静默答成"系统里没有"。
     */
    public boolean usable() {
        return url != null && !url.isBlank() && scopeToken != null && !scopeToken.isBlank();
    }

    /**
     * 本次问答是否允许查询该实体。
     *
     * <p>{@code entities} 为空表示主系统没给收窄清单（四个实体都试），一旦给了就只认清单内的——
     * 越界不是"没有数据"，必须让模型区分开（§11.6 的 403 语义）。
     */
    public boolean allows(String entity) {
        return entities == null || entities.isEmpty() || entities.contains(entity);
    }
}
