package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 单个产物的<b>内容</b>（{@code GET /api/ai/tasks/{taskId}/pipeline/artifacts/{artifactType}}）。
 *
 * <h2>它是"答案 [n] → 切片"的映射入口</h2>
 * <p>溯源链（§4.5.1）的第 4 跳需要"按 {@code chunk_key} 取切片"，而
 * {@code chunk_key} 的清单就在 {@code chunk_manifest} 产物的 {@code content_json} 里。
 * 主系统<b>不解释</b>这份 JSON 的形状（它的字段口径由 AI 侧定义，主系统猜形状一定会猜错），
 * 只保证"原样取出 + 说明是否被截断 + 指路全文对象"。
 *
 * <p>{@code truncated=true} 时不要去猜缺失部分：{@code object_key} 指向 AI 服务
 * {@code work/} 里的全文（当前没有"主系统代理取全文"的接口，属未落地项，见报告）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ArtifactContentVO extends PipelineVo {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("attachment_id")
    private Long attachmentId;

    @JsonProperty("artifact_type")
    private String artifactType;

    @JsonProperty("artifact_rev")
    private Integer artifactRev;

    @JsonProperty("stage_key")
    private String stageKey;

    /** 内容（已解析成 JSON；落库值不是合法 JSON 时是字符串）。 */
    @JsonProperty("content")
    private JsonNode content;

    /** 落库内容的字符数。 */
    @JsonProperty("content_chars")
    private Integer contentChars;

    /** 产物全文真实字符数。 */
    @JsonProperty("char_count")
    private Long charCount;

    /** 内容是否被截断（{@code char_count > content_chars}）。 */
    @JsonProperty("truncated")
    private Boolean truncated;

    @JsonProperty("object_key")
    private String objectKey;

    @JsonProperty("item_count")
    private Integer itemCount;

    @JsonProperty("sha1")
    private String sha1;

    @JsonProperty("create_time")
    private String createTime;

    /** 备注：被截断时说明"全文去哪取"，避免前端把截断的清单当全量用。 */
    @JsonProperty("note")
    private String note;
}
