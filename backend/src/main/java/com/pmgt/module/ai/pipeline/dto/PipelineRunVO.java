package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一次流水线运行（= 一条 {@code attachment_ai_task}）的<b>概要</b>：列表行与详情头部共用。
 *
 * <p>它同时服务两类读者，故同时给「任务流视图要的当前阶段」与「并发管理要的领取信息」：
 * <ul>
 *   <li>前端：{@code stage/stage_done/stage_total/elapsed_ms/attempts} → 列表行上的阶段进度；</li>
 *   <li>运维/AI：{@code claimed_by/claim_expires_at/task_type/priority} → 谁在跑、租约还剩多久；</li>
 *   <li>幂等：{@code content_hash/pipeline_rev/clean_rev/task_key} → 同一份内容的第几次流水线版本。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PipelineRunVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("attachment_id")
    private Long attachmentId;

    @JsonProperty("project_id")
    private Long projectId;

    /** 项目名（预取，避免前端逐行查项目）。 */
    @JsonProperty("project_name")
    private String projectName;

    /** 触发时的文件名（附件被删后任务历史仍可读，故冗余在任务行上）。 */
    @JsonProperty("filename")
    private String filename;

    @JsonProperty("status")
    private String status;

    /** 是否终态（前端据此决定"要不要继续轮询"）。 */
    @JsonProperty("terminal")
    private Boolean terminal;

    @JsonProperty("task_type")
    private String taskType;

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("doc_id")
    private String docId;

    @JsonProperty("error_msg")
    private String errorMsg;

    @JsonProperty("attempts")
    private Integer attempts;

    @JsonProperty("elapsed_ms")
    private Long elapsedMs;

    /** 当前阶段键（PARSE/CLEAN/CHUNK/EMBED/INDEX）；历史任务（V15 前的行）为空。 */
    @JsonProperty("stage_key")
    private String stageKey;

    /** 当前阶段中文名。 */
    @JsonProperty("stage")
    private String stage;

    @JsonProperty("stage_done")
    private Integer stageDone;

    @JsonProperty("stage_total")
    private Integer stageTotal;

    @JsonProperty("claimed_by")
    private String claimedBy;

    @JsonProperty("claim_expires_at")
    private String claimExpiresAt;

    @JsonProperty("content_hash")
    private String contentHash;

    @JsonProperty("pipeline_rev")
    private String pipelineRev;

    @JsonProperty("clean_rev")
    private Integer cleanRev;

    /** 幂等键（{@code sha1(content_hash + pipeline_rev)}，实时派生不落库）。 */
    @JsonProperty("task_key")
    private String taskKey;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
