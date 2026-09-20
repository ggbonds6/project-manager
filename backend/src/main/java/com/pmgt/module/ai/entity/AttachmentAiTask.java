package com.pmgt.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

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
    /** 失败（含 AI 服务侧取消）。 */
    public static final String FAILED = "FAILED";

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

    /** 是否处于未结束状态（可被轮询刷新、不会再被重试）。 */
    public boolean pending() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }
}
