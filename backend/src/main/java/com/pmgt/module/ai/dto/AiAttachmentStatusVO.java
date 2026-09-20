package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #8 {@code GET /api/ai/attachments/status} 的一条附件解析状态。
 *
 * <p>与任务不同：任务的「最新一行」是历史记录，而这里是同一附件的<b>汇总视图</b>，
 * 供附件列表/附件中心给每个附件打标签（未解析 ｜ 解析中（进度）｜ 已可检索 ｜ 解析失败）。
 */
@Data
public class AiAttachmentStatusVO {

    private Long attachmentId;
    /** NOT_PARSED / PARSING / READY / FAILED。 */
    private String indexStatus;
    /** 解析中进度 0~100；未在解析时为 null。 */
    private Integer progress;
    /** 已可检索时的 AI 文档 id。 */
    private String docId;
    /** 失败原因（无则 null）。 */
    private String error;
}
