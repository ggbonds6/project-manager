package com.pmgt.ai.module.pipeline;

/**
 * 阶段键（设计 §3.2 的七阶段契约；本批 AI 侧上报其中五阶段）。
 *
 * <p>为什么是枚举而不是字符串常量：它同时是<b>上报契约的一部分</b>
 * （主系统 {@code ai_pipeline_stage.stage_key}）与<b>指标标签</b>
 * （{@code ai.pipeline.stage.elapsed_ms{stage=...}}）。用枚举能让"打错一个字母"
 * 在编译期就暴露，而不是在主系统那边静默多出一行谁都不认的阶段。
 *
 * <p>{@code RETRIEVE} / {@code GENERATE} 是<b>在线阶段</b>（不建模为任务状态，无阶段行），
 * 保留在枚举里是为了"阶段清单与设计文档逐条对应"，不参与本批上报。
 */
public enum StageKey {

    PARSE("PARSE", "识别", 1),
    CLEAN("CLEAN", "清洗", 2),
    CHUNK("CHUNK", "切分", 3),
    EMBED("EMBED", "向量化", 4),
    INDEX("INDEX", "入库", 5),
    RETRIEVE("RETRIEVE", "召回", 6),
    GENERATE("GENERATE", "生成", 7);

    private final String key;
    private final String label;
    private final int seq;

    StageKey(String key, String label, int seq) {
        this.key = key;
        this.label = label;
        this.seq = seq;
    }

    public String key() {
        return key;
    }

    /** 中文名（前端阶段条直接显示）。 */
    public String label() {
        return label;
    }

    /** 阶段顺序（1~7，与设计 §3.2 的表一致）。 */
    public int seq() {
        return seq;
    }

    /** 本批 AI 侧真正会上报的五个阶段（RETRIEVE/GENERATE 是在线阶段，不落阶段行）。 */
    public static StageKey[] reported() {
        return new StageKey[] {PARSE, CLEAN, CHUNK, EMBED, INDEX};
    }
}
