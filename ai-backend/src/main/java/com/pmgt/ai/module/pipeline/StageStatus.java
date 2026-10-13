package com.pmgt.ai.module.pipeline;

/**
 * 阶段状态（设计 §4.4.2 的阶段级状态机，与主系统 {@code ai_pipeline_stage.status} 逐字一致）。
 *
 * <table border="1">
 *   <caption>七个状态</caption>
 *   <tr><th>状态</th><th>含义</th><th>本批 AI 侧会不会上报</th></tr>
 *   <tr><td>{@code PENDING}</td><td>已规划、未开始</td><td>会（阶段开始时）</td></tr>
 *   <tr><td>{@code RUNNING}</td><td>进行中</td><td>会</td></tr>
 *   <tr><td>{@code DONE}</td><td>成功</td><td>会</td></tr>
 *   <tr><td>{@code PARTIAL}</td><td>部分成功（如页级 OCR 失败、部分切片写索引失败）</td><td>会（有 item_failed 时）</td></tr>
 *   <tr><td>{@code SKIPPED}</td><td>跳过（本批 {@code CLEAN} 就是这一类：尚无清洗阶段）</td><td>会</td></tr>
 *   <tr><td>{@code FAILED}</td><td>失败</td><td>会</td></tr>
 *   <tr><td>{@code CANCELLED}</td><td>被取消</td><td>会（取消时对在跑的阶段补一条）</td></tr>
 * </table>
 */
public enum StageStatus {

    PENDING,
    RUNNING,
    DONE,
    PARTIAL,
    SKIPPED,
    FAILED,
    CANCELLED;

    /** 是不是终态（终态才写 {@code finished_at}）。 */
    public boolean terminal() {
        return this == DONE || this == PARTIAL || this == SKIPPED || this == FAILED || this == CANCELLED;
    }

    /** 契约里的字面量（大写，与主系统 DB 列值一致）。 */
    public String wire() {
        return name();
    }
}
