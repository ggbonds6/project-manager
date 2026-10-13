package com.pmgt.module.ai.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 解析流水线的<b>产物/证据行</b>（V15 的 {@code ai_pipeline_artifact}）。
 *
 * <h2>只 INSERT，永不 UPDATE（§4.1 P4）</h2>
 * <p>同一阶段重跑 ⇒ 生成<b>新的一行</b>、{@code artifact_rev} 递增（第 1 版、第 2 版…），
 * 这样才谈得上"两次解析差异对比"（API-3）。因此本类的服务层<b>只有 insert 路径</b>：
 * 没有 update 语句，也没有 {@code updateById} 调用——写错一次就会把"历史版本"覆盖掉，
 * 这是产物表最不能犯的错。
 *
 * <h2>为什么不继承 {@code BaseEntity}</h2>
 * <p>V15 的该表<b>没有 {@code update_time} 列</b>（"[只 INSERT] 的表不需要更新时间"）：
 * 继承 {@code BaseEntity} 会让 MyBatis-Plus 在 insert 时带上不存在的列而报错。
 * 这里只显式声明 {@code id / create_time / deleted} 三列，
 * {@code MyMetaObjectHandler.strictInsertFill("updateTime")} 对没有该字段的实体会自动跳过。
 */
@Data
@TableName("ai_pipeline_artifact")
public class AiPipelineArtifact implements Serializable {

    /** 产物类型（五类，与设计文档 §4.3 的列注释一字不差）。 */
    public static final String PAGE_DIGEST = "page_digest";
    public static final String CLEAN_DIFF = "clean_diff";
    public static final String CHUNK_MANIFEST = "chunk_manifest";
    public static final String EMBED_BATCH = "embed_batch";
    public static final String INDEX_RESULT = "index_result";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流水线运行 id（与 {@code ai_pipeline_stage.run_id} 同源）。 */
    private String runId;
    /** 关联 attachment_ai_task.id。 */
    private Long taskId;
    /** 冗余：项目 id（附件删除后仍可按项目追溯）。 */
    private Long projectId;
    /** 冗余：附件 id。 */
    private Long attachmentId;
    /** 产出该产物的阶段：PARSE/CLEAN/CHUNK/EMBED/INDEX（由 artifact_type 唯一确定）。 */
    private String stageKey;
    private String artifactType;
    /** 产物版本（同一 run 同一类型的第几版；重跑递增）。 */
    private Integer artifactRev;
    /** AI 服务侧对象相对键（如 clean/&lt;docId&gt;.json）；仅当该产物有全文落盘时才有值。 */
    private String objectKey;
    /** 产物摘要（JSON 字符串，≤4000 字符；超长截断并由 charCount 反映真实体量）。 */
    private String contentJson;
    /** 产物全文的字符数。 */
    private Long charCount;
    /** 产物的条目数（页数/切片数/批数/写入数，口径随 artifactType）。 */
    private Integer itemCount;
    /** 产物全文的 sha1（"两次解析产物是否一致"的快速比对）。 */
    private String sha1;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
