package com.pmgt.ai.module.clean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认清洗实现：按 {@link CleanRules#ALL} 的顺序跑规则，产出 {@link CleanResult}。
 *
 * <p><b>关闭时（{@code ai.clean.enabled=false}，默认）行为与接入前逐字一致</b>：
 * 页面文本原样返回（{@code after == before}）、{@code removed}/{@code normalized} 为空、
 * counters 全 0。这不是"顺手洗一下但不上报"，而是"一个字符都不动"——
 * 回归口径见 {@code CleanRevRegressionTest}。
 *
 * <h2>产物与留痕</h2>
 * <ul>
 *   <li>{@link #summaryJson(CleanResult)}：{@code clean_diff} 产物的<b>摘要</b>（≤4000 字符，
 *       主系统 DB 只存摘要）；</li>
 *   <li>{@link #fullTraceJson(CleanResult)}：<b>全文前后对照</b>，落 {@code work/clean/<docId>.json}
 *       （可重建物，不进主系统——设计 I2 的纪律）。</li>
 * </ul>
 */
@Slf4j
public class RuleBasedTextCleaner implements TextCleaner {

    /** 摘要里逐条痕迹的条数上限（超出的只计数，全文在 {@code work/clean/}）。 */
    static final int SUMMARY_TRACES = 20;

    /** 摘要硬上限（主系统 {@code content_json} 的口径是 4000 字符）。 */
    static final int SUMMARY_LIMIT = 4000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CleanOptions options;

    public RuleBasedTextCleaner(AiSettings settings) {
        this(CleanOptions.of(settings));
    }

    public RuleBasedTextCleaner(CleanOptions options) {
        this.options = options == null ? CleanOptions.disabled() : options;
        for (String unknown : this.options.unknownRules()) {
            // 拼错规则 id 的后果是"规则没生效"，必须看得见（静默忽略是最难查的一类配置问题）
            log.warn("ai.clean.rules 里有未知规则 id：{}；已知 id={}", unknown, CleanRules.ALL.stream()
                    .map(CleanRule::id).toList());
        }
    }

    @Override
    public boolean enabled() {
        return options.enabled();
    }

    @Override
    public CleanOptions options() {
        return options;
    }

    @Override
    public CleanResult cleanDocument(CleanContext context) {
        List<CleanResult.CleanPage> rawPages = new ArrayList<>();
        for (CleanContext.PageInput input : context.pages()) {
            rawPages.add(new CleanResult.CleanPage(input.pageNo(), input.source(), input.safeText(),
                    input.safeText(), List.of()));
        }
        if (!options.enabled()) {
            return CleanResult.disabled(context.docId(), context.cleanRev(),
                    "清洗关闭（ai.clean.enabled=false）：切片输入 = 解析原文，逐字未改", rawPages);
        }
        CleanWork work = new CleanWork(options, context.docId(), context.filename(), context.cleanRev(),
                context);
        for (CleanRule rule : CleanRules.ALL) {
            if (options.ruleEnabled(rule)) {
                rule.apply(work);
            }
        }
        return work.toResult();
    }

    // ══════════════════════════════════════════════════════════════
    // 产物：摘要（进 DB）与全文（留 work/）
    // ══════════════════════════════════════════════════════════════

    /** {@code clean_diff} 摘要（≤4000 字符）。 */
    public static String summaryJson(CleanResult result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("doc_id", result.docId());
        root.put("clean_rev", result.cleanRev());
        root.put("enabled", result.enabled());
        if (result.reason() != null) {
            root.put("reason", result.reason());
        }
        // note 放在最前面：摘要超长时会按字符截断，而"全文在哪"是这份摘要最有用的信息之一
        root.put("note", "清洗前后对照的全文见 AI 服务 work/clean/<docId>.json（DB 只存摘要；"
                + "removed_samples 是最多 " + SUMMARY_TRACES + " 条被删行样本，供人工复核误删）");
        CleanResult.Stats stats = result.stats();
        root.put("pages", stats.pages());
        root.put("pages_cleaned", stats.pagesCleaned());
        root.put("pages_skipped", stats.pagesSkipped());
        root.put("before_chars", stats.beforeChars());
        root.put("after_chars", stats.afterChars());
        root.put("removed_chars", stats.removedChars());
        root.put("removed_chars_ratio", stats.removedCharsRatio());
        root.put("removed_lines", stats.removedLines());
        root.put("pages_all_removed", stats.pagesAllRemoved());
        root.put("duplicate_paragraphs", stats.duplicateParagraphs());
        root.put("table_blocks", stats.tableBlocks());
        root.put("table_chars", stats.tableChars());
        root.put("replacement_chars", stats.replacementChars());
        if (stats.totalPages() != null) {
            root.put("total_pages", stats.totalPages());
        }
        ObjectNode fired = root.putObject("rules_fired");
        result.firedByRule().forEach(fired::put);
        ArrayNode removed = root.putArray("removed_samples");
        for (CleanResult.TraceItem item : result.removedSamples(SUMMARY_TRACES)) {
            ObjectNode node = removed.addObject();
            node.put("page_no", item.pageNo());
            node.put("rule", item.rule());
            node.put("line", item.text());
        }
        ArrayNode normalized = root.putArray("normalized_samples");
        int count = 0;
        for (CleanResult.TraceItem item : result.normalized()) {
            if (count++ >= SUMMARY_TRACES) {
                break;
            }
            ObjectNode node = normalized.addObject();
            node.put("page_no", item.pageNo());
            node.put("rule", item.rule());
            node.put("before", item.text());
        }
        return clip(MAPPER, root);
    }

    /** 全文前后对照（落 {@code work/clean/<docId>.json}，可重建物）。 */
    public static String fullTraceJson(CleanResult result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("doc_id", result.docId());
        root.put("clean_rev", result.cleanRev());
        root.put("enabled", result.enabled());
        if (result.reason() != null) {
            root.put("reason", result.reason());
        }
        root.set("stats", MAPPER.valueToTree(statsMap(result.stats())));
        ObjectNode fired = root.putObject("rules_fired");
        result.firedByRule().forEach(fired::put);
        ArrayNode removed = root.putArray("removed");
        for (CleanResult.TraceItem item : result.removed()) {
            ObjectNode node = removed.addObject();
            node.put("page_no", item.pageNo());
            node.put("rule", item.rule());
            node.put("line", item.text());
        }
        ArrayNode normalized = root.putArray("normalized");
        for (CleanResult.TraceItem item : result.normalized()) {
            ObjectNode node = normalized.addObject();
            node.put("page_no", item.pageNo());
            node.put("rule", item.rule());
            node.put("before", item.text());
        }
        ArrayNode pages = root.putArray("pages");
        for (CleanResult.CleanPage page : result.pages()) {
            ObjectNode node = pages.addObject();
            node.put("page_no", page.pageNo());
            node.put("source", page.source());
            node.put("before", page.before());
            node.put("after", page.after());
            ArrayNode tables = node.putArray("table_ranges");
            for (CleanResult.TableRange range : page.tables()) {
                ObjectNode table = tables.addObject();
                table.put("start_line", range.startLine());
                table.put("end_line", range.endLine());
                table.put("rows", range.rows());
            }
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"doc_id\":\"" + result.docId() + "\",\"error\":\"序列化失败\"}";
        }
    }

    static Map<String, Object> statsMap(CleanResult.Stats stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", stats.pages());
        out.put("pages_cleaned", stats.pagesCleaned());
        out.put("pages_skipped", stats.pagesSkipped());
        out.put("before_chars", stats.beforeChars());
        out.put("after_chars", stats.afterChars());
        out.put("removed_chars", stats.removedChars());
        out.put("removed_lines", stats.removedLines());
        out.put("pages_all_removed", stats.pagesAllRemoved());
        out.put("duplicate_paragraphs", stats.duplicateParagraphs());
        out.put("table_blocks", stats.tableBlocks());
        out.put("table_chars", stats.tableChars());
        out.put("replacement_chars", stats.replacementChars());
        out.put("total_pages", stats.totalPages());
        out.put("removed_chars_ratio", stats.removedCharsRatio());
        return out;
    }

    private static String clip(ObjectMapper mapper, ObjectNode node) {
        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
        return json.length() <= SUMMARY_LIMIT ? json : json.substring(0, SUMMARY_LIMIT);
    }
}
