package com.pmgt.ai.module.llm;

import com.pmgt.ai.module.doc.DocumentText;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次"抽取 + 生成"的结果。
 *
 * <p>字段与 Python 的 {@code analyze.AnalyzeResult} 一一对应，
 * {@link #toPayload()} 的输出就是 {@code /analyze} 响应体里的 {@code data}（逐字段一致）。
 */
public final class AnalyzeResult {

    private final String markdown;
    private final Map<String, Object> doc;
    private final Map<String, Object> llm;
    private final Map<String, Object> verification;
    private final boolean truncated;
    private final String truncateNote;
    private final String warning;

    public AnalyzeResult(
            String markdown,
            Map<String, Object> doc,
            Map<String, Object> llm,
            Map<String, Object> verification,
            boolean truncated,
            String truncateNote,
            String warning) {
        this.markdown = markdown;
        this.doc = doc == null ? new LinkedHashMap<>() : doc;
        this.llm = llm == null ? new LinkedHashMap<>() : llm;
        this.verification = verification == null ? new LinkedHashMap<>() : verification;
        this.truncated = truncated;
        this.truncateNote = truncateNote == null ? "" : truncateNote;
        this.warning = warning == null ? "" : warning;
    }

    public String getMarkdown() {
        return markdown;
    }

    /** 文档摘要（{@code DocumentText.summary()} 的形状，不是 {@code DocumentText} 本身）。 */
    public Map<String, Object> getDoc() {
        return doc;
    }

    /** 模型调用情况：{@code ok/model/prompt_tokens/completion_tokens/reasoning_chars/finish_reason/elapsed}。 */
    public Map<String, Object> getLlm() {
        return llm;
    }

    /**
     * <b>输出侧的机器校验</b>：答案里的数字能否在原文逐字找到。
     *
     * <p>比"让模型自评置信度"可靠——模型说 0.95 你无从验证，但"这个数字原文里有没有"
     * 是代码算出来的、可复现的。见 {@code Checks.verifyNumbersInSource}。
     */
    public Map<String, Object> getVerification() {
        return verification;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public String getTruncateNote() {
        return truncateNote;
    }

    public String getWarning() {
        return warning;
    }

    /** 对齐 Python 的 {@code AnalyzeResult.to_payload()}（键名与顺序都按它）。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markdown", markdown);
        out.put("doc", doc);
        out.put("llm", llm);
        out.put("verification", verification);
        out.put("truncated", truncated);
        out.put("truncate_note", truncateNote);
        out.put("warning", warning);
        return out;
    }

    /** 便捷构造：只有 markdown / doc / warning（文件读不出来、没文本、模型调用失败这三条短路路径）。 */
    public static AnalyzeResult of(String markdown, Map<String, Object> doc, String warning) {
        return new AnalyzeResult(markdown, doc, new LinkedHashMap<>(), new LinkedHashMap<>(), false, "", warning);
    }

    /** 便捷构造：带上 llm 与截断信息。 */
    public static AnalyzeResult of(
            String markdown,
            Map<String, Object> doc,
            Map<String, Object> llm,
            boolean truncated,
            String truncateNote,
            String warning) {
        return new AnalyzeResult(
                markdown, doc, llm, new LinkedHashMap<>(), truncated, truncateNote, warning);
    }

    /** 只用来确认 {@link DocumentText} 传的就是它的 {@code summary()}（编译期类型提示）。 */
    static Map<String, Object> summaryOf(DocumentText doc) {
        return doc.summary();
    }
}
