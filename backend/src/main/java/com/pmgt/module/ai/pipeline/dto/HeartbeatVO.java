package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code POST /api/ai/pipeline/runs/{run_id}/heartbeat} 的响应 {@code data}。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class HeartbeatVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    /** 续约后的任务状态（用于确认任务没被别的路径改成终态）。 */
    @JsonProperty("status")
    private String status;

    /** 续约后的租约到期时刻。 */
    @JsonProperty("lease_expires_at")
    private String leaseExpiresAt;

    /** 本次续约的时长（秒）。 */
    @JsonProperty("lease_seconds")
    private Integer leaseSeconds;

    @JsonProperty("claimed_by")
    private String claimedBy;

    /** 建议下次心跳间隔（秒）。 */
    @JsonProperty("heartbeat_seconds")
    private Integer heartbeatSeconds;
}
