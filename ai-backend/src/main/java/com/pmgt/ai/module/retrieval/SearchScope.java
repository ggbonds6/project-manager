package com.pmgt.ai.module.retrieval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索作用域（<b>硬约束</b>）：{@code doc_ids} / {@code attachmentIds} / {@code projectIds} 三个维度。
 *
 * <p>为什么要有它：请求级 {@code doc_ids} 在改造前<b>只是"提示词级"约束</b>
 * （{@code QaService} 只把它写进 system 提示词的文档清单，模型不传 {@code doc_id} 就会检索全库），
 * 而 OpenSearch 路径更是<b>全库 kNN 之后在客户端丢越界命中</b>——越界内容虽然不会返回，
 * 但它<b>占了 k 个候选位</b>，会把本该命中的内容挤出去。
 * 把作用域下推到 DocStore 过滤与 kNN 的 {@code filter} 之后，"越界即空"才成立。
 *
 * <p><b>空维度 = 该维度不加约束</b>（{@code docIds} 为空表示"不限文档"）；
 * 但某些维度一旦给了值，<b>元数据缺失的文档一律排除</b>（缺失 ≠ 通配）。
 * 三个维度之间是<b>交集</b>。
 *
 * <p>⚠️ 本类只做"检索范围"的收窄，<b>不做鉴权</b>：谁有权问哪些文档由主系统的 scope_token 决定，
 * AI 侧只负责"给什么范围就只在这个范围里检索"。
 */
public record SearchScope(Set<String> docIds, Set<String> attachmentIds, Set<String> projectIds) {

    public SearchScope {
        docIds = immutable(docIds);
        attachmentIds = immutable(attachmentIds);
        projectIds = immutable(projectIds);
    }

    /** 无约束（检索全部已入库文档）——老行为的等价物。 */
    public static SearchScope empty() {
        return new SearchScope(Set.of(), Set.of(), Set.of());
    }

    /** 只限定文档：请求里带了 {@code doc_ids} 时用它。 */
    public static SearchScope ofDocIds(Collection<String> docIds) {
        return new SearchScope(clean(docIds), Set.of(), Set.of());
    }

    public static SearchScope of(Collection<String> docIds, Collection<String> attachmentIds,
                                Collection<String> projectIds) {
        return new SearchScope(clean(docIds), clean(attachmentIds), clean(projectIds));
    }

    /** 是否有任何约束（无约束时 OpenSearch 不加 filter、本地路径不做交集）。 */
    public boolean constrained() {
        return !docIds.isEmpty() || !attachmentIds.isEmpty() || !projectIds.isEmpty();
    }

    /**
     * 该文档是否在作用域内。
     *
     * @param docId 切片所属文档
     * @param meta  该文档的过滤元数据（可能为 {@code null} 或 {@link DocMeta#EMPTY}）
     */
    public boolean matches(String docId, DocMeta meta) {
        if (!docIds.isEmpty() && !docIds.contains(docId)) {
            return false;
        }
        DocMeta effective = meta == null ? DocMeta.EMPTY : meta;
        if (!attachmentIds.isEmpty() && !attachmentIds.contains(effective.attachmentId())) {
            return false;
        }
        return projectIds.isEmpty() || projectIds.contains(effective.projectId());
    }

    /** 供日志与 note：把生效的约束写清楚（越界即空是可解释的）。 */
    public String describe() {
        if (!constrained()) {
            return "全部文档";
        }
        StringBuilder sb = new StringBuilder();
        if (!docIds.isEmpty()) {
            sb.append("doc_ids=").append(docIds);
        }
        if (!attachmentIds.isEmpty()) {
            sb.append(sb.length() > 0 ? ", " : "").append("attachment_ids=").append(attachmentIds);
        }
        if (!projectIds.isEmpty()) {
            sb.append(sb.length() > 0 ? ", " : "").append("project_ids=").append(projectIds);
        }
        return sb.toString();
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> clean(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.strip());
            }
        }
        return out;
    }
}
