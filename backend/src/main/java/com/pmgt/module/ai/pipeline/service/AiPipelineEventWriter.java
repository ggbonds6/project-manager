package com.pmgt.module.ai.pipeline.service;

import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import org.springframework.stereotype.Component;

/**
 * 状态迁移事件的<b>唯一写入入口</b>（只 INSERT）。
 *
 * <p>集中在这里的原因有两个：① 事件里的 {@code run_id/task_id/project_id} 每次都要一起带上，
 * 散着写必然漏字段（漏了 project_id，按项目过滤就查不到这条审计）；
 * ② 审计的"只追加"语义需要一个地方保证——全仓只有本类 import 了事件 Mapper。
 *
 * <p>写入失败<b>不吞</b>：事件丢了等于状态变化没有痕迹，而状态变化的痕迹正是这张表存在的理由。
 * 调用方（claim/release/finish）都在事务内，失败就整体回滚，宁可让 AI 侧重试一次。
 */
@Component
public class AiPipelineEventWriter {

    private final AiPipelineEventMapper eventMapper;

    public AiPipelineEventWriter(AiPipelineEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /** 记一条事件；{@code operator} 为空时记 {@code SYSTEM}（自动流转）。 */
    public AiPipelineEvent record(String runId,
                                  Long taskId,
                                  Long projectId,
                                  String stageKey,
                                  String fromStatus,
                                  String toStatus,
                                  String reason,
                                  String operator) {
        AiPipelineEvent event = new AiPipelineEvent();
        event.setRunId(runId);
        event.setTaskId(taskId);
        event.setProjectId(projectId);
        event.setStageKey(stageKey);
        event.setFromStatus(trimTo(fromStatus, 16));
        event.setToStatus(trimTo(toStatus, 16));
        event.setReason(trimTo(reason, 500));
        event.setOperator(trimTo(operator == null || operator.isBlank() ? "SYSTEM" : operator, 64));
        eventMapper.insert(event);
        return event;
    }

    /**
     * 截断到列宽（V15 的列宽：from/to 16、reason 500、operator 64）。
     *
     * <p>为什么要截断而不是让数据库报错：{@code reason} 常带"失败原因原文"，
     * 超长会让整条状态迁移回滚——那等于"因为备注太长，状态没落库"。
     * 截断丢的是尾巴，保住的是状态本身（原文另有 {@code error_msg} 列承载）。
     */
    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
