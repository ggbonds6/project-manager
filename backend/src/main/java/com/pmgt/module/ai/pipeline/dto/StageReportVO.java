package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 阶段上报的响应 {@code data}（回显落库结果，便于 AI 侧确认幂等语义）。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class StageReportVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("stage_key")
    private String stageKey;

    @JsonProperty("stage_name")
    private String stageName;

    /** 阶段顺序（1..7），前端阶段条按它排序。 */
    @JsonProperty("seq")
    private Integer seq;

    @JsonProperty("status")
    private String status;

    /** 本阶段累计尝试次数（同键重复上报会递增，这是幂等语义的可见证据）。 */
    @JsonProperty("attempts")
    private Integer attempts;

    @JsonProperty("item_count")
    private Integer itemCount;

    @JsonProperty("item_failed")
    private Integer itemFailed;

    @JsonProperty("elapsed_ms")
    private Long elapsedMs;
}
