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

    /**
     * 带<b>作用域硬约束</b>的检索（{@code doc_ids} / {@code attachmentIds} / {@code projectIds}）。
     *
     * <p>为什么要有它：{@code docId} 只能表达"一份文档"，而"先过滤再检索"需要能表达
     * 项目/附件维度的交集（权限合规与效率都靠它）。默认实现退化为"只认单个 docId"，
     * 老实现（及测试替身）因此不需要改就能编译——但<b>只有真正实现了过滤的后端</b>
     * （{@code RetrievalService}）才能保证"作用域外的文档不可能被返回"。
     */
    default SearchResult searchInScope(String query, Integer topK, SearchScope scope) {
        String single = scope != null && scope.docIds().size() == 1 ? scope.docIds().iterator().next() : null;
        return search(query, topK, single);
    }

    /** 检索结果。字段名对齐 Python 版，主系统/模型看到的结构不变。 */
    record SearchResult(int found, List<Hit> hits, String retrieval, boolean reranked, String note) {
    }

    /**
     * 一条命中。
     *
     * <p>P1-α 补了五个字段（设计 §3.4.4/§3.4.5），它们都服务"可溯源 + 省一次 {@code read_page}"：
     * <ul>
     *   <li>{@code chunkKey}：切片唯一键（含 {@code clean_rev/chunk_rev} 版本段）——
     *       引用回填、检索级评测的"正确切片"比对、两次解析差异对比都要它；</li>
     *   <li>{@code parentKey} / {@code parentSummary}：<b>父子块（small-to-big）</b>——
     *       子块检索命中后，把同页父块摘要一并给模型（设计 §3.4.4 的轻量形态）；</li>
     *   <li>{@code isTable} / {@code sectionPath}：表格片与章节路径（展示与后续章节树父块的接缝）。</li>
     * </ul>
     *
     * <p>老的七参构造<b>保留</b>（测试与外部实现不必改）：新字段按"未知"填。
     */
    record Hit(
            String docId,
            String filename,
            int pageNo,
            String chunkKey,
            double score,
            double keywordScore,
            double vectorScore,
            String text,
            boolean isTable,
            String parentKey,
            String parentSummary,
            String sectionPath) {

        /** 兼容构造（无切片元数据的命中）。 */
        public Hit(String docId, String filename, int pageNo, double score, double keywordScore,
                   double vectorScore, String text) {
            this(docId, filename, pageNo, null, score, keywordScore, vectorScore, text, false, null, null, "");
        }

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
         *
         * <p>字段<b>只在有值时出现</b>（{@code chunk_key}/{@code parent_summary} 等）：
         * 两条后端（local / opensearch）必须产出同一套键，否则"结构一致"的回归会红
         * （见 {@code RetrievalBackendParityTest}）——所以这一层不做后端相关的分支。
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
            if (chunkKey != null && !chunkKey.isBlank()) {
                out.put("chunk_key", chunkKey);
            }
            if (isTable) {
                out.put("is_table", true);
            }
            if (parentKey != null && !parentKey.isBlank()) {
                out.put("parent_key", parentKey);
            }
            if (parentSummary != null && !parentSummary.isBlank()) {
                out.put("parent_summary", parentSummary);
            }
            if (sectionPath != null && !sectionPath.isBlank()) {
                out.put("section_path", sectionPath);
            }
            return out;
        }
    }
}
