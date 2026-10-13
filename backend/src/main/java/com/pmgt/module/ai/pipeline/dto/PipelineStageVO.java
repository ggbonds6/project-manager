package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 阶段条上的一个节点（前端 {@code Steps} 直接用）。
 *
 * <p>字段就是设计文档 §4.9.3「阶段条字段」那一行：{@code stage_key/status/elapsed_ms/
 * item_count/item_failed}，加上阶段卡片要的 {@code input_digest/output_digest/counters/
 * error_msg/attempts}。所有数值<b>来自接口、前端不二次计算</b>。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PipelineStageVO extends PipelineVo {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("stage_key")
    private String stageKey;

    /** 阶段中文名（识别/清洗/切分/向量化/入库/召回/生成）。 */
    @JsonProperty("stage_name")
    private String stageName;

    @JsonProperty("seq")
    private Integer seq;

    /** 落库状态（原样来自 DB）。 */
    @JsonProperty("status")
    private String status;

    /**
     * 展示用状态：正常时等于 {@link #status}；<b>投影兜底</b>判定为"长时间无上报"时为 {@code STALE}。
     *
     * <p>它只影响读展示，<b>不改 DB 状态</b>（§4.4.3 方案 C 的只读形态）：
     * 主系统不替 AI 侧判死，只把"这条 RUNNING 已经很久没动了"如实告诉界面。
     */
    @JsonProperty("display_status")
    private String displayStatus;

    /** 是否被投影兜底标记（{@code display_status=STALE} 时为 true）。 */
    @JsonProperty("stale")
    private Boolean stale;

    @JsonProperty("elapsed_ms")
    private Long elapsedMs;

    @JsonProperty("item_count")
    private Integer itemCount;

    @JsonProperty("item_failed")
    private Integer itemFailed;

    /** 计数明细（已解析成对象的形态，前端直接渲染"键: 值"列表）。 */
    @JsonProperty("counters")
    private JsonNode counters;

    /** 计数明细原文（解析失败时仍能看到原文，不丢信息）。 */
    @JsonProperty("counters_json")
    private String countersJson;

    @JsonProperty("input_digest")
    private String inputDigest;

    @JsonProperty("output_digest")
    private String outputDigest;

    @JsonProperty("error_msg")
    private String errorMsg;

    @JsonProperty("attempts")
    private Integer attempts;

    @JsonProperty("started_at")
    private String startedAt;

    @JsonProperty("finished_at")
    private String finishedAt;

    @JsonProperty("update_time")
    private String updateTime;
}
