package com.pmgt.module.ai.pipeline.service;

import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * run_id ↔ 任务行的<b>唯一查找入口</b>，以及各服务共用的时间格式。
 *
 * <p>为什么值得单独一个类：每个流水线接口都要先把 {@code run-<taskId>} 变成一行任务，
 * 而"run_id 写错了"（400）与"run 不存在"（404）是两种完全不同的失败——
 * 散在 5 个地方各写一遍，迟早有一个写成 500 或写成 400。这里一处定义。
 */
@Component
public class AiPipelineRunLookup {

    /** 与仓库其它接口一致的时间展示口径（本地时区、秒级）。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AttachmentAiTaskMapper taskMapper;

    public AiPipelineRunLookup(AttachmentAiTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * run_id → task_id。
     *
     * @throws AiPipelineException 400：形状不合法（不是 {@code run-<正整数>}）
     */
    public Long requireTaskId(String runId) {
        Long taskId = AiPipelineKeys.taskIdOf(runId);
        if (taskId == null) {
            throw AiPipelineException.badRequest("run_id 非法：" + runId
                    + "；本系统的 run_id 形状为 run-<taskId>（如 run-12，见 claim 响应）");
        }
        return taskId;
    }

    /**
     * 按 run_id 取任务行。
     *
     * @throws AiPipelineException 400 形状不合法 / 404 任务不存在
     */
    public AttachmentAiTask require(String runId) {
        return requireTask(requireTaskId(runId), runId);
    }

    /** 按任务 id 取任务行（404）。 */
    public AttachmentAiTask requireTask(Long taskId, String runIdForMessage) {
        AttachmentAiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw AiPipelineException.notFound("流水线运行不存在：" + runIdForMessage
                    + "（主系统没有这条解析任务记录；它可能已被回滚/清理）");
        }
        return task;
    }

    /** 格式化时间（null 安全）。 */
    public static String format(LocalDateTime time) {
        return time == null ? null : TS.format(time);
    }
}
