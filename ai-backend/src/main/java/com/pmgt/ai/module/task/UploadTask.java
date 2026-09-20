package com.pmgt.ai.module.task;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次上传解析任务。
 *
 * <p>为什么要有它：解析一份扫描件要几十秒到几分钟，同步接口必然超时（Python 版实测：
 * axios 30s + nginx 60s + 存储客户端 60s 三层叠加）。所以改成"先登记任务、立即返回，
 * 后台解析、前端轮询"——这个类就是被轮询的那份状态。
 *
 * <p>状态机：{@code QUEUED → PARSING → DONE / FAILED / CANCELLED}，
 * 只有 QUEUED/PARSING 可取消、只有已结束的任务可移除。
 */
@Getter
@Setter
public class UploadTask {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 阶段名 → 中文标签。键与 Python 一致（含已移除的兜底阶段不再出现）。 */
    public static final Map<String, String> STAGE_LABEL = Map.of(
            "receive", "接收文件",
            "detect", "判定文件类型",
            "render", "渲染页面",
            "ocr", "识别中",
            "check", "数据校验",
            "store", "写入文档库",
            "done", "完成");

    /** 阶段 → (起点占比, 终点占比)。识别最耗时，权重 0.20~0.97（兜底阶段移除后归还给它）。 */
    private static final Map<String, double[]> STAGE_RANGE = Map.of(
            "render", new double[]{0.05, 0.20},
            "ocr", new double[]{0.20, 0.97},
            "check", new double[]{0.97, 0.99},
            "store", new double[]{0.99, 1.0});

    private String taskId = "";
    private String filename = "";
    private long sizeBytes;
    private Integer dpi;
    private String status = "QUEUED";
    private String stageKey = "receive";
    private String stage = STAGE_LABEL.get("receive");
    private int stageDone;
    private int stageTotal;
    private String createdAt = now();
    private String startedAt = "";
    private String finishedAt = "";
    private String docId = "";
    private String provider = "";
    private Map<String, Object> summary = new LinkedHashMap<>();
    private Map<String, Double> stages = new LinkedHashMap<>();
    private List<Map<String, Object>> checks = List.of();
    private List<String> notes = List.of();
    private List<Map<String, Object>> pages = List.of();
    private String error = "";
    private String tmpPath = "";
    private boolean cancelRequested;
    /** 任务结束后固化的总耗时（秒）。有它就优先用它——避免"结束后 elapsed 还在涨"。 */
    private double elapsedSeconds;

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 总进度 0~100：按阶段加权，避免"渲染 12 页"看起来像卡住。 */
    public double percent() {
        if ("DONE".equals(status)) {
            return 100.0;
        }
        double[] range = STAGE_RANGE.getOrDefault(stageKey, new double[]{0.0, 0.05});
        double frac = stageTotal > 0 ? (double) stageDone / stageTotal : 0.0;
        double value = range[0] + (range[1] - range[0]) * Math.min(1.0, frac);
        return Math.round(value * 1000) / 10.0;
    }

    public double elapsed() {
        if (elapsedSeconds > 0) {
            return Math.round(elapsedSeconds * 100) / 100.0;
        }
        if (startedAt == null || startedAt.isBlank()) {
            return 0.0;
        }
        LocalDateTime start = LocalDateTime.parse(startedAt, TS);
        LocalDateTime end = (finishedAt != null && !finishedAt.isBlank())
                ? LocalDateTime.parse(finishedAt, TS)
                : LocalDateTime.now();
        return Math.round(java.time.Duration.between(start, end).toMillis() / 10.0) / 100.0;
    }

    public static String timestamp() {
        return now();
    }

    /** 列表接口用（**不带 pages**，避免列表返回巨大 JSON）。 */
    public Map<String, Object> toBrief() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", taskId);
        out.put("filename", filename);
        out.put("size_bytes", sizeBytes);
        out.put("status", status);
        out.put("stage", stage);
        out.put("stage_key", stageKey);
        out.put("stage_done", stageDone);
        out.put("stage_total", stageTotal);
        out.put("percent", percent());
        out.put("created_at", createdAt);
        out.put("started_at", startedAt);
        out.put("finished_at", finishedAt);
        out.put("elapsed", elapsed());
        out.put("doc_id", docId);
        out.put("provider", provider);
        out.put("error", error);
        return out;
    }

    /** 详情接口用（带 pages / checks / notes）。 */
    public Map<String, Object> toDetail() {
        Map<String, Object> out = new LinkedHashMap<>(toBrief());
        out.put("summary", summary);
        out.put("stages", stages);
        out.put("checks", checks);
        out.put("notes", notes);
        out.put("pages", pages);
        return out;
    }
}
