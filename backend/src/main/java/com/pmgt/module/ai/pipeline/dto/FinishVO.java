package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 终态回调的响应 {@code data}。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class FinishVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("attachment_id")
    private Long attachmentId;

    /** 落库后的任务状态（= 请求里的终态）。 */
    @JsonProperty("status")
    private String status;

    @JsonProperty("doc_id")
    private String docId;

    /** 回写后的附件索引状态（READY/FAILED/PARSING/null=未改动）。 */
    @JsonProperty("attachment_index_status")
    private String attachmentIndexStatus;

    /** 任务端到端耗时（毫秒）。 */
    @JsonProperty("elapsed_ms")
    private Long elapsedMs;

    /**
     * 需要调用方知道的一句实话。
     *
     * <p>典型内容：任务已 DONE 但主系统还没有 {@code doc_id}（finish 未带、对账也还没回填）
     * ⇒ 附件索引状态保持在"解析中"，等对账回填——<b>不假装已经可检索</b>。
     */
    @JsonProperty("note")
    private String note;
}
