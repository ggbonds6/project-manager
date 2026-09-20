package com.pmgt.module.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * §9 #9 {@code POST /api/ai/chat} 的请求体。
 *
 * <p>注意这里<b>没有</b> {@code docIds}：doc_id 是 AI 服务的概念，前端不该知道，
 * 也不该被允许直接指定（否则等于绕过主系统的权限解析）。前端只说「关于哪些附件 / 哪个项目问」。
 */
@Data
public class AiChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题过长（上限 2000 字）")
    private String question;

    /** 作用域：项目 id（可选）。 */
    private Long projectId;

    /**
     * 作用域：附件 id 列表（可选）。
     *
     * <p>这些 id 必须属于当前用户可访问的项目，且已解析入库；
     * 不合法的 id 会被<b>明确拒绝</b>（不是静默过滤），见 {@code AiScopeResolver}。
     */
    private List<Long> attachmentIds;

    /** 会话 id（P0 仅原样回显；多会话历史属 P2）。 */
    private String conversationId;

    /** 期望引用条数上限（可选；AI 服务当前忽略该字段，主系统按契约透传）。 */
    private Integer topK;
}
