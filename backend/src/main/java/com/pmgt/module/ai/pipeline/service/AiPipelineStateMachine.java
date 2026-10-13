package com.pmgt.module.ai.pipeline.service;

import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务级/阶段级<b>状态机强校验</b>（设计文档 §4.4.1 的 7 状态 + 11 条合法迁移、
 * §4.4.2 的阶段状态）。
 *
 * <h2>为什么必须"强校验"而不是"记一条日志"</h2>
 * <p>状态是并发写的东西：AI 服务回调、主系统对账、用户取消、启动恢复四条路径都会写它。
 * 如果非法迁移被静默接受，最容易出现的坏结果是 <b>终态被改回非终态</b>
 * （例如已经 {@code DONE} 的任务被一条迟到的 {@code RUNNING} 回调改回跑动中，
 * 前端永远转圈、对账再也不会收口）。所以这里的选择是：<b>拒绝并说明允许的目标状态</b>，
 * 让调用方（AI 侧）拿到可操作的报错，而不是让它自己猜。
 *
 * <h2>边界（诚实登记）</h2>
 * <p>本校验作用于<b>流水线回写链路</b>（claim / heartbeat / release / finish / 领取回收）。
 * 既有的 {@code AiTaskService} 对账与启动恢复路径<b>不加</b>这层校验，原因是它们要处理
 * <b>V15 之前的存量行</b>（例如"QUEUED 行被 AI 侧直接报 DONE"这种历史形状），
 * 一旦强校验就会把老数据的清理路径堵死。这一点在设计文档与本轮报告里都明确写了。
 */
public final class AiPipelineStateMachine {

    /** 任务级 7 状态（顺序即文档表格顺序，便于报错文案与前端展示一致）。 */
    public static final List<String> TASK_STATES = List.of(
            AttachmentAiTask.QUEUED,
            AttachmentAiTask.RUNNING,
            AttachmentAiTask.DONE,
            AttachmentAiTask.PARTIAL,
            AttachmentAiTask.FAILED,
            AttachmentAiTask.CANCELLED,
            AttachmentAiTask.DEAD);

    /** 任务级终态（PARTIAL 是终态，但允许"补齐重试"→QUEUED，见 T9）。 */
    public static final Set<String> TASK_TERMINAL_STATES = Set.of(
            AttachmentAiTask.DONE, AttachmentAiTask.PARTIAL, AttachmentAiTask.FAILED,
            AttachmentAiTask.CANCELLED, AttachmentAiTask.DEAD);

    /** 阶段级 7 状态（§4.4.2）。 */
    public static final List<String> STAGE_STATES = List.of(
            AiPipelineStage.PENDING, AiPipelineStage.RUNNING, AiPipelineStage.DONE,
            AiPipelineStage.PARTIAL, AiPipelineStage.SKIPPED, AiPipelineStage.FAILED,
            AiPipelineStage.CANCELLED);

    /**
     * 合法迁移<b>规则表</b>（11 条，与 §4.4.1 的 T1~T11 一一对应）。
     *
     * <p>注意"11 条规则"与"多少条边"不是一回事：T9 是
     * {@code PARTIAL/FAILED → QUEUED} <b>一条规则两个来源</b>，所以边数是 12。
     * 文档按"触发场景"计 11 条，代码按规则表存 11 条、展开成 12 条边——
     * 这里写成"规则表"而不是直接写边，就是为了让"文档说 11 条"能在代码里数得出来。
     */
    public record Rule(String no, List<String> froms, List<String> targets, String trigger, String event) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("T1", List.of(""), List.of(AttachmentAiTask.QUEUED),
                    "提交解析（手动/自动/重试）", "SUBMIT:<action>"),
            new Rule("T2", List.of(AttachmentAiTask.QUEUED), List.of(AttachmentAiTask.RUNNING),
                    "抢占式领取成功（受影响行数 = 1）", "CLAIM:<nodeId>"),
            new Rule("T3", List.of(AttachmentAiTask.QUEUED), List.of(AttachmentAiTask.FAILED),
                    "准入被拒（AI 队列满 429 / 附件不可读 / 校验失败）", "REJECT:<原因原文>"),
            new Rule("T4", List.of(AttachmentAiTask.RUNNING), List.of(AttachmentAiTask.DONE),
                    "AI 回调/对账：status=DONE 且 doc_id 非空", "AI_DONE"),
            new Rule("T5", List.of(AttachmentAiTask.RUNNING), List.of(AttachmentAiTask.PARTIAL),
                    "AI 回调：有失败页或失败切片，但产出可用", "AI_PARTIAL:<失败计数>"),
            new Rule("T6", List.of(AttachmentAiTask.RUNNING), List.of(AttachmentAiTask.FAILED),
                    "AI 回调：FAILED（含「未提取到任何文本」）", "AI_FAILED:<error_msg>"),
            new Rule("T7", List.of(AttachmentAiTask.RUNNING), List.of(AttachmentAiTask.QUEUED),
                    "租约过期（claim_expires_at < now）且无人续约", "STALE_CLAIM"),
            new Rule("T8", List.of(AttachmentAiTask.RUNNING), List.of(AttachmentAiTask.CANCELLED),
                    "用户取消（协作式）", "CANCEL_BY_USER:<userId>"),
            new Rule("T9", List.of(AttachmentAiTask.PARTIAL, AttachmentAiTask.FAILED),
                    List.of(AttachmentAiTask.QUEUED),
                    "重试（新建任务行，保留历史；attempts 继承 +1）", "RETRY_BY:<userId>"),
            new Rule("T10", List.of(AttachmentAiTask.FAILED), List.of(AttachmentAiTask.DEAD),
                    "调度器判定 attempts >= 3（不再自动重试）", "RETRY_EXHAUSTED"),
            new Rule("T11", List.of(AttachmentAiTask.DONE), List.of(AttachmentAiTask.QUEUED),
                    "pipeline_rev 变化触发的重建", "REBUILD:<rev 变化摘要>"));

    /**
     * 合法迁移表：键是"来源状态"（{@code ""} 键表示"首次进入"），值是允许的目标状态（<b>有序</b>）。
     *
     * <p>由 {@link #RULES} 派生，保证"规则表"与"校验用的边表"不会分叉；
     * 用 {@code LinkedHashMap} + {@code List}（不是 {@code Set}）是为了让 400 的报错里
     * 目标状态顺序稳定——同一个错误每次给出同样的文案，才方便调用方照着写分支。
     */
    private static final Map<String, List<String>> TASK_TRANSITIONS = expandRules();

    private static Map<String, List<String>> expandRules() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            for (String from : rule.froms()) {
                List<String> targets = m.computeIfAbsent(from, k -> new java.util.ArrayList<>());
                for (String to : rule.targets()) {
                    if (!targets.contains(to)) {
                        targets.add(to);
                    }
                }
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : m.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private AiPipelineStateMachine() {
    }

    /** 规则表（11 条，T1~T11）；文档说 11 条，代码就在这里数得出来。 */
    public static List<Rule> transitionRules() {
        return RULES;
    }

    /**
     * 全部合法迁移边（"from → to"列表）。
     *
     * <p>12 条 > 11 条规则的原因见 {@link #RULES}：T9 一条规则覆盖 PARTIAL 与 FAILED 两个来源。
     * 另外 T10（FAILED→DEAD）与 T9 的 FAILED→QUEUED 都挂在 FAILED 上，所以 FAILED 有两个出边。
     */
    public static List<String> allTaskTransitions() {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> e : TASK_TRANSITIONS.entrySet()) {
            String from = e.getKey().isEmpty() ? "(空)" : e.getKey();
            for (String to : e.getValue()) {
                out.add(from + " → " + to);
            }
        }
        return List.copyOf(out);
    }

    /** 某个来源状态允许的目标状态（有序；未知/终态来源返回空列表）。 */
    public static List<String> allowedTaskTargets(String fromStatus) {
        return TASK_TRANSITIONS.getOrDefault(normalize(fromStatus), List.of());
    }

    /** 校验并归一化任务状态值（未知值 → 400，文案里给出全部合法状态）。 */
    public static String requireTaskState(String status, String field) {
        String s = status == null ? null : status.trim().toUpperCase();
        if (s == null || s.isEmpty()) {
            throw AiPipelineException.badRequest(field + " 不能为空；合法值：" + String.join("/", TASK_STATES));
        }
        if (!TASK_STATES.contains(s)) {
            throw AiPipelineException.badRequest(
                    field + " 非法：" + status + "；合法值：" + String.join("/", TASK_STATES));
        }
        return s;
    }

    /** 校验并归一化阶段状态值。 */
    public static String requireStageState(String status, String field) {
        String s = status == null ? null : status.trim().toUpperCase();
        if (s == null || s.isEmpty()) {
            throw AiPipelineException.badRequest(field + " 不能为空；合法值：" + String.join("/", STAGE_STATES));
        }
        if (!STAGE_STATES.contains(s)) {
            throw AiPipelineException.badRequest(
                    field + " 非法：" + status + "；合法值：" + String.join("/", STAGE_STATES));
        }
        return s;
    }

    /**
     * 任务状态迁移校验。
     *
     * @param fromStatus 当前状态（{@code null}/空 = 首次进入）
     * @param toStatus   目标状态
     * @return 归一化后的目标状态
     * @throws AiPipelineException 400：迁移非法；<b>文案里带上允许的目标状态</b>
     */
    public static String requireTaskTransition(String fromStatus, String toStatus) {
        String from = normalize(fromStatus);
        String to = requireTaskState(toStatus, "status");
        if (from.equals(to)) {
            // "同状态重复上报"不是非法迁移：它必须被当成幂等（AI 侧网络重试会重复发同一条）。
            // 真正的重复写入由各服务自己的"受影响行数"判断兜住（如 finish 的 WHERE status=from）。
            return to;
        }
        List<String> allowed = allowedTaskTargets(from);
        if (!allowed.contains(to)) {
            String fromText = from.isEmpty() ? "(空)" : from;
            throw AiPipelineException.badRequest("非法状态迁移：" + fromText + " → " + to
                    + "。允许的目标状态：" + (allowed.isEmpty() ? "无（该状态是终态，只能新建任务行重试）"
                    : String.join("/", allowed))
                    + "；完整迁移表见 docs/RAG流水线设计与并发管理.md §4.4.1（T1~T11）");
        }
        return to;
    }

    /**
     * 阶段状态迁移校验（§4.4.2）：{@code PENDING→RUNNING}；
     * {@code RUNNING→{DONE,PARTIAL,FAILED,CANCELLED}}；{@code {FAILED,CANCELLED}→PENDING|RUNNING}（重试）；
     * {@code SKIPPED→DONE}（补齐执行）。
     *
     * <p>与任务级不同的两点（刻意放宽，都是"阶段行会被重跑复用"决定的）：
     * ① <b>终态 → RUNNING/PENDING 是合法的</b>（同一阶段重跑 = 更新同一行、attempts+1）；
     * ② 同状态重复上报一律视为幂等（网络重试重复发同一个 {@code RUNNING} 不该 400）。
     */
    public static String requireStageTransition(String fromStatus, String toStatus) {
        String from = fromStatus == null ? "" : fromStatus.trim().toUpperCase();
        String to = requireStageState(toStatus, "status");
        if (from.isEmpty() || from.equals(to)) {
            return to;
        }
        // 用 List 而不是 Set：Set.of 的迭代顺序不保证，会让 400 的报错文案时好时坏
        List<String> allowed = switch (from) {
            case AiPipelineStage.PENDING -> List.of(AiPipelineStage.RUNNING, AiPipelineStage.SKIPPED);
            case AiPipelineStage.RUNNING -> List.of(AiPipelineStage.DONE, AiPipelineStage.PARTIAL,
                    AiPipelineStage.SKIPPED, AiPipelineStage.FAILED, AiPipelineStage.CANCELLED);
            case AiPipelineStage.SKIPPED -> List.of(AiPipelineStage.DONE);
            // 失败/取消/成功/部分成功后重跑同一阶段：允许回到 RUNNING/PENDING（attempts+1）
            case AiPipelineStage.FAILED, AiPipelineStage.CANCELLED, AiPipelineStage.DONE,
                 AiPipelineStage.PARTIAL -> List.of(AiPipelineStage.PENDING, AiPipelineStage.RUNNING);
            default -> List.of();
        };
        if (!allowed.contains(to)) {
            throw AiPipelineException.badRequest("非法阶段迁移：" + from + " → " + to
                    + "。允许的目标状态：" + (allowed.isEmpty() ? "无" : String.join("/", allowed))
                    + "；阶段状态机见 docs/RAG流水线设计与并发管理.md §4.4.2");
        }
        return to;
    }

    /** 状态归一化：null/空 → ""（"首次进入"），其余去空白 + 大写。 */
    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }
}
