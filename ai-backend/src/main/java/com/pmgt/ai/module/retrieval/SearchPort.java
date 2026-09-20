package com.pmgt.ai.module.retrieval;

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

        public Map<String, Object> toMap() {
            return Map.of(
                    "doc_id", docId,
                    "filename", filename,
                    "page_no", pageNo,
                    "score", score,
                    "keyword_score", keywordScore,
                    "vector_score", vectorScore,
                    "text", text);
        }
    }
}
