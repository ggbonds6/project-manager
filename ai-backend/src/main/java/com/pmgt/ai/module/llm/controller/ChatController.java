package com.pmgt.ai.module.llm.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.module.llm.BizQuerySpec;
import com.pmgt.ai.module.llm.QaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 文档问答：基于已入库文档，用工具调用（检索 → 读页 → 计算）产出**带来源页码**的答案。
 *
 * <p>请求体字段：{@code question} / {@code doc_ids} / {@code history}
 * （契约记录见 {@code ai-backend/docs/迁移方案与对照表.md} §3，curl 示例也用的是 {@code question}）；
 * 另可带 {@code biz_query}（可选，见下）。
 *
 * <p>响应 {@code data}：{@code answer / trace / scope / llm / stopped_reason / error / citations}，
 * 其中 {@code citations} 是本次改造新增的<b>结构化引用表</b>
 * （{@code index / doc_id / filename / page_no / snippet / score}，顺序 = 编号顺序），
 * 前端据此把答案里的 {@code [1]} 变成"跳到该附件第 N 页"的链接；
 * 既有字段一个都没动（只增不改）。
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final QaService qaService;

    /**
     * 问答入口。
     *
     * <p>⚠️ 为什么按 {@code biz_query} 分两个分支调 {@code QaService}：
     * <b>不传 {@code biz_query} 的请求必须走原来的四参入口</b>——那条路径的行为、工具清单、
     * 提示词都与本版本前逐字节一致（§11.2 的兼容性要求，也是"分批发版"的前提）。
     * 传了才走带通道的五参入口（多注册一个工具、多一段业务路由提示词）。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatIn payload) {
        BizQuerySpec bizQuery = payload.getBizQuery();
        QaService.QaResult result = bizQuery == null
                ? qaService.ask(payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null)
                : qaService.ask(
                        payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null, bizQuery);
        return ApiResponse.ok(result.toDict());
    }

    /**
     * {@code /chat} 的请求体。
     *
     * <p>⚠️ <b>字段名的事实描述（2026-09-20 修正）</b>：本类原先的注释声称
     * "请求体字段与 Python 版一致（{@code question} / {@code doc_ids} / {@code history}）"，
     * 但 DTO 字段名是 {@code docIds}、服务也没有配 {@code SNAKE_CASE} 命名策略
     * （见 {@code application.yml}：只有 {@code default-property-inclusion}），
     * 即<b>线上实际只认 camelCase 的 {@code docIds}</b>，与文档/历史契约写的 {@code doc_ids} 不符——
     * 主系统按老实契约发 {@code doc_ids} 时，这个字段会被静默丢弃（表现为"检索范围没生效、答了全库"，
     * 而不是报错，最难排查）。
     *
     * <p>修法是<b>两种写法都收</b>：序列化主名用 snake_case（对齐文档契约），
     * 同时用 {@link JsonAlias} 兼容 camelCase（老调用方已经这么发了，不能把它们的请求打挂）。
     */
    @Data
    public static class ChatIn {

        private String question = "";

        /** 限定检索范围；{@code doc_ids}（主名，见文档契约）与 {@code docIds}（别名）都接受。 */
        @JsonProperty("doc_ids")
        @JsonAlias({"docIds"})
        private List<String> docIds;

        private List<Map<String, Object>> history;

        /**
         * P2 受控查询通道（{@code docs/AI前端与集成方案.md} §11.2，**可选**）。
         *
         * <pre>
         * "biz_query": { "url": "http://…/api/ai/query",
         *                "scope_token": "&lt;短时效 JWT&gt;",
         *                "entities": ["projects","contracts","payments","stats"] }
         * </pre>
         *
         * <p><b>不传＝不注册 {@code query_business_data} 工具</b>，行为与本版本前完全一致；
         * 传了才把该工具（及对应提示词段落）装进这次问答。{@code scope_token} 是唯一授权凭据，
         * 只用于回调主系统的 Authorization Header，AI 侧不记录、不回显。
         *
         * <p>主名 {@code biz_query} 对齐契约，同时兼容 {@code bizQuery}（同 {@code docIds} 的处理）。
         */
        @JsonProperty("biz_query")
        @JsonAlias({"bizQuery"})
        private BizQuerySpec bizQuery;
    }
}
