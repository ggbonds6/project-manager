package com.pmgt.ai.module.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索接缝：`tools.search_documents` 只依赖它，不依赖具体实现。
 *
 * <p>这样换检索算法（关键词 → 混合 → OpenSearch kNN）不用动工具层、提示词与问答编排。
 */
public interface SearchPort {

    SearchResult search(String query, Integer topK, String docId);

    /** 检索结果。字段名对齐 Python 版，主系统/模型看到的结构不变。 */
    record SearchResult(int found, List<Hit> hits, String retrieval, boolean reranked, String note) {
    }

    record Hit(
            String docId,
            String filename,
            int pageNo,
            double score,
            double keywordScore,
            double vectorScore,
            String text) {

        /**
         * 命中字典（给模型、给前端）。
         *
         * <p>⚠️ <b>用 {@link LinkedHashMap} 而不是 {@code Map.of}</b>（2026-09-20 改）：
         * <ol>
         *   <li>{@code Map.of} 的迭代顺序是<b>未指定</b>的（随键的哈希变化），JSON 里的字段顺序会漂；
         *       保序之后工具结果的形状每次调用都一致，便于人工比对与回归；</li>
         *   <li>{@code Map.of} 不可变，而工具层要在命中上补一个 {@code cite} 编号
         *       （结构化引用，见 {@code CitationRegistry}）——把共享的不可变 Map 复制成可变副本，
         *       是"每次问答的临时状态不许写回共享对象"这条约束的落地方式。</li>
         * </ol>
         *
         * <p>调用方若要在结果上补字段（如 {@code cite}），<b>请再复制一份</b>，不要直接改返回值：
         * 目前每次调用都是新建的，但契约上它是"只读快照"。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("doc_id", docId);
            out.put("filename", filename);
            out.put("page_no", pageNo);
            out.put("score", score);
            out.put("keyword_score", keywordScore);
            out.put("vector_score", vectorScore);
            out.put("text", text);
            return out;
        }
    }
}
