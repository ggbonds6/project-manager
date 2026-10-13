package com.pmgt.module.ai.pipeline.service;

import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机强校验（设计文档 §4.4.1 的 7 状态 + 11 条合法迁移、§4.4.2 的阶段状态）。
 *
 * <p>本测试钉三件事，缺一条都会让"状态机"退化成注释：
 * <ol>
 *   <li><b>11 条合法迁移一条不少、一条不多</b>（文档说 11 条，代码就必须是 11 条）；</li>
 *   <li>非法迁移被拒且报错里带<b>允许的目标状态</b>（否则 AI 侧只能盲试）；</li>
 *   <li>终态不能被改回非终态（最危险的坏结果：已完成的任务被迟到回调改回"跑动中"）。</li>
 * </ol>
 */
class AiPipelineStateMachineTest {

    @Test
    void 合法迁移规则恰好是设计文档写的11条() {
        List<AiPipelineStateMachine.Rule> rules = AiPipelineStateMachine.transitionRules();

        assertEquals(11, rules.size(), "§4.4.1 的 T1~T11 共 11 条规则，实际：" + rules);
        assertEquals(List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10", "T11"),
                rules.stream().map(AiPipelineStateMachine.Rule::no).toList());
        // T9 是"一条规则两个来源"（PARTIAL/FAILED → QUEUED），所以边比规则多一条
        assertEquals(List.of("PARTIAL", "FAILED"), rules.get(8).froms());
        assertEquals(List.of("QUEUED"), rules.get(8).targets());
    }

    @Test
    void 边表覆盖11条规则的每一对来源与目标() {
        List<String> edges = AiPipelineStateMachine.allTaskTransitions();

        // 11 条规则展开成 12 条边（T9 两个来源）
        assertEquals(12, edges.size(), "边表：" + edges);
        for (AiPipelineStateMachine.Rule rule : AiPipelineStateMachine.transitionRules()) {
            for (String from : rule.froms()) {
                for (String to : rule.targets()) {
                    String label = (from.isEmpty() ? "(空)" : from) + " → " + to;
                    assertTrue(edges.contains(label), "缺少规则 " + rule.no() + " 的边：" + label);
                }
            }
        }
        assertTrue(edges.contains("(空) → QUEUED"), edges.toString());
        assertTrue(edges.contains("QUEUED → RUNNING"), edges.toString());
        assertTrue(edges.contains("QUEUED → FAILED"), edges.toString());
        assertTrue(edges.contains("RUNNING → DONE"), edges.toString());
        assertTrue(edges.contains("RUNNING → PARTIAL"), edges.toString());
        assertTrue(edges.contains("RUNNING → FAILED"), edges.toString());
        assertTrue(edges.contains("RUNNING → QUEUED"), edges.toString());
        assertTrue(edges.contains("RUNNING → CANCELLED"), edges.toString());
        assertTrue(edges.contains("PARTIAL → QUEUED"), edges.toString());
        assertTrue(edges.contains("FAILED → QUEUED"), edges.toString());
        assertTrue(edges.contains("FAILED → DEAD"), edges.toString());
        assertTrue(edges.contains("DONE → QUEUED"), edges.toString());
    }

    @Test
    void 七个任务状态与七个阶段状态都在() {
        assertEquals(List.of("QUEUED", "RUNNING", "DONE", "PARTIAL", "FAILED", "CANCELLED", "DEAD"),
                AiPipelineStateMachine.TASK_STATES);
        assertEquals(List.of("PENDING", "RUNNING", "DONE", "PARTIAL", "SKIPPED", "FAILED", "CANCELLED"),
                AiPipelineStateMachine.STAGE_STATES);
    }

    @Test
    void 非法任务迁移被拒且说明允许的目标状态() {
        // DONE 是终态，唯一出口是 QUEUED（pipeline_rev 变化触发的重建，T11）
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> AiPipelineStateMachine.requireTaskTransition(AttachmentAiTask.DONE, AttachmentAiTask.RUNNING));

        assertEquals(400, e.getStatus(), "非法状态迁移必须是 400（调用方不要重试，按提示改）");
        assertTrue(e.getMessage().contains("非法状态迁移：DONE → RUNNING"), e.getMessage());
        assertTrue(e.getMessage().contains("允许的目标状态：QUEUED"), e.getMessage());
        assertTrue(e.getMessage().contains("§4.4.1"), e.getMessage());
    }

    @Test
    void 终态DEAD与CANCELLED没有任何目标状态() {
        assertEquals(List.of(), AiPipelineStateMachine.allowedTaskTargets(AttachmentAiTask.DEAD));
        assertEquals(List.of(), AiPipelineStateMachine.allowedTaskTargets(AttachmentAiTask.CANCELLED));

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> AiPipelineStateMachine.requireTaskTransition(AttachmentAiTask.CANCELLED, AttachmentAiTask.QUEUED));
        assertTrue(e.getMessage().contains("无（该状态是终态，只能新建任务行重试）"), e.getMessage());
    }

    @Test
    void 未知状态值被拒且列出全部合法值() {
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> AiPipelineStateMachine.requireTaskState("PARSING", "status"));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("合法值：QUEUED/RUNNING/DONE/PARTIAL/FAILED/CANCELLED/DEAD"),
                e.getMessage());
    }

    @Test
    void 同状态重复上报按幂等而不是非法() {
        // AI 侧网络重试会重复发同一条状态：它不该 400（真正的重复写入由"受影响行数"兜住）
        assertEquals(AttachmentAiTask.RUNNING,
                AiPipelineStateMachine.requireTaskTransition(AttachmentAiTask.RUNNING, AttachmentAiTask.RUNNING));
        assertEquals(AiPipelineStage.RUNNING,
                AiPipelineStateMachine.requireStageTransition(AiPipelineStage.RUNNING, AiPipelineStage.RUNNING));
    }

    @Test
    void 阶段状态机允许重跑同一阶段() {
        // PENDING → RUNNING
        assertEquals(AiPipelineStage.RUNNING,
                AiPipelineStateMachine.requireStageTransition(AiPipelineStage.PENDING, AiPipelineStage.RUNNING));
        // RUNNING → 各终态
        for (String to : List.of(AiPipelineStage.DONE, AiPipelineStage.PARTIAL,
                AiPipelineStage.FAILED, AiPipelineStage.CANCELLED, AiPipelineStage.SKIPPED)) {
            assertEquals(to, AiPipelineStateMachine.requireStageTransition(AiPipelineStage.RUNNING, to));
        }
        // 终态 → RUNNING/PENDING 是合法的（同一阶段重跑 = 更新同一行、attempts+1）
        assertEquals(AiPipelineStage.RUNNING,
                AiPipelineStateMachine.requireStageTransition(AiPipelineStage.FAILED, AiPipelineStage.RUNNING));
        assertEquals(AiPipelineStage.PENDING,
                AiPipelineStateMachine.requireStageTransition(AiPipelineStage.DONE, AiPipelineStage.PENDING));
        // SKIPPED → DONE（补齐执行）
        assertEquals(AiPipelineStage.DONE,
                AiPipelineStateMachine.requireStageTransition(AiPipelineStage.SKIPPED, AiPipelineStage.DONE));
    }

    @Test
    void 非法阶段迁移被拒并说明允许目标() {
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> AiPipelineStateMachine.requireStageTransition(AiPipelineStage.PENDING, AiPipelineStage.DONE));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("非法阶段迁移：PENDING → DONE"), e.getMessage());
        assertTrue(e.getMessage().contains("允许的目标状态：RUNNING/SKIPPED"), e.getMessage());
    }
}
