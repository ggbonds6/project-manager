package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #2 文档库列表里的一条记录。
 *
 * <p>「文档」是 AI 服务的概念，「附件」是主系统的概念，两者靠
 * {@code attachment.ai_doc_id} 关联；因此这里同时带上 {@code attachmentId} /
 * {@code projectId} / {@code projectName}，前端才能显示「来源项目」并跳转附件预览。
 * 关联不上的（AI 服务侧有、主系统侧没有对应附件）字段为 null，不猜。
 */
@Data
public class AiDocumentVO {

    /** AI 服务侧文档 id。 */
    private String docId;
    private String filename;
    /** 来源项目（主系统侧映射，映射不到为 null）。 */
    private Long projectId;
    private String projectName;
    /** 主系统附件 id（映射不到为 null）。 */
    private Long attachmentId;
    private Integer pageCount;
    /**
     * 切片数。
     *
     * <p>⚠️ <b>估算值</b>：AI 服务的 {@code GET /documents} 元信息里没有切片数
     * （切片是现算的，只落向量缓存），逐个调 {@code /documents/{id}} 拉全文再算切片
     * 会让列表页产生 N 次全量传输。这里按 AI 服务的切片规则（每片约 500 字、不跨页）
     * 用 {@code chars / 500} 估算，只用于展示量级；需要精确值时由 AI 服务在元信息里补字段。
     */
    private Integer chunkCount;
    private Long sizeBytes;
    /** 入库时间（转成 {@code yyyy-MM-dd HH:mm:ss}）。 */
    private String indexedAt;
    /** NOT_PARSED / PARSING / READY / FAILED（按主系统枚举输出）。 */
    private String indexStatus;
    /** 主系统侧最近一次解析失败原因（来自 attachment_ai_task）。 */
    private String error;
}
