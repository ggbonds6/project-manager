package com.pmgt.module.ai.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 流水线<b>状态迁移事件</b>（V15 的 {@code ai_pipeline_event}）：只追加，回答
 * "谁 / 何时 / 为什么把状态改成了什么"。
 *
 * <p>为什么状态列之外还要一张事件表：任务行的 {@code status} 只有一个当前值，
 * 而排障要问的是"它中间经过了什么、被谁改的"。典型场景——
 * 用户说"我点了取消但它又跑起来了"：事件流里能同时看到
 * {@code RUNNING→CANCELLED（CANCEL_BY_USER:9）} 与 {@code CANCELLED→QUEUED（RETRY_BY:9）}。
 *
 * <p>该表<b>没有 {@code update_time}，也没有 {@code deleted}</b>：审计流水只增不减，
 * 清理由保留期作业按时间做（§4.7），不搞逻辑删除——"被逻辑删除的审计"等于没有审计。
 * 故本实体不继承 {@code BaseEntity}。
 */
@Data
@TableName("ai_pipeline_event")
public class AiPipelineEvent implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 流水线运行 id。 */
    private String runId;
    /** 关联 attachment_ai_task.id。 */
    private Long taskId;
    /** 冗余：项目 id。 */
    private Long projectId;
    /** 发生迁移的阶段键（任务级迁移为空）。 */
    private String stageKey;
    /** 迁移前状态（首次进入为空）。 */
    private String fromStatus;
    /** 迁移后状态。 */
    private String toStatus;
    /** 迁移原因（如 "CLAIM:<nodeId>"、"AI_FAILED:<原因>"、"租约过期重新领取"）。 */
    private String reason;
    /** 触发者标识：主系统用户 id / 节点 id / AI 服务实例名；自动流转记 SYSTEM 或节点名。 */
    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
