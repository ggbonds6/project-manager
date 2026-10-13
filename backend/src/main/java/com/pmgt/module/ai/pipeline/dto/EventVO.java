package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 状态迁移事件（审计流）。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class EventVO extends PipelineVo {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("stage_key")
    private String stageKey;

    @JsonProperty("from_status")
    private String fromStatus;

    @JsonProperty("to_status")
    private String toStatus;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("operator")
    private String operator;

    @JsonProperty("create_time")
    private String createTime;
}
