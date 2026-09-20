package com.pmgt.module.ai.dto;

import lombok.Data;

/**
 * §9 #4/#5/#6 的解析任务。
 *
 * <p>{@code status} 已翻译成主系统枚举：AI 服务的 {@code PARSING → RUNNING}、
 * {@code CANCELLED → FAILED}。前端只认 {@code QUEUED / RUNNING / DONE / FAILED}。
 */
@Data
public class AiTaskVO {

    private Long taskId;
    private Long attachmentId;
    private String filename;
    private Long projectId;
    private String projectName;
    /** QUEUED 排队中 / RUNNING 解析中 / DONE 已完成 / FAILED 失败。 */
    private String status;
    /** 0~100。 */
    private Integer progress;
    /** 解析成功后的 AI 文档 id。 */
    private String docId;
    /** 失败原因（原样带出，不美化）。 */
    private String error;
    /** {@code yyyy-MM-dd HH:mm:ss}。 */
    private String createdAt;
    private String updatedAt;
}
