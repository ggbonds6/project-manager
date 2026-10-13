package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * {@code POST /api/ai/pipeline/runs/{run_id}/artifacts} 的请求体（追加产物）。
 *
 * <p>产物行<b>只 INSERT</b>：同一 run 同一类型重复上报不会覆盖，而是
 * {@code artifact_rev + 1}（第 2 版），这样"两次解析差异对比"才成立。
 */
@Data
public class ArtifactAppendRequest {

    /** 产物类型：page_digest/clean_diff/chunk_manifest/embed_batch/index_result（必填）。 */
    @JsonProperty("artifact_type")
    @JsonAlias("artifactType")
    private String artifactType;

    /**
     * 产物内容（摘要，不是全文）。对象/数组/字符串都接受：
     * 对象与数组会被序列化后存入 {@code content_json}，字符串按原文存。
     */
    @JsonProperty("content")
    @JsonAlias("content")
    private JsonNode content;

    /** AI 服务侧对象相对键（如 {@code clean/<docId>.json}）；全文落在 AI 服务 work/。 */
    @JsonProperty("object_key")
    @JsonAlias("objectKey")
    private String objectKey;

    /** 产物全文的真实字符数（{@code content} 被截断时用它反映体量）。 */
    @JsonProperty("char_count")
    @JsonAlias("charCount")
    private Long charCount;

    /** 产物全文的 sha1（两次解析是否一致的快速比对）。 */
    @JsonProperty("sha1")
    @JsonAlias("sha1")
    private String sha1;

    /** 产物条目数（页数/切片数/批数/写入数，口径随 artifact_type）。 */
    @JsonProperty("item_count")
    @JsonAlias("itemCount")
    private Integer itemCount;

    /**
     * 备注。
     *
     * <p>V15 的 {@code ai_pipeline_artifact} <b>没有 note 列</b>（本批不新增列），
     * 所以它被并入 {@code content_json}：单独给 note 时存 {@code {"_note":"…"}}，
     * 同时有 content 时存 {@code {"_note":"…","_content":<content>}}。
     * 这样"备注不丢"且"不伪造一列"，代价是读方要认这两个下划线键（见接口说明）。
     */
    @JsonProperty("note")
    @JsonAlias("note")
    private String note;
}
