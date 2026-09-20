package com.pmgt.ai.module.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份入库文档（对应 {@code work/docs/<doc_id>.json}）。
 *
 * <p>逐字段对齐 Python 版 {@code store.StoredDoc}：磁盘上的 JSON、{@code /documents} 的
 * 响应形状与 {@code StoredDoc.meta()} 的键名都按老契约读，换语言不该让调用方改代码。
 *
 * <p>⚠️ {@code confidence} / {@code avg_confidence} <b>恒为 {@code null}</b>：平台 OCR 不返回置信度，
 * 而唯一能给逐页分数的本地引擎已于 2026-09-18 移除。这两个键保留是为了历史文档与前端不缺字段——
 * {@code null} 的正确读法是"这个信息不存在"，不是"识别质量为零"。
 *
 * <p>{@code chunks} 在 Python 版里预留给向量化且恒为空，这里<b>同样保留该键恒空</b>：
 * 切片由 {@link DocStore#iterChunks} 现算，向量由 {@code RetrievalService} 落在
 * {@code work/vectors/<doc_id>.json}，回写文档会让"改切片规则"变成数据迁移。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredDoc {

    private String docId = "";
    private String filename = "";
    private String kind = "";
    private List<PageInfo> pages = new ArrayList<>();
    private String uploadedAt = "";
    private long sizeBytes;
    private String engine = "";
    private Integer dpi;
    private Double avgConfidence;
    private String provider = "";
    private String imageFormat = "";
    private Map<String, Double> stages = new LinkedHashMap<>();
    private List<Map<String, Object>> checks = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private List<Map<String, Object>> chunks = new ArrayList<>();

    public StoredDoc() {
    }

    public StoredDoc(String docId, String filename) {
        this.docId = docId;
        this.filename = filename;
    }

    /**
     * 把一个已解析的文档转成入库形态。
     *
     * <p>页级信息<b>尽量留全</b>（来源 / 区域块 / 质量信号 / 耗时）——这些是"内容出处"与
     * "复核优先级"的依据，丢了就只能回去重跑 OCR。区域块只留 {@code label + bbox}
     * （对齐 Python 的 {@code blocks_brief}）：正文已在 {@code text} 里，存全量块内容会让
     * JSON 体积翻几倍而没什么收益。
     */
    public static StoredDoc of(String docId, String filename, DocumentText doc, long sizeBytes, String uploadedAt) {
        StoredDoc out = new StoredDoc(docId, filename);
        out.kind = doc.getKind();
        List<PageInfo> pages = new ArrayList<>();
        for (PageText page : doc.getPages()) {
            pages.add(PageInfo.of(page));
        }
        out.pages = pages;
        out.uploadedAt = uploadedAt;
        out.sizeBytes = sizeBytes;
        out.engine = doc.getEngine();
        out.dpi = doc.getDpi();
        out.avgConfidence = doc.avgConfidence();   // 平台路径恒 null，见类注释
        out.provider = doc.getProvider();
        out.imageFormat = doc.getImageFormat();
        out.stages = doc.getStages();
        out.checks = doc.getChecks();
        out.notes = doc.getNotes();
        out.chunks = new ArrayList<>();
        return out;
    }

    // ── 只读派生 ─────────────────────────────────────────────────

    /**
     * 页数。<b>同时是 getter</b>（{@code getPageCount()}）——controller 用它，
     * 这样 {@code /documents/{id}} 直接序列化 {@code StoredDoc} 时不会多出 {@code page_count} 字段。
     */
    @JsonProperty("page_count")
    public int getPageCount() {
        return pages.size();
    }

    /** 与 {@link #getPageCount()} 同义的短名（源码内部调用更顺手）。 */
    public int pageCount() {
        return pages.size();
    }

    @JsonProperty("char_count")
    public int getCharCount() {
        return charCount();
    }

    public int charCount() {
        int total = 0;
        for (PageInfo page : pages) {
            total += page.text().length();
        }
        return total;
    }

    /**
     * 详情用的整份文档字典，字段名与顺序对齐 Python {@code StoredDoc.to_dict()}。
     *
     * <p>契约要点：{@code /documents} 列表只给 {@link #meta()}（<b>不含全文</b>），
     * {@code /documents/{id}} 给整份（含 {@code pages}）——两者不能混，
     * 列表里塞全文就是"列表接口返回几十 MB"的老问题。
     */
    public Map<String, Object> toDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_id", docId);
        out.put("filename", filename);
        out.put("kind", kind);
        out.put("pages", pages);
        out.put("uploaded_at", uploadedAt);
        out.put("size_bytes", sizeBytes);
        out.put("engine", engine);
        out.put("dpi", dpi);
        out.put("avg_confidence", avgConfidence);
        out.put("provider", provider);
        out.put("image_format", imageFormat);
        out.put("stages", stages);
        out.put("checks", checks);
        out.put("notes", notes);
        out.put("chunks", chunks);
        return out;
    }

    /** GET 型 getter（Jackson 序列化 StoredDoc 时与 {@link #toDict()} 同一个形状）。 */
    @JsonProperty("doc_id")
    public String getDocId() {
        return docId;
    }

    @JsonProperty("filename")
    public String getFilename() {
        return filename;
    }

    @JsonProperty("kind")
    public String getKind() {
        return kind;
    }

    @JsonProperty("pages")
    public List<PageInfo> getPages() {
        return pages;
    }

    @JsonProperty("uploaded_at")
    public String getUploadedAt() {
        return uploadedAt;
    }

    @JsonProperty("size_bytes")
    public long getSizeBytes() {
        return sizeBytes;
    }

    @JsonProperty("engine")
    public String getEngine() {
        return engine;
    }

    @JsonProperty("dpi")
    public Integer getDpi() {
        return dpi;
    }

    @JsonProperty("avg_confidence")
    public Double getAvgConfidence() {
        return avgConfidence;
    }

    @JsonProperty("provider")
    public String getProvider() {
        return provider;
    }

    @JsonProperty("image_format")
    public String getImageFormat() {
        return imageFormat;
    }

    @JsonProperty("stages")
    public Map<String, Double> getStages() {
        return stages;
    }

    @JsonProperty("checks")
    public List<Map<String, Object>> getChecks() {
        return checks;
    }

    @JsonProperty("notes")
    public List<String> getNotes() {
        return notes;
    }

    @JsonProperty("chunks")
    public List<Map<String, Object>> getChunks() {
        return chunks;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (PageInfo page : pages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(page.text());
        }
        return sb.toString();
    }

    /** 建议人工优先复核的页：识别失败的 + 空白的 + 校验不通过的。 */
    public List<Integer> reviewPages() {
        List<Integer> out = new ArrayList<>();
        for (PageInfo page : pages) {
            boolean failed = page.error() != null && !page.error().isBlank();
            if (failed || page.text().isBlank()) {
                out.add(page.pageNo());
            }
        }
        for (Map<String, Object> check : checks) {
            Object status = check.get("status");
            if (!"fail".equals(status) && !"warn".equals(status)) {
                continue;
            }
            Object items = check.get("items");
            if (items instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && map.get("page") instanceof Number page) {
                        out.add(page.intValue());
                    }
                }
            }
        }
        return out.stream().filter(p -> p != null && p > 0).distinct().sorted().toList();
    }

    public Map<String, Integer> checkSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> check : checks) {
            counts.merge(String.valueOf(check.getOrDefault("status", "skip")), 1, Integer::sum);
        }
        return counts;
    }

    public String pageText(int pageNo) {
        for (PageInfo page : pages) {
            if (page.pageNo() == pageNo) {
                return page.text();
            }
        }
        return "";
    }

    /** 带页码标记的全文（{@code maxChars} 非空时按页截断），喂给模型的就是它。 */
    public String labeledText(Integer maxChars) {
        List<String> blocks = new ArrayList<>();
        int total = 0;
        for (PageInfo page : pages) {
            String body = page.text().isBlank() ? "（本页未识别到文本）" : page.text().strip();
            List<String> marks = new ArrayList<>();
            if (page.tables() > 0) {
                marks.add("含表格 " + page.tables() + " 个");
            }
            if (page.seals() > 0) {
                marks.add("含印章 " + page.seals() + " 处（未识别文字）");
            }
            String suffix = marks.isEmpty() ? "" : " · " + String.join("、", marks);
            String block = "【第 " + page.pageNo() + " 页" + suffix + "】\n" + body;
            if (maxChars != null && total + block.length() > maxChars && !blocks.isEmpty()) {
                blocks.add("（…后续页面因篇幅限制未提供…）");
                break;
            }
            blocks.add(block);
            total += block.length();
        }
        return String.join("\n\n", blocks);
    }

    /** 列表接口用的元信息（<b>不含全文</b>，避免列表返回巨大 JSON）。 */
    public Map<String, Object> meta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_id", docId);
        out.put("filename", filename);
        out.put("kind", kind);
        out.put("pages", pageCount());
        out.put("chars", charCount());
        out.put("avg_confidence", avgConfidence);
        out.put("uploaded_at", uploadedAt);
        out.put("size_bytes", sizeBytes);
        out.put("engine", engine);
        out.put("provider", provider == null || provider.isBlank() ? engine : provider);
        out.put("image_format", imageFormat);
        out.put("review_pages", reviewPages());
        out.put("check_summary", checkSummary());
        return out;
    }

    /**
     * 单页信息（对齐 Python 版页字典的键名与顺序）。
     *
     * <p>用类而不是 {@code Map} 是为了"读回来时不丢类型"：{@code confidence} 怎么都是 {@code null}，
     * 但 {@code page_no} 必须还是数字、{@code tables} 必须还是整数，前端按老契约读它们。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageInfo {

        /**
         * ⚠️ 必须显式声明 snake_case：Python 版存的 JSON 用 {@code page_no}，
         * 而 Java 字段是 {@code pageNo}——不标注的话 Jackson 反序列化会静默丢掉页码
         * （实测症状：文档能读出来、但 {@code pageText(1)} 永远返回空，被误判成"空白页"）。
         */
        @JsonProperty("page_no")
        private int pageNo;
        private String text = "";
        private Double confidence;
        private String source = "";
        private int chars;
        private long tables;
        private long seals;
        private double elapsed;
        private List<BlockRef> blocks = new ArrayList<>();
        private String error;
        private String note;

        public PageInfo() {
        }

        public static PageInfo of(PageText page) {
            PageInfo out = new PageInfo();
            out.pageNo = page.getPageNo();
            out.text = page.getText() == null ? "" : page.getText();
            out.confidence = page.getConfidence();   // 恒 null（平台不返回）
            out.source = page.getSource() == null ? "" : page.getSource();
            out.chars = page.chars();
            out.tables = page.tableCount();
            out.seals = page.sealCount();
            out.elapsed = Math.round(page.getElapsed() * 100) / 100.0;
            out.blocks = page.getBlocks().stream()
                    .map(b -> new BlockRef(b.label(), b.bbox() == null ? new double[0] : b.bbox()))
                    .toList();
            out.error = page.getError();
            out.note = page.getNote();
            return out;
        }

        public int pageNo() {
            return pageNo;
        }

        /** 文本永远不为 null，省掉调用方一堆判空。 */
        public String text() {
            return text == null ? "" : text;
        }

        public Double confidence() {
            return confidence;
        }

        public String source() {
            return source == null ? "" : source;
        }

        public int chars() {
            return chars;
        }

        public long tables() {
            return tables;
        }

        public long seals() {
            return seals;
        }

        public double elapsed() {
            return elapsed;
        }

        public List<BlockRef> blocks() {
            return blocks == null ? List.of() : blocks;
        }

        public String error() {
            return error;
        }

        public String note() {
            return note;
        }

        public int getPageNo() {
            return pageNo;
        }

        public void setPageNo(int pageNo) {
            this.pageNo = pageNo;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text == null ? "" : text;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public int getChars() {
            return chars;
        }

        public void setChars(int chars) {
            this.chars = chars;
        }

        public long getTables() {
            return tables;
        }

        public void setTables(long tables) {
            this.tables = tables;
        }

        public long getSeals() {
            return seals;
        }

        public void setSeals(long seals) {
            this.seals = seals;
        }

        public double getElapsed() {
            return elapsed;
        }

        public void setElapsed(double elapsed) {
            this.elapsed = elapsed;
        }

        public List<BlockRef> getBlocks() {
            return blocks();
        }

        public void setBlocks(List<BlockRef> blocks) {
            this.blocks = blocks == null ? new ArrayList<>() : blocks;
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
    }

    /** 区域块摘要：只留标签与坐标（正文已在 {@code text} 里）。 */
    public record BlockRef(String label, double[] bbox) {
    }

    // ── setter（getter 见上方"只读派生"段，命名与 JSON 字段名一一对应）──────

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public void setPages(List<PageInfo> pages) {
        this.pages = pages == null ? new ArrayList<>() : pages;
    }

    public void setUploadedAt(String uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public void setDpi(Integer dpi) {
        this.dpi = dpi;
    }

    public void setAvgConfidence(Double avgConfidence) {
        this.avgConfidence = avgConfidence;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    public void setStages(Map<String, Double> stages) {
        this.stages = stages == null ? new LinkedHashMap<>() : stages;
    }

    public void setChecks(List<Map<String, Object>> checks) {
        this.checks = checks == null ? new ArrayList<>() : checks;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes == null ? new ArrayList<>() : notes;
    }

    public void setChunks(List<Map<String, Object>> chunks) {
        this.chunks = chunks == null ? new ArrayList<>() : chunks;
    }
}
