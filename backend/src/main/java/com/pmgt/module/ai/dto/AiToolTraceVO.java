package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #9 的一条工具调用轨迹（前端折叠展示「模型查了什么、查到几条」）。
 *
 * <p>字段映射自 AI 服务 {@code trace[i]}：{@code name} 直取，
 * {@code brief} → {@code summary}，{@code elapsed}（秒）→ {@code elapsedMs}（毫秒），
 * 命中数从 brief 里的「命中 N 段」解析而来（AI 服务未单独返回该计数）。
 */
@Data
public class AiToolTraceVO {

    /** 工具名：{@code search_documents} / {@code read_page} / {@code calculate}。 */
    private String name;
    /** 一行摘要（AI 服务生成的 brief）。 */
    private String summary;
    /** 本工具耗时（毫秒）。 */
    private Long elapsedMs;
    /** 命中条数（仅检索类工具有值，其余为 null）。 */
    private Integer hitCount;
}
