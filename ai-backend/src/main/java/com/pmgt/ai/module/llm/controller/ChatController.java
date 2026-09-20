package com.pmgt.ai.module.llm.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmgt.ai.common.web.ApiResponse;
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
 * （契约记录见 {@code ai-backend/docs/迁移方案与对照表.md} §3，curl 示例也用的是 {@code question}）。
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

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatIn payload) {
        return ApiResponse.ok(
                qaService.ask(payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null).toDict());
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
    }
}
