package com.pmgt.module.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * §9 #9 的响应 {@code data}。
 *
 * <p>{@code degraded} / {@code notice} 是「不许含糊」的落点：
 * 若 AI 服务报告向量检索不可用而只走了关键词召回，这里必须为 true 并把原因写进 notice，
 * 前端据此提示「本次结果可能不完整」——而不是让用户以为「文档里就没有」。
 */
@Data
public class AiChatResponse {

    /** 原样回显（P0 不做多会话管理）。 */
    private String conversationId;
    private String answer;
    private List<AiCitationVO> citations = new ArrayList<>();
    /** P0 恒为空数组（受控查询工具属 P2）。 */
    private List<AiSystemDataVO> systemData = new ArrayList<>();
    private List<AiToolTraceVO> toolTrace = new ArrayList<>();
    /** 本次是否发生降级（向量检索不可用等）。 */
    private boolean degraded;
    /** 降级/异常提示原文；无则 null。 */
    private String notice;
    /** 本次问答总耗时（毫秒，含主系统作用域解析与 AI 服务调用）。 */
    private Long elapsedMs;
    /** 留痕记录 id（ai_ask_log.id），便于用户报障时直接定位。 */
    private Long logId;
}
