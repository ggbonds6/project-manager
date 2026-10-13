package com.pmgt.module.ai.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 任务流详情（{@code GET /api/ai/pipeline/runs/{run_id}}、
 * {@code GET /api/ai/tasks/{taskId}/pipeline}、{@code GET /api/ai/attachments/{id}/pipeline}）。
 *
 * <p>= 概要 + 阶段列表 + 产物摘要 + 事件流 + 投影说明，就是 §4.8「任务流视图」要的全部数据
 * （一次取全，抽屉打开即渲染；列表接口不带这些大块内容）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineRunDetailVO extends PipelineRunVO {

    /** 阶段列表（按 seq 升序；历史任务为空数组，前端显示"无阶段明细"而不是报错）。 */
    @JsonProperty("stages")
    private List<PipelineStageVO> stages;

    /** 产物摘要（<b>不含 content</b>；按类型 + 版本倒序）。 */
    @JsonProperty("artifacts")
    private List<ArtifactVO> artifacts;

    /** 事件流（状态迁移审计，按时间升序）。 */
    @JsonProperty("events")
    private List<EventVO> events;

    /**
     * 投影兜底说明：只有当存在"长时间 RUNNING 无上报"的阶段时才有值。
     *
     * <p>它必须显式出现在 data 里（而不是只靠 {@code display_status=STALE}）：
     * 前端要能原样把这句话显示给用户，说明"这是主系统的只读提示、DB 状态没被改"。
     */
    @JsonProperty("projection_note")
    private String projectionNote;

    /** 被判定为"长时间无上报"的阶段键清单（便于前端定位是哪一个节点）。 */
    @JsonProperty("stale_stage_keys")
    private List<String> staleStageKeys;

    /** 投影阈值（秒），把判据一并给出去，避免前端自己编一个。 */
    @JsonProperty("stale_after_seconds")
    private Integer staleAfterSeconds;
}
