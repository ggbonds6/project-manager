package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** {@code POST /api/ai/pipeline/runs/{run_id}/release} 的请求体（放回队列）。 */
@Data
public class ReleaseRequest {

    /** 领取者标识；用于事件流的 operator，并校验"放回的是不是自己领的那个"。 */
    @JsonProperty("claimed_by")
    @JsonAlias("claimedBy")
    private String claimedBy;

    /** 放回原因（写进事件流，便于回答"为什么这份文档又排队了"）。 */
    @JsonProperty("reason")
    @JsonAlias("reason")
    private String reason;
}
