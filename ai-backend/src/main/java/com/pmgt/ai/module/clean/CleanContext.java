package com.pmgt.ai.module.clean;

import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.store.StoredDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次清洗的输入（设计 §3.3.1 的 {@code CleanContext}）。
 *
 * <p>为什么给的是"整份文档"而不是单页：页眉/页脚（C1）、水印（C8）、段级去重（C6）都是
 * <b>跨页规则</b>——只看一页无法判断"这一行是不是每页都有"。所以接口分两层：
 * {@link TextCleaner#cleanDocument(CleanContext)} 先做跨页检测，再逐页应用。
 *
 * <p>{@code source} 是"只作用于文本层与 OCR markdown 两路"这条约束的载体：
 * 已知取值 {@code text-layer}（PDF 文本层）与 {@code platform}（平台 OCR markdown）参与清洗；
 * 其它来源（未来新增的解析路径，如 DOCX/XLSX 扩展）<b>默认不参与</b>，
 * 直到它的清洗语义被单独确认——"不认识就照原样传下去"比"顺手洗一遍"安全。
 */
public record CleanContext(String docId, String filename, int pageCount, int cleanRev, List<PageInput> pages) {

    /** 参与清洗的两路来源（设计原文：规则只作用于文本层与 OCR markdown 两路）。 */
    public static final List<String> SUPPORTED_SOURCES = List.of("text-layer", "platform");

    /** 一页的清洗输入：页码 + 来源 + 原文 + 平台给的区域块（C5 表格标记优先用它的 bbox/label）。 */
    public record PageInput(int pageNo, String source, String text, List<StoredDoc.BlockRef> blocks) {

        public String safeText() {
            return text == null ? "" : text;
        }

        public List<StoredDoc.BlockRef> safeBlocks() {
            return blocks == null ? List.of() : blocks;
        }
    }

    /** 从文档库的文档构造（{@code cleanRev} 取当前生效口径，见 {@link PipelineRev}）。 */
    public static CleanContext of(StoredDoc doc, int cleanRev) {
        List<PageInput> inputs = new ArrayList<>();
        for (StoredDoc.PageInfo page : doc.getPages()) {
            inputs.add(new PageInput(page.pageNo(), page.source(), page.text(), page.blocks()));
        }
        return new CleanContext(doc.getDocId(), doc.getFilename(), doc.getPageCount(), cleanRev, inputs);
    }

    /** 该页的来源是否属于"清洗只作用于两路"的白名单（空来源按已知路径处理，见类注释）。 */
    public static boolean supportedSource(String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        return SUPPORTED_SOURCES.contains(source.strip().toLowerCase(java.util.Locale.ROOT));
    }
}
