package com.pmgt.ai.module.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段上报体（契约 {@code POST /api/ai/pipeline/runs/{run_id}/stages/{stage_key}}，设计 §4.4.3）。
 *
 * <p><b>JSON 一律 snake_case</b>（同时兼容 camelCase 的读入——主系统那边同样处理）：
 * 契约冻结后，字段名就是两侧的接口，改名等于改契约。
 *
 * <p><b>幂等</b>：同一 {@code run_id + stage_key} 的重复上报由主系统"更新同一行、{@code attempts+1}"，
 * 不产生多行噪音；因此本类<b>不带 attempts</b>（那是主系统的计数，不是 AI 侧说的）。
 */
public record StageReport(StageStatus status,
                          Integer itemCount,
                          Integer itemFailed,
                          Long elapsedMs,
                          Map<String, Object> counters,
                          String inputDigest,
                          String outputDigest,
                          String errorMsg) {

    /** 组装成契约的请求体（snake_case；空字段不下发，与仓库"不发明 null 字段"的习惯一致）。 */
    public Map<String, Object> toBody() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status.wire());
        if (itemCount != null) {
            out.put("item_count", itemCount);
        }
        if (itemFailed != null) {
            out.put("item_failed", itemFailed);
        }
        if (elapsedMs != null) {
            out.put("elapsed_ms", elapsedMs);
        }
        if (counters != null && !counters.isEmpty()) {
            out.put("counters", counters);
        }
        if (inputDigest != null && !inputDigest.isBlank()) {
            out.put("input_digest", inputDigest);
        }
        if (outputDigest != null && !outputDigest.isBlank()) {
            out.put("output_digest", outputDigest);
        }
        if (errorMsg != null && !errorMsg.isBlank()) {
            out.put("error_msg", errorMsg);
        }
        return out;
    }

    // ── 常用构造（让调用点只写"业务事实"，不重复组装 Map）────────────────

    public static StageReport running(String inputDigest) {
        return new StageReport(StageStatus.RUNNING, null, null, null, null, inputDigest, null, null);
    }

    public static StageReport done(int itemCount, int itemFailed, long elapsedMs,
                                   Map<String, Object> counters, String outputDigest) {
        return new StageReport(itemFailed > 0 ? StageStatus.PARTIAL : StageStatus.DONE,
                itemCount, itemFailed, elapsedMs, counters, null, outputDigest, null);
    }

    /**
     * {@code SKIPPED}：<b>必须写明原因</b>。
     * 本批的 {@code CLEAN} 就走这条路——本轮尚无清洗阶段（G1 属 P1-α），
     * 用 {@code SKIPPED + error_msg} 如实登记，而不是伪造一个 {@code DONE} 的清洗阶段
     * （"文档写了、代码没写"正是 §9 要消灭的歧义）。
     */
    public static StageReport skipped(String reason) {
        return new StageReport(StageStatus.SKIPPED, 0, 0, 0L, null, null, null, reason);
    }

    public static StageReport failed(long elapsedMs, String errorMsg) {
        return new StageReport(StageStatus.FAILED, null, null, elapsedMs, null, null, null, errorMsg);
    }

    public static StageReport cancelled(long elapsedMs, String reason) {
        return new StageReport(StageStatus.CANCELLED, null, null, elapsedMs, null, null, null, reason);
    }
}
