package com.pmgt.ai.module.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一份文档的解析结果（文本型 / 扫描件 / 图片三种来源统一到它）。
 *
 * <p>{@code provider}/{@code engine} 取值域已收窄为 {@code text-layer} 与 {@code platform}
 * （本地 OCR 引擎已于 2026-09-18 移除，Python 与 Java 版都不再有第三种）。
 */
public class DocumentText {

    private String kind = "text_pdf";     // text_pdf | scanned | image | error
    private List<PageText> pages = List.of();
    private String engine = "text-layer";
    private String provider = "text-layer";
    /**
     * 渲染 DPI。**只有走 OCR 的路径才有值**（文本型 PDF 与图片路径下为 {@code null}）——
     * 对齐 Python 版：那边是 {@code None}，若这里用 {@code 0}，主系统会看到"dpi=0"这种不存在的档位。
     */
    private Integer dpi;
    private String imageFormat = "";
    private Map<String, Double> stages = new LinkedHashMap<>();
    private List<String> notes = new ArrayList<>();
    private List<Map<String, Object>> checks = new ArrayList<>();
    private double elapsed;
    private String error;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public List<PageText> getPages() {
        return pages;
    }

    public void setPages(List<PageText> pages) {
        this.pages = pages == null ? List.of() : pages;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Integer getDpi() {
        return dpi;
    }

    public void setDpi(Integer dpi) {
        this.dpi = dpi;
    }

    public String getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat == null ? "" : imageFormat;
    }

    public Map<String, Double> getStages() {
        return stages;
    }

    public void setStages(Map<String, Double> stages) {
        this.stages = stages == null ? new LinkedHashMap<>() : stages;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes == null ? new ArrayList<>() : notes;
    }

    public List<Map<String, Object>> getChecks() {
        return checks;
    }

    public void setChecks(List<Map<String, Object>> checks) {
        this.checks = checks == null ? new ArrayList<>() : checks;
    }

    public double getElapsed() {
        return elapsed;
    }

    public void setElapsed(double elapsed) {
        this.elapsed = elapsed;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int pageCount() {
        return pages.size();
    }

    public String text() {
        return pages.stream().map(PageText::getText).collect(Collectors.joining("\n"));
    }

    public int charCount() {
        return text().length();
    }

    /** 带页码标记的全文——喂给模型的就是它，来源标注的前提。 */
    public String labeledText() {
        return pages.stream()
                .map(p -> p.header() + "\n" + (p.getText().isBlank() ? "（本页未识别到文本）" : p.getText()))
                .collect(Collectors.joining("\n\n"));
    }

    public List<Integer> emptyPages() {
        return pages.stream().filter(PageText::isEmpty).map(PageText::getPageNo).toList();
    }

    public List<Integer> failedPages() {
        return pages.stream()
                .filter(p -> p.getError() != null && !p.getError().isBlank())
                .map(PageText::getPageNo)
                .toList();
    }

    /** 平台不返回置信度，恒空；保留是为了 API 形状与历史文档稳定。 */
    public List<Integer> lowConfidencePages() {
        return List.of();
    }

    /** 平台不返回置信度，恒 null。 */
    public Double avgConfidence() {
        return null;
    }

    /** 建议人工优先复核的页：识别失败的 + 空白 + 校验不通过的。 */
    public List<Integer> reviewPages() {
        List<Integer> pagesOut = new ArrayList<>(failedPages());
        pagesOut.addAll(emptyPages());
        for (Map<String, Object> check : checks) {
            Object status = check.get("status");
            if ("fail".equals(status) || "warn".equals(status)) {
                Object items = check.get("items");
                if (items instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("page") instanceof Number page) {
                            pagesOut.add(page.intValue());
                        }
                    }
                }
            }
        }
        return pagesOut.stream().filter(p -> p != null && p > 0).distinct().sorted().toList();
    }

    public Map<String, Integer> checkSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> check : checks) {
            String status = String.valueOf(check.getOrDefault("status", "skip"));
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    /** 响应里透出的摘要（字段名对齐 Python 版 `summary()`）。 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("pages", pageCount());
        out.put("engine", engine);
        out.put("provider", provider);
        out.put("dpi", dpi);
        out.put("image_format", imageFormat);
        out.put("chars", charCount());
        out.put("avg_confidence", avgConfidence());
        out.put("low_confidence_pages", lowConfidencePages());
        out.put("empty_pages", emptyPages());
        out.put("failed_pages", failedPages());
        out.put("review_pages", reviewPages());
        out.put("stages", stages);
        out.put("checks", checks);
        out.put("check_summary", checkSummary());
        out.put("notes", notes);
        out.put("elapsed", Math.round(elapsed * 100) / 100.0);
        return out;
    }
}
