package com.pmgt.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 「附件 → AI 服务解析」任务记录（V13）。
 *
 * <p>为什么主系统要自己存一份任务，而不是每次去问 AI 服务：
 * <ol>
 *   <li><b>权限在主系统收口</b>：任务列表要按「用户可访问的项目」过滤，
 *       AI 服务不认主系统的项目/角色，只有主系统能算这个范围；</li>
 *   <li><b>要能回答「谁在什么时候把哪个附件送去解析」</b>：AI 服务的任务只有文件名，
 *       没有触发人与业务归属，审计口径对不上；</li>
 *   <li><b>AI 服务重启/换实例后任务记录会丢</b>（它把任务落成 JSON 且只留最近若干条），
 *       主系统的解析历史不该跟着丢。</li>
 * </ol>
 *
 * <p>状态取值对齐 §9 的任务枚举 {@code QUEUED / RUNNING / DONE / FAILED}；
 * AI 服务侧的 {@code PARSING → RUNNING}、{@code CANCELLED → FAILED} 由适配层翻译，
 * 前端不认 AI 服务的原始枚举。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("attachment_ai_task")
public class AttachmentAiTask extends BaseEntity implements Serializable {

    /** 排队中（已提交 AI 服务，尚未开始解析）。 */
    public static final String QUEUED = "QUEUED";
    /** 解析中。 */
    public static final String RUNNING = "RUNNING";
    /** 解析完成（此时 {@link #docId} 有值）。 */
    public static final String DONE = "DONE";
    /** 失败（含 AI 服务侧取消的适配）。 */
    public static final String FAILED = "FAILED";
    /**
     * 部分成功（V15 新增，§4.4.1）：页级或切片级有失败，但文档可用（可检索）。
     *
     * <p>与 {@link #FAILED} 分开的理由：前端必须能显示"可检索，但有 N 项需人工复核"，
     * 而不是把"能用的文档"标成失败让用户重头再来一遍。
     */
    public static final String PARTIAL = "PARTIAL";
    /**
     * 用户主动取消（V15 新增，§4.4.1）：与 {@link #FAILED} 分开。
     *
     * <p>注意存量口径：{@code AiTaskService.translateStatus} 仍把 AI 侧的 {@code CANCELLED}
     * 翻成 {@code FAILED}（客户端枚举未升级时的兼容行为），本常量只用于流水线回调与显式取消。
     */
    public static final String CANCELLED = "CANCELLED";
    /** 重试耗尽（V15 新增，§4.4.1）：{@code attempts >= 3} 后不再自动重试。 */
    public static final String DEAD = "DEAD";

    /** 任务分类（V15 {@code task_type}）：上传解析 / 重试 / 重建索引 / 批量向量化 / 评测跑批。 */
    public static final String TYPE_PARSE = "PARSE";
    public static final String TYPE_RETRY = "RETRY";
    public static final String TYPE_REINDEX = "REINDEX";
    public static final String TYPE_EMBED = "EMBED";
    public static final String TYPE_EVAL = "EVAL";

    /** 冗余：附件所属项目（附件被删后仍可按项目追溯任务历史）。 */
    private Long projectId;
    /** 主系统附件 id（attachment.id）。 */
    private Long attachmentId;
    /** 冗余：触发时的原始文件名。 */
    private String filename;
    private String status;
    /** 进度 0~100。 */
    private Integer progress;
    /** AI 服务的 upload-task id（对账用，可为空）。 */
    private String aiTaskId;
    /** 解析入库后的 AI 文档 id。 */
    private String docId;
    /** 失败原因原文。 */
    private String errorMsg;

    // ── V15 扩列（13 列；全部可空或带默认值，存量行零影响）───────────────
    // A. 幂等与重建
    /** 原始内容指纹（附件字节 sha1，十六进制）；与 {@link #pipelineRev} 构成幂等键（见 AiPipelineKeys）。 */
    private String contentHash;
    /** 流水线版本串，形如 {@code parse=1,clean=0,chunk=0}；任一段变化即需重建。 */
    private String pipelineRev;
    /** 清洗规则版本号；0 = 未经过清洗阶段（存量行与未开启清洗时的语义）。 */
    private Integer cleanRev;
    // B. 统一并发管理（分类与优先级）
    /** 任务分类：PARSE/RETRY/REINDEX/EMBED/EVAL。 */
    private String taskType;
    /** 优先级：数值越大越优先（抢占式领取按它排序）。 */
    private Integer priority;
    // C. 任务流展示
    /** 当前阶段键（机器可读）：PARSE/CLEAN/CHUNK/EMBED/INDEX，与 ai_pipeline_stage.stage_key 同源。 */
    private String stageKey;
    /** 当前阶段中文名（前端直接显示，如「识别」「清洗」）。 */
    private String stage;
    /** 当前阶段已完成的计量值（页数/切片数/批数）。 */
    private Integer stageDone;
    /** 当前阶段的总计量值；0 表示总量未知（前端显示为不确定进度）。 */
    private Integer stageTotal;
    /** 任务端到端耗时（毫秒）。 */
    private Long elapsedMs;
    /** 本任务已尝试次数（含重试）；≥3 视为重试耗尽（DEAD）。 */
    private Integer attempts;
    // D. 双机抢占式领取
    /** 领取持有者标识（主机名+节点 id）；仅主系统侧与 AI 侧领取者写入。 */
    private String claimedBy;
    /** 领取租约到期时间；过期视为可重新领取（T7 stale claim recovery），按 30s 心跳续约。 */
    private LocalDateTime claimExpiresAt;

    /** 是否处于未结束状态（可被轮询刷新、不会被重试）。 */
    public boolean pending() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }

    /** 是否已进入终态（§4.4.1：PARTIAL 是终态，但允许"补齐重试"）。 */
    public boolean terminal() {
        return DONE.equals(status) || PARTIAL.equals(status) || FAILED.equals(status)
                || CANCELLED.equals(status) || DEAD.equals(status);
    }

    /** 租约是否已过期（{@code claim_expires_at} 为空视为"从未领取"，不算过期）。 */
    public boolean claimExpired(LocalDateTime now) {
        return claimExpiresAt != null && now != null && claimExpiresAt.isBefore(now);
    }
}
