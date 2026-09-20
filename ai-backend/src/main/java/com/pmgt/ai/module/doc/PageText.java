package com.pmgt.ai.module.doc;

import com.pmgt.ai.module.ocr.PlatformPage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单页文本 + 质量信号。
 *
 * <p>页号必须带着走——检索结果要能标注来源（{@code [P2]}），否则"必须标来源"的提示词约束无从落地。
 *
 * <p>{@code confidence} 在平台路径下**恒为 null**（平台不返回置信度），字段保留只为响应形状稳定，
 * 前端据此显示"该引擎不提供逐页置信度"才是正确解读；{@code null} ≠ 0。
 */
public class PageText {

    private final int pageNo;
    private String text;
    private String source;          // text-layer | platform
    private List<PlatformPage.Block> blocks = List.of();
    private Double confidence;      // 恒 null（平台不返回）
    private double elapsed;
    private String error;
    private String note;

    public PageText(int pageNo, String text) {
        this.pageNo = pageNo;
        this.text = text == null ? "" : text;
    }

    public int getPageNo() {
        return pageNo;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<PlatformPage.Block> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<PlatformPage.Block> blocks) {
        this.blocks = blocks == null ? List.of() : blocks;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
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

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int chars() {
        return text == null ? 0 : text.length();
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    public long tableCount() {
        return countLabel("table");
    }

    public long sealCount() {
        return countLabel("seal");
    }

    private long countLabel(String label) {
        return blocks.stream().filter(b -> label.equalsIgnoreCase(b.label())).count();
    }

    /** 页头：让模型知道"这段是表格里的"，标注来源时能给到更细的粒度。 */
    public String header() {
        List<String> marks = new ArrayList<>();
        if (tableCount() > 0) {
            marks.add("含表格 " + tableCount() + " 个");
        }
        if (sealCount() > 0) {
            marks.add("含印章 " + sealCount() + " 处（未识别文字）");
        }
        if (error != null && !error.isBlank()) {
            marks.add("识别失败");
        }
        String suffix = marks.isEmpty() ? "" : " · " + String.join("、", marks);
        return "【第 " + pageNo + " 页" + suffix + "】";
    }

    /** 前端"过程性内容"用的页级明细。 */
    public Map<String, Object> quality() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page_no", pageNo);
        out.put("chars", chars());
        out.put("elapsed", elapsed);
        out.put("source", source);
        out.put("confidence", confidence);
        out.put("tables", tableCount());
        out.put("seals", sealCount());
        out.put("empty", isEmpty());
        out.put("error", error == null ? "" : error);
        out.put("note", note == null ? "" : note);
        return out;
    }
}
