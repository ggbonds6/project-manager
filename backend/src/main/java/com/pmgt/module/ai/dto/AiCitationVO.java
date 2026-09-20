package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #9 的一条引用（前端据此渲染可点击的「文件名 第 N 页」）。
 *
 * <p>{@code attachmentId} 由主系统回填：AI 服务只认 {@code doc_id}，
 * 而前端要跳转的是主系统的附件预览，所以映射（{@code attachment.ai_doc_id}）必须在这里做。
 * 映射不到时给 {@code null}（例如文档已被删），前端会渲染成不可点击的「附件已删除」，
 * <b>不要</b>把这个映射推给前端——那会逼前端再查一次文档列表，也把主系统的知识漏出去。
 */
@Data
public class AiCitationVO {

    /** 引用编号（与答案里 {@code [1]}、{@code [2]} 的标注对应，来自 AI 服务的引用注册表）。 */
    private Integer index;
    /** AI 服务侧文档 id。 */
    private String docId;
    /** 主系统附件 id（映射不到为 null）。 */
    private Long attachmentId;
    private String filename;
    /** 页码，从 1 开始。 */
    private Integer pageNo;
    /** 原文片段（AI 服务已截断）。 */
    private String snippet;
    /** 精排/融合分数。 */
    private Double score;
}
