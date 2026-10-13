package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 产物行（详情/产物清单形态）。
 *
 * <p><b>含 content，但不含"全文"</b>：{@code content} 是主系统存的<b>摘要</b>
 * （≤4000 字符，超长截断、真实长度在 {@code char_count}），解析成 JSON 对象给前端直接渲染
 * （不是合法 JSON 时原样给字符串）。全文在 AI 服务 {@code work/}，由 {@code object_key} 指路。
 *
 * <p>为什么这里带 content 而"运行列表"（{@code GET /runs}）一条产物都不带：
 * 列表是"表头级"读取（可能几十行，且 1.5s 轮询），详情/产物清单是"打开抽屉才拉一次"的读取
 * （每个 run ≤6 条、单条 ≤4000 字符）。这正是设计文档 §4.9.2⑥「列表只给表头、详情重新取全量」
 * 的分层，而不是"任何地方都不给内容"。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ArtifactVO extends PipelineVo {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("stage_key")
    private String stageKey;

    /** 五类之一：page_digest/clean_diff/chunk_manifest/embed_batch/index_result。 */
    @JsonProperty("artifact_type")
    private String artifactType;

    /** 版本号（同一 run 同一类型的第几版；重跑递增）。 */
    @JsonProperty("artifact_rev")
    private Integer artifactRev;

    @JsonProperty("object_key")
    private String objectKey;

    /** 产物摘要（已解析成 JSON 给前端渲染；非合法 JSON 时是字符串）。 */
    @JsonProperty("content")
    private JsonNode content;

    /** 落库摘要的字符数。 */
    @JsonProperty("content_chars")
    private Integer contentChars;

    /** 产物全文真实字符数（content 被截断时靠它反映体量）。 */
    @JsonProperty("char_count")
    private Long charCount;

    @JsonProperty("item_count")
    private Integer itemCount;

    @JsonProperty("sha1")
    private String sha1;

    @JsonProperty("create_time")
    private String createTime;
}
