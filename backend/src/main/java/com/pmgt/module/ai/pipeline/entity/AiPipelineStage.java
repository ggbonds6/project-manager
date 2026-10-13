package com.pmgt.module.ai.pipeline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 解析流水线的<b>阶段行</b>（V15 的 {@code ai_pipeline_stage}）：一行 = 一个任务的一个阶段。
 *
 * <h2>两条与"任务行"相反的语义（照抄自 V15 的表注释与设计文档 §4.1 P3）</h2>
 * <ol>
 *   <li><b>唯一键是 {@code (run_id, stage_key)}</b>：同一阶段重跑 ⇒ <b>更新同一行</b> 并
 *       {@code attempts + 1}，而不是插新行。这样前端阶段条不会因为重试长出重复节点；</li>
 *   <li><b>{@code attempts} 是"这一阶段尝试了几次"</b>：它和任务行的 {@code attempts}
 *       （整份任务重试了几次）不是一个口径，别混用。</li>
 * </ol>
 *
 * <p>它继承 {@link BaseEntity}：V15 的该表确实有 {@code id/create_time/update_time/deleted}
 * 四列（与任务表同形）；而产物表与事件表没有 {@code update_time}，故那两个实体不继承。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_pipeline_stage")
public class AiPipelineStage extends BaseEntity implements Serializable {

    /** 阶段状态（§4.4.2 阶段级状态机）。 */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    /** 部分成功：有 item 失败但整体可用（OCR 失败页 / 部分切片写入失败）。 */
    public static final String PARTIAL = "PARTIAL";
    /** 幂等命中，本次未执行（如"向量缓存全命中"）。 */
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 流水线运行 id（一次解析尝试；本批实现为 {@code run-<taskId>}，见 {@code AiPipelineKeys}）。 */
    private String runId;
    /** 关联 attachment_ai_task.id（冗余，便于按任务过滤）。 */
    private Long taskId;
    /** 冗余：项目 id（按项目过滤/公平调度统计用）。 */
    private Long projectId;
    /** 冗余：附件 id。 */
    private Long attachmentId;
    /** AI 服务的文档 id（PARSE 阶段完成时才有）。 */
    private String docId;
    /** 阶段键：PARSE/CLEAN/CHUNK/EMBED/INDEX（+ 契约里的 RETRIEVE/GENERATE）。 */
    private String stageKey;
    /** 阶段中文名（识别/清洗/切分/向量化/入库），前端阶段条直接显示。 */
    private String stageName;
    /** 阶段顺序（1..7），前端据此排序与画阶段条。 */
    private Integer seq;
    private String status;
    private LocalDateTime startedAt;
    /** 阶段结束时间（进行中为空）。 */
    private LocalDateTime finishedAt;
    /** 阶段耗时（毫秒）。 */
    private Long elapsedMs;
    /** 输入摘要（人读，如「20 页 / 12,340 字 / sha1=ab12cd34」）。 */
    private String inputDigest;
    /** 输出摘要（人读，如「切片 30，其中表格 4，平均 412 字」）。 */
    private String outputDigest;
    /** 本阶段处理的条目总数（口径随阶段：页数/切片数/批数）。 */
    private Integer itemCount;
    /** 本阶段失败的条目数；> 0 时状态应为 PARTIAL。 */
    private Integer itemFailed;
    /** 阶段计数明细（JSON 字符串，如 {"empty_pages":2,"tables":4}）。 */
    private String countersJson;
    /** 失败原因原文（不吞异常）；PARTIAL 时为失败明细摘要。 */
    private String errorMsg;
    /** 本阶段尝试次数（重试递增）。 */
    private Integer attempts;

    /** 是否已进入终态（前端阶段条的"完成/失败"判定）。 */
    public boolean terminal() {
        return DONE.equals(status) || PARTIAL.equals(status) || SKIPPED.equals(status)
                || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
