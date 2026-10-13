package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code POST /api/ai/pipeline/claim} 的响应 {@code data}。
 *
 * <p>一次调用只领到一个任务（{@code limit} 只支持 1）；空队列返回<b>真实 HTTP 404</b>
 * 与"当前没有可领取的任务"}, 而不是 200 + 空对象——AI 侧轮询时"领到了"与"没领到"
 * 必须一眼可分。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ClaimVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("attachment_id")
    private Long attachmentId;

    @JsonProperty("project_id")
    private Long projectId;

    /** 冗余：触发时的文件名（AI 侧日志/展示用，不必再查一次）。 */
    @JsonProperty("filename")
    private String filename;

    /** 幂等键 {@code sha1(content_hash + pipeline_rev)}（主系统派生，不落库）。 */
    @JsonProperty("task_key")
    private String taskKey;

    /** 运行级令牌（详见 {@code AiPipelineTokenService}）：带回来会校验它属于本 run。 */
    @JsonProperty("pipeline_token")
    private String pipelineToken;

    /** 租约到期时刻（本地时间 {@code yyyy-MM-dd HH:mm:ss}）。 */
    @JsonProperty("lease_expires_at")
    private String leaseExpiresAt;

    /** 领取者（回显，便于 AI 侧确认"是我领的"）。 */
    @JsonProperty("claimed_by")
    private String claimedBy;

    /** 任务分类（PARSE/RETRY/REINDEX/EMBED/EVAL）。 */
    @JsonProperty("task_type")
    private String taskType;

    /** 领取后的任务状态（恒为 RUNNING）。 */
    @JsonProperty("status")
    private String status;

    /** 本任务第几次尝试（领取即 +1，退避/死信判定用）。 */
    @JsonProperty("attempts")
    private Integer attempts;

    /** 内容指纹（附件字节 sha1）；AI 侧可据此判断"同一份内容"（如页级缓存键）。 */
    @JsonProperty("content_hash")
    private String contentHash;

    /** 流水线版本串。 */
    @JsonProperty("pipeline_rev")
    private String pipelineRev;

    /** 清洗规则版本。 */
    @JsonProperty("clean_rev")
    private Integer cleanRev;

    /** 优先级（数值越大越优先）。 */
    @JsonProperty("priority")
    private Integer priority;

    /** 领取后应进入的首个阶段键（PARSE；非解析类任务为空）。 */
    @JsonProperty("stage_key")
    private String stageKey;

    /** 首个阶段中文名（识别）。 */
    @JsonProperty("stage_name")
    private String stageName;

    /** 建议心跳间隔（秒）：租约的 1/3，AI 侧照此续约就不会掉租约。 */
    @JsonProperty("heartbeat_seconds")
    private Integer heartbeatSeconds;
}
