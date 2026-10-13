package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code POST /api/ai/pipeline/runs/{run_id}/release} 的响应 {@code data}。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ReleaseVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    /** 放回后的任务状态（恒为 QUEUED）。 */
    @JsonProperty("status")
    private String status;

    /** 放回前记录的原因（回显，便于 AI 侧日志与主系统事件流对得上）。 */
    @JsonProperty("reason")
    private String reason;

    /** 释放前的领取者（回显）。 */
    @JsonProperty("released_by")
    private String releasedBy;
}
