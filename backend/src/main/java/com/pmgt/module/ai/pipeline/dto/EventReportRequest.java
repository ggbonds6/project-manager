package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * {@code POST /api/ai/pipeline/runs/{run_id}/events} 的请求体（状态迁移审计）。
 *
 * <p>这是<b>只追加审计</b>的接口：它把事件写进 {@code ai_pipeline_event}，
 * 但<b>不改变任务状态</b>（状态只能由 claim/release/finish 这些有状态语义的接口改）。
 * 两个状态值都必须落在 7 个合法状态内（否则 400），但<b>不校验迁移合法性</b>：
 * 审计的职责是"如实记录已经发生的事"，而不是替调用方决定它能不能发生
 * （例如 AI 侧要补记一条历史上的 {@code QUEUED→DONE}，那正是需要被看见的异常）。
 */
@Data
public class EventReportRequest {

    /** 迁移前状态（首次进入留空）。 */
    @JsonProperty("from_status")
    @JsonAlias("fromStatus")
    private String fromStatus;

    /** 迁移后状态（必填）。 */
    @JsonProperty("to_status")
    @JsonAlias("toStatus")
    private String toStatus;

    /** 迁移原因（如 "CANCEL_BY_USER:9"、"AI_FAILED:平台 OCR 超时"）。 */
    @JsonProperty("reason")
    @JsonAlias("reason")
    private String reason;

    /** 触发者标识（用户 id / 节点 id / AI 实例名）；留空按 SYSTEM 记。 */
    @JsonProperty("operator")
    @JsonAlias("operator")
    private String operator;

    /** 可选：发生迁移的阶段键（任务级迁移留空）。 */
    @JsonProperty("stage_key")
    @JsonAlias("stageKey")
    private String stageKey;
}
