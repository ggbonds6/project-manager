package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * {@code POST /api/ai/pipeline/runs/{run_id}/stages/{stage_key}} 的请求体（阶段上报）。
 *
 * <p>幂等语义（契约第 4 条）：同 {@code (run_id, stage_key)} 重复上报 <b>更新同一行</b>
 * 并 {@code attempts + 1}，不会长出第二行——AI 侧网络重试可以放心重发。
 */
@Data
public class StageReportRequest {

    /** 阶段状态：PENDING/RUNNING/DONE/PARTIAL/SKIPPED/FAILED/CANCELLED（必填）。 */
    @JsonProperty("status")
    @JsonAlias("status")
    private String status;

    /** 本阶段条目总数（页数/切片数/批数，口径随阶段）。 */
    @JsonProperty("item_count")
    @JsonAlias("itemCount")
    private Integer itemCount;

    /** 本阶段失败条目数；> 0 且状态为 DONE 时会被记成 PARTIAL 的语义提示（状态仍以报文为准）。 */
    @JsonProperty("item_failed")
    @JsonAlias("itemFailed")
    private Integer itemFailed;

    /** 阶段耗时（毫秒）。 */
    @JsonProperty("elapsed_ms")
    @JsonAlias("elapsedMs")
    private Long elapsedMs;

    /**
     * 阶段计数明细（任意 JSON 对象，如 {@code {"empty_pages":2,"tables":4}}）。
     *
     * <p>用 {@link JsonNode} 而不是 {@code Map<String,Object>}：契约只说"计数明细"，
     * 写成对象/字符串都应被接受并原样存进 {@code counters_json}，不做字段级白名单
     * （这里没有"口径由主系统决定"的问题——它就是 AI 侧的读数）。
     */
    @JsonProperty("counters")
    @JsonAlias("counters")
    private JsonNode counters;

    /** 输入摘要（人读，如「20 页 / 12,340 字 / sha1=ab12cd34」）。 */
    @JsonProperty("input_digest")
    @JsonAlias("inputDigest")
    private String inputDigest;

    /** 输出摘要（人读，如「切片 30，其中表格 4，平均 412 字」）。 */
    @JsonProperty("output_digest")
    @JsonAlias("outputDigest")
    private String outputDigest;

    /** 失败原因原文（不吞异常）；PARTIAL 时为失败明细摘要。 */
    @JsonProperty("error_msg")
    @JsonAlias("errorMsg")
    private String errorMsg;

    /**
     * 可选：本阶段的产出文档 id（PARSE 阶段完成时有值）。
     *
     * <p>契约没有这个字段，这里是<b>只增</b>的可选扩展：带上它，主系统就能在阶段边界
     * 立刻把 {@code doc_id} 落到任务行/附件上，而不必等 {@code /finish} 或对账。
     * 不带也完全不影响既有行为。
     */
    @JsonProperty("doc_id")
    @JsonAlias("docId")
    private String docId;

    /** 可选：阶段中文名的覆盖值（默认按 stage_key 取主系统内置中文名）。 */
    @JsonProperty("stage_name")
    @JsonAlias("stageName")
    private String stageName;
}
