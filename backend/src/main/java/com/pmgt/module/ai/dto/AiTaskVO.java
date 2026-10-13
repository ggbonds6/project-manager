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

    // ── V15 起的任务流字段（**只增不改**：老前端不读它们，行为与从前一致）─────────
    // 设计文档 §4.9.3「列表行字段（现有表格扩展）」：任务队列这一行要能显示
    // "当前在哪个阶段、阶段进度多少、已经跑了多久"，用户不必点开详情才知道卡在哪一步。

    /** 任务分类（PARSE/RETRY/REINDEX/EMBED/EVAL）。 */
    private String taskType;
    /** 当前阶段键（PARSE/CLEAN/CHUNK/EMBED/INDEX）；存量任务（V15 之前）为空。 */
    private String stageKey;
    /** 当前阶段中文名（识别/清洗/切分/向量化/入库）。 */
    private String stage;
    /** 当前阶段已完成计量值（页数/切片数/批数）。 */
    private Integer stageDone;
    /** 当前阶段总计量值；0 表示未知（前端显示不确定进度）。 */
    private Integer stageTotal;
    /** 任务端到端耗时（毫秒）。 */
    private Long elapsedMs;
    /** 已尝试次数（含重试）。 */
    private Integer attempts;
}
