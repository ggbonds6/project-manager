package com.pmgt.ai.module.llm;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.check.Checks;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排：文档 → 结构化文本 → 提示词 → 千问 → markdown 结果（{@code /analyze} 的后端）。
 *
 * <p>这一层只负责"把链路串起来"，具体规则都在：
 * <ul>
 *   <li>解析/分页/页内区域 → {@code module.doc}</li>
 *   <li>提示词与铁律 → {@link Prompts}</li>
 *   <li>模型调用 → {@link LlmClient}</li>
 * </ul>
 *
 * <h2>超长文档怎么处理</h2>
 *
 * <p>先按<b>页</b>截断（不是按字粗暴切），并在提示词里<b>明确告知模型"你没看到全部"</b>——
 * 否则模型会把"只给了前 10 页"当成"原文就这 10 页"，输出看似完整实则缺失的结论，
 * 这在审计场景是危险的。
 *
 * <p>截断信息会同时回传给前端，让使用者知道结论的覆盖范围。
 */
@Service
public class AnalyzeService {

    private final AiSettings settings;
    private final DocumentReader documentReader;
    private final LlmClient llmClient;

    public AnalyzeService(AiSettings settings, DocumentReader documentReader, LlmClient llmClient) {
        this.settings = settings;
        this.documentReader = documentReader;
        this.llmClient = llmClient;
    }

    /**
     * 对一个文件做完整的"抽取 + 生成"。
     *
     * @param path        文件路径
     * @param instruction 用户特别关注点（可为空）
     * @param dpi         扫描件渲染 DPI，null 取配置
     */
    public AnalyzeResult analyze(Path path, String instruction, Integer dpi) {
        double started = now();

        // ── 1) 文档 → 带页码的结构化文本 ───────────────────────────
        DocumentText doc = documentReader.read(path, dpi, false, null);
        if (doc.getError() != null && !doc.getError().isBlank()) {
            return AnalyzeResult.of(
                    "> ⚠️ **无法分析该文件**：" + doc.getError(),
                    doc.summary(),
                    doc.getError());
        }

        if (doc.text() == null || doc.text().strip().isEmpty()) {
            return AnalyzeResult.of(
                    "> ⚠️ **未从文件中提取到任何文本**。\n>\n"
                            + "> 可能原因：扫描件清晰度过低、平台 OCR 未能识别，或文件为空白页。\n"
                            + "> 建议：确认文件内容正常；用 `.env` 的 `OCR_PLATFORM_DPI` 提高渲染 DPI "
                            + "后重试（仅排障/印章两遍法有用）；或先用 `/health` 确认平台 OCR 是否可用。\n"
                            + "> 注意：本地 OCR 兜底已于 2026-09-18 移除，平台不可用时**没有备用引擎**。",
                    doc.summary(),
                    "未提取到文本");
        }

        // ── 2) 截断（按页）────────────────────────────────────────
        Truncation truncation = truncate(doc, settings.getLlm().getMaxInputChars());
        String userPrompt = Prompts.buildUserPrompt(
                truncation.text(), doc.pageCount(), instruction, truncation.note());

        // ── 3) 调用模型（temperature=0：审计场景要求可复现）────────
        Map<String, Object> docSummary = doc.summary();
        boolean truncated = truncation.truncated();
        String note = truncation.note();
        LlmClient.ChatResult chat;
        try {
            chat = llmClient.chat(
                    userPrompt,
                    Prompts.SYSTEM_PROMPT,
                    0.0,
                    (double) Math.max(1, settings.getGateway().getTimeoutSeconds()),
                    null,
                    null);
        } catch (Exception exc) {
            // 要把失败原因如实告知使用者
            String kind = exc.getClass().getSimpleName();
            Map<String, Object> llmFail = new LinkedHashMap<>();
            llmFail.put("ok", false);
            return AnalyzeResult.of(
                    "> ⚠️ **模型调用失败**：`" + kind + "` " + exc.getMessage() + "\n>\n"
                            + "> 排查：① 推理服务是否可达（`/health?with_llm=true`）　"
                            + "② `LLM_TIMEOUT` 是否偏小　③ 模型名是否正确",
                    docSummary,
                    llmFail,
                    truncated,
                    note,
                    "模型调用失败：" + kind);
        }

        String markdown = chat.text();
        String warning = "";

        if (markdown == null || markdown.isEmpty()) {
            // 空输出必须给出**可操作的原因**，不能含糊说"系统繁忙"——审计场景要能解释清楚
            if (chat.isTruncated()) {
                markdown = "> ⚠️ **模型输出被截断，未给出最终结论**\n>\n"
                        + "> 思维链占满了全部输出额度（`max_tokens=" + settings.getLlm().getMaxTokens() + "`，"
                        + "思考内容约 " + chat.reasoningChars() + " 字），最终答案被挤掉了。\n>\n"
                        + "> **处理**：调大 `.env` 的 `LLM_MAX_TOKENS`；或把文档拆分后分次分析；"
                        + "也可设 `LLM_ENABLE_THINKING=false` 换速度（但抽取质量会下降）。";
                warning = "模型输出被 max_tokens 截断，未给出结论";
            } else if (chat.reasoning() != null && !chat.reasoning().isEmpty()) {
                markdown = "> ⚠️ **模型只输出了思考过程，没有给出最终结论**\n>\n"
                        + "> 思考内容约 " + chat.reasoningChars() + " 字，"
                        + "`finish_reason=" + (chat.finishReason() == null || chat.finishReason().isEmpty()
                                ? "-" : chat.finishReason()) + "`。\n"
                        + "> **处理**：重试一次，或调大 `LLM_MAX_TOKENS`。";
                warning = "模型未给出结论（仅返回思考内容）";
            } else {
                markdown = "> ⚠️ " + Prompts.EMPTY_OUTPUT_HINT;
                warning = "模型返回空内容";
            }
        }

        if (truncated && warning.isEmpty()) {
            warning = "原文超长，仅前若干页参与分析";
        }

        // 输出侧机器校验：答案里的每个数字能不能在原文找到。
        // 这是"禁止虚构"的**机器兜底**——不依赖模型的自觉，也不依赖它的自评分。
        List<String> pages = new ArrayList<>();
        for (PageText page : doc.getPages()) {
            pages.add(page.getText() == null ? "" : page.getText());
        }
        Map<String, Object> verification = Checks.verifyNumbersInSource(markdown, pages);
        if ("warn".equals(verification.get("status")) && warning.isEmpty()) {
            warning = "有数字在原文中未找到，请重点核对";
        }

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("ok", true);
        llm.put("model", chat.model());
        llm.put("prompt_tokens", chat.promptTokens());
        llm.put("completion_tokens", chat.completionTokens());
        llm.put("reasoning_chars", chat.reasoningChars());
        llm.put("finish_reason", chat.finishReason());
        llm.put("elapsed", round2(now() - started));

        return new AnalyzeResult(
                markdown, docSummary, llm, verification, truncated, note, warning);
    }

    /** 截断结果：文本、是否截断、给模型与前端看的说明。 */
    record Truncation(String text, boolean truncated, String note) {
    }

    /** 按<b>页</b>累加直到接近上限，返回 (文本, 是否截断, 说明)。 */
    static Truncation truncate(DocumentText doc, int limit) {
        if (doc.charCount() <= limit) {
            return new Truncation(doc.labeledText(), false, "");
        }

        List<String> kept = new ArrayList<>();
        int total = 0;
        for (PageText page : doc.getPages()) {
            String body = page.getText() == null || page.getText().strip().isEmpty()
                    ? "（本页未识别到文本）"
                    : page.getText().strip();
            String block = page.header() + "\n" + body;
            // 至少保留一页，避免首屏超大时一页都不给
            if (!kept.isEmpty() && total + block.length() > limit) {
                break;
            }
            kept.add(block);
            total += block.length();
        }

        String note = "因篇幅限制，本次**仅提供前 " + kept.size() + " 页**（原文共 " + doc.pageCount() + " 页），"
                + "未提供的页面未参与分析，因此结论**不覆盖**那部分内容。";
        return new Truncation(String.join("\n\n", kept), true, note);
    }

    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    /** 对齐 Python 的 {@code round(x, 2)}（用于展示）。 */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
