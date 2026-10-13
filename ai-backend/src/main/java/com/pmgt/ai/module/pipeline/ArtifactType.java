package com.pmgt.ai.module.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产物类型（设计 §4.3 的 {@code ai_pipeline_artifact.artifact_type} 五类，逐字一致）。
 *
 * <p>铁律：产物行<b>只 INSERT</b>（同一阶段重跑生成新行、{@code artifact_rev} 递增），
 * 这样才能做"两次解析差异对比"。所以本类只负责"类型 + 上报体的组装"，
 * <b>不做任何 upsert</b>。
 *
 * <p>另一条边界（I2）：DB 只存<b>摘要</b>（{@code content} ≤4000 字符，超长由主系统截断并记
 * {@code char_count}），全文留在 AI 服务 {@code work/} 下、由 {@code object_key} 指路。
 */
public enum ArtifactType {

    /** 页级摘要（页数、空页数、逐页字符数/来源/置信度）——Q1/Q3 的数据源。 */
    PAGE_DIGEST("page_digest"),
    /** 清洗前后对照（本批尚无清洗 ⇒ 上报 {@code note} 说明，不造假数据）。 */
    CLEAN_DIFF("clean_diff"),
    /** 切片清单（切片数、平均/最大长度、表格片数、切片键样本）——Q5 的数据源。 */
    CHUNK_MANIFEST("chunk_manifest"),
    /** 向量化批次统计（新算/复用/失败/批数/耗时）——Q6 的数据源。 */
    EMBED_BATCH("embed_batch"),
    /** 写索引结果（written/skipped/failed/耗时 + 索引名）。 */
    INDEX_RESULT("index_result");

    private final String key;

    ArtifactType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * 上报体（snake_case，兼容 camelCase；契约见设计 §4.4.3）。
     *
     * @param content    ≤4000 字符的摘要（可为 {@code null}）
     * @param objectKey  全文在 AI 服务 {@code work/} 下的路径（可为 {@code null}）
     * @param charCount  全文的真实字符数（截断前的口径）
     * @param sha1       摘要的 sha1（用于"同类型两次上报是否真的一样"）
     * @param itemCount  该产物的条目数（页数/切片数/批数）
     * @param note       人读说明（<b>跳过类产物必须写清原因</b>）
     */
    public Map<String, Object> body(String content, String objectKey, Integer charCount, String sha1,
                                    Integer itemCount, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifact_type", key);
        if (content != null) {
            out.put("content", content);
        }
        if (objectKey != null) {
            out.put("object_key", objectKey);
        }
        if (charCount != null) {
            out.put("char_count", charCount);
        }
        if (sha1 != null) {
            out.put("sha1", sha1);
        }
        if (itemCount != null) {
            out.put("item_count", itemCount);
        }
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }
}
