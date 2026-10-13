package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * {@code POST /api/ai/pipeline/runs/{run_id}/finish} 的请求体（终态）。
 *
 * <p><b>任务终态以主系统为准</b>（设计文档 §4.4.3 第 3 条）：AI 侧报终态，主系统校验迁移合法性
 * 后才落库；非法迁移 400，状态已被别的写入者改掉 409。
 */
@Data
public class FinishRequest {

    /** 终态：DONE/PARTIAL/FAILED/CANCELLED/DEAD（必填；不允许 RUNNING/QUEUED）。 */
    @JsonProperty("status")
    @JsonAlias("status")
    private String status;

    /** 失败原因原文（PARTIAL/FAILED/DEAD 时应带）。 */
    @JsonProperty("error_msg")
    @JsonAlias("errorMsg")
    private String errorMsg;

    /** 本次执行实际使用的流水线版本串（AI 侧会补上 embed 段），用于重建判定。 */
    @JsonProperty("pipeline_rev")
    @JsonAlias("pipelineRev")
    private String pipelineRev;

    /** 本次执行实际使用的清洗规则版本。 */
    @JsonProperty("clean_rev")
    @JsonAlias("cleanRev")
    private Integer cleanRev;

    /**
     * 可选：本次解析产出的 AI 文档 id。
     *
     * <p>契约里没有这个字段，是<b>只增</b>的可选扩展。设计文档 §4.4.1 定义
     * {@code DONE = doc_id 有值}，而本批 finish 报文是 doc_id 唯一的"AI 侧主动告知"通道
     * （另一条是主系统对账，它需要 ai_task_id，拉取模式下不一定有）。
     * 主系统<b>不强制</b>它：不带就落 DONE，附件索引状态保持"解析中"并在响应里
     * 用 {@code note} 说明"等对账回填 doc_id"——不假装成功，也不因为缺一个扩展字段就拒收终态。
     */
    @JsonProperty("doc_id")
    @JsonAlias("docId")
    private String docId;
}
