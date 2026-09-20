package com.pmgt.ai.module.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>一次问答请求</b>的引用注册表：把"模型写的 {@code [1][3]}"与"前端的出处列表"对上。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>在它之前，{@code /chat} 只把命中/整页原文喂给模型，页码信息在 {@code trace.brief}
 * 里被压成一句人类可读的摘要——前端拿到答案里的 {@code [合同.pdf P3]} 也没法"跳到第 3 页"，
 * 因为页码与片段都被丢掉了（见本次改造的需求说明）。所以这里为每次问答维护一张
 * <b>{@code doc_id + page_no} 去重的表</b>：命中一次就登记一条，前端按编号就能定位到原文。
 *
 * <h2>编号为什么稳定</h2>
 *
 * <ol>
 *   <li><b>按首次命中顺序分配</b>：{@code cite = 1,2,3…}，靠 {@link LinkedHashMap} 的插入顺序保序，
 *       不依赖任何排序、哈希或遍历顺序；</li>
 *   <li><b>只增不改</b>：编号一旦分配就写进了已经发给模型的工具结果（命中里的 {@code cite} 字段），
 *       <b>后续任何一轮检索都不可能改动它</b>——否则模型写的 {@code [2]} 会指向另一页；</li>
 *   <li><b>同一页只占一个号</b>：同一页被两个切片命中、或被 {@code read_page} 再读一遍，
 *       仍然返回第一次分配的号（否则前端会出现两条指向同一页的引用，模型也可能写出两个号指同一页）。</li>
 * </ol>
 *
 * <h2>为什么不是 Bean / static / ThreadLocal</h2>
 *
 * <p>它是<b>请求级状态</b>：{@code Tools} 与 {@code ToolAgent} 都是 Spring 单例，
 * 把注册表塞进它们（或塞进 static / ThreadLocal）会让并发问答互相污染编号、清理不当还会串号。
 * 所以由 {@link QaService#ask} 每次问答 {@code new} 一份，显式当参数传进工具调用循环。
 * 这样"谁能写注册表"在类型系统里就看得见，也不需要任何并发清理逻辑。
 */
public final class CitationRegistry {

    /**
     * {@code snippet} 的字数上限（<b>含</b>截断省略号）。
     *
     * <p>为什么是 200：前端用它做引用卡片的摘要，一条引用铺满屏幕就失去了"快速核对"的意义；
     * 需要原文时前端可以按 {@code doc_id + page_no} 去 {@code /documents/{id}} 取整页。
     */
    public static final int MAX_SNIPPET_CHARS = 200;

    /** 截断标记。用单字符省略号：保证截断后的总数仍然 ≤ {@link #MAX_SNIPPET_CHARS}。 */
    private static final String ELLIPSIS = "…";

    /** 键：{@code doc_id + NUL + page_no}（NUL 不可能出现在 doc_id 或页码里，不会撞键）。 */
    private static final char KEY_SEPARATOR = '\u0000';

    /** 按首次命中顺序登记（{@link LinkedHashMap} 的插入顺序就是 cite 编号顺序）。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 一条引用的内部状态。字段可变是为了"同一页后来被更高分命中时"能刷新分数与片段。 */
    private static final class Entry {

        private final int index;
        private final String docId;
        private final int pageNo;
        private String filename;
        private String snippet;
        private double score;

        private Entry(int index, String docId, String filename, int pageNo, String snippet, double score) {
            this.index = index;
            this.docId = docId;
            this.filename = filename;
            this.pageNo = pageNo;
            this.snippet = snippet;
            this.score = score;
        }
    }

    /**
     * 登记一处引用，返回它的 {@code cite} 编号（首次命中时分配新号，重复命中返回原号）。
     *
     * <p>调用点只有两个：{@code search_documents} 的每条命中、{@code read_page} 取回的页。
     *
     * @param docId    文档 ID
     * @param filename 文件名（前端展示用；同一文档多次命中时以首次登记的为准）
     * @param pageNo   页码，从 1 开始
     * @param text     该命中的片段原文（会做去换行 + 截断，见 {@link #normalizeSnippet}）
     * @param score    该命中的最终分数（rerank 后分数，未 rerank 时是融合分数）；
     *                 {@code read_page} 直接取回的页没有检索分数，传 {@code 0.0}
     * @return 该 {@code (doc_id, page_no)} 的稳定编号
     */
    public int register(String docId, String filename, int pageNo, String text, double score) {
        String key = key(docId, pageNo);
        Entry existing = entries.get(key);
        if (existing != null) {
            // 同一页再次命中：编号**不动**（模型可能已经按原编号写了答案）。
            // 但分数/片段取更高的那次——同一页通常只有最高分的那次命中才代表"为什么引它"。
            if (score > existing.score) {
                existing.score = score;
                existing.snippet = normalizeSnippet(text);
            }
            if (isBlank(existing.filename) && !isBlank(filename)) {
                // 首次登记时文件名缺失（例如 read_page 之外的路径拿不到文件名），后来补上
                existing.filename = filename;
            }
            return existing.index;
        }
        int index = entries.size() + 1;
        entries.put(key, new Entry(
                index, docId == null ? "" : docId, filename, pageNo, normalizeSnippet(text), score));
        return index;
    }

    /** 已登记的引用条数（去重后）。 */
    public int size() {
        return entries.size();
    }

    /**
     * 响应里的 {@code citations}：顺序 = 编号顺序；没有任何引用时返回空列表。
     *
     * <p>键名与顺序固定为 {@code index / doc_id / filename / page_no / snippet / score}，
     * 前端按它渲染引用列表并据此跳转到附件第 {@code page_no} 页。
     */
    public List<Map<String, Object>> toList() {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", entry.index);
            item.put("doc_id", entry.docId);
            item.put("filename", entry.filename == null ? "" : entry.filename);
            item.put("page_no", entry.pageNo);
            item.put("snippet", entry.snippet);
            item.put("score", entry.score);
            out.add(item);
        }
        return out;
    }

    private static String key(String docId, int pageNo) {
        return (docId == null ? "" : docId) + KEY_SEPARATOR + pageNo;
    }

    /**
     * snippet 规范化：<b>去换行</b>（连续的空白/换行折成一个空格）后截断到
     * {@link #MAX_SNIPPET_CHARS} 字。
     *
     * <p>为什么折空白而不是只删 {@code \n}：OCR 出的正文里有大量制表与连续空格，
     * 原样塞进 JSON 会让前端卡片出现大片空洞；折叠只影响展示副本，原文（{@code hit.text}
     * 与 {@code read_page.text}）一个字都不动。
     */
    static String normalizeSnippet(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").strip(), MAX_SNIPPET_CHARS);
    }

    /** 截断到 {@code max} 字（截断时末尾补省略号，<b>总长仍 ≤ max</b>）。 */
    static String truncate(String value, int max) {
        if (max <= 0) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
