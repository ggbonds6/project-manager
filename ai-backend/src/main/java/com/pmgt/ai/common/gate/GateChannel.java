package com.pmgt.ai.common.gate;

import com.pmgt.ai.common.config.AiSettings;

import java.util.Locale;

/**
 * L1 业务并发闸门（{@code PipelineGate}）的<b>五条通道</b>（设计 §5.2/§5.10.2 第 1 条）。
 *
 * <p>为什么按"通道"分而不是"按模型分"：模型级的在飞上限已经由 L2（{@code ModelRegistry}）
 * 管住了；L1 要回答的是另一个问题——<b>谁可以开始干活</b>。
 * 这两件事的粒度天然不同（一次解析任务会调上百次 OCR、几十批 embed），
 * 所以 L1 按"任务类型"分池，L2 按"模型"分池，两层单向（先 L1 后 L2）。
 *
 * <p>通道的配置读法、默认值与依据见 {@link AiSettings.Gate} 的注释与 {@code ai-backend/README.md}。
 */
public enum GateChannel {

    /** 交互式：{@code /chat}、自检、检索调试。有人等（前端 180s）、延迟敏感、**不可重试**。 */
    INTERACTIVE("interactive", "交互式", false),

    /** 批量解析：上传解析（含自动触发）与重试解析。无人等、幂等、OCR 密集。 */
    BATCH_PARSE("batch.parse", "批量解析", true),

    /** 重建索引：{@code /admin/reindex}、{@code force=true}。无人等、长任务、可取消/续跑。 */
    BATCH_REINDEX("batch.reindex", "重建索引", true),

    /** 批量向量化：批量补算向量。与交互式共享 L2 的 embed 信号量。 */
    BATCH_EMBED("batch.embed", "批量向量化", true),

    /** 评测跑批：最低优先级、无人在等。 */
    BATCH_EVAL("batch.eval", "评测跑批", true);

    private final String key;
    private final String label;
    private final boolean batch;

    GateChannel(String key, String label, boolean batch) {
        this.key = key;
        this.label = label;
        this.batch = batch;
    }

    /** 指标标签 / 配置里用的稳定键（<b>小写点分</b>，与设计 §5.5 的 {@code queue=...} 一致）。 */
    public String key() {
        return key;
    }

    /** 中文名（日志与自检用）。 */
    public String label() {
        return label;
    }

    /** 是否批量通道（软闸门只对 {@link #BATCH_PARSE} 生效，但自检要能一眼看出哪些是批量）。 */
    public boolean batch() {
        return batch;
    }

    /** 该通道的类型权重（{@code effective_priority} 的第二项，设计 §5.2）。 */
    public int weight(AiSettings.Gate.Priority priority) {
        return switch (this) {
            case INTERACTIVE -> priority.getInteractiveWeight();
            case BATCH_PARSE -> priority.getBatchParseWeight();
            case BATCH_REINDEX -> priority.getBatchReindexWeight();
            case BATCH_EMBED -> priority.getBatchEmbedWeight();
            case BATCH_EVAL -> priority.getBatchEvalWeight();
        };
    }

    /** 从外部字符串解析通道（契约里的 {@code task_type}；大小写与 {@code .}/{@code _} 都容）。 */
    public static GateChannel parse(String raw, GateChannel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('_', '.');
        for (GateChannel channel : values()) {
            if (channel.key.equals(normalized)) {
                return channel;
            }
        }
        // 兼容主系统的 task_type 字面量（PARSE/REINDEX/EMBED/EVAL，见 V15 的列注释）
        return switch (normalized) {
            case "parse", "upload", "retry" -> BATCH_PARSE;
            case "reindex" -> BATCH_REINDEX;
            case "embed" -> BATCH_EMBED;
            case "eval" -> BATCH_EVAL;
            case "interactive", "chat", "ask" -> INTERACTIVE;
            default -> fallback;
        };
    }
}
