package com.pmgt.ai.module.retrieval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索作用域（<b>硬约束</b>）：{@code doc_ids} / {@code attachmentIds} / {@code projectIds} / {@code securityLevels}
 * 四个维度。
 *
 * <p>为什么要有它：请求级 {@code doc_ids} 在改造前<b>只是"提示词级"约束</b>
 * （{@code QaService} 只把它写进 system 提示词的文档清单，模型不传 {@code doc_id} 就会检索全库），
 * 而 OpenSearch 路径更是<b>全库 kNN 之后在客户端丢越界命中</b>——越界内容虽然不会返回，
 * 但它<b>占了 k 个候选位</b>，会把本该命中的内容挤出去。
 * 把作用域下推到 DocStore 过滤与 kNN 的 {@code filter} 之后，"越界即空"才成立。
 *
 * <p><b>空维度 = 该维度不加约束</b>（{@code docIds} 为空表示"不限文档"）；
 * 但某些维度一旦给了值，<b>元数据缺失的文档一律排除</b>（缺失 ≠ 通配）。
 * 四个维度之间是<b>交集</b>。
 *
 * <p>⚠️ 本类只做"检索范围"的收窄，<b>不做鉴权</b>：谁有权问哪些文档由主系统的 scope_token 决定，
 * AI 侧只负责"给什么范围就只在这个范围里检索"。
 *
 * <p>⚠️ <b>请求作用域是上界，模型不能把它放大</b>：模型在工具调用里给的 {@code doc_id}
 * 与请求作用域<b>取交集</b>（{@link #intersect(SearchScope)}），交集为空就是"越界即空"，
 * 绝不退回请求作用域或全库。
 */
public record SearchScope(Set<String> docIds, Set<String> attachmentIds, Set<String> projectIds,
                          Set<String> securityLevels) {

    public SearchScope {
        docIds = immutable(docIds);
        attachmentIds = immutable(attachmentIds);
        projectIds = immutable(projectIds);
        securityLevels = immutable(securityLevels);
    }

    /** 兼容三参调用点（无密级维度 = 该维度不加约束）。 */
    public SearchScope(Set<String> docIds, Set<String> attachmentIds, Set<String> projectIds) {
        this(docIds, attachmentIds, projectIds, Set.of());
    }

    /** 无约束（检索全部已入库文档）——老行为的等价物。 */
    public static SearchScope empty() {
        return new SearchScope(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 只限定文档：请求里带了 {@code doc_ids} 时用它。 */
    public static SearchScope ofDocIds(Collection<String> docIds) {
        return new SearchScope(clean(docIds), Set.of(), Set.of(), Set.of());
    }

    /** 兼容三参调用点（见 {@link #SearchScope(Set, Set, Set)}）。 */
    public static SearchScope of(Collection<String> docIds, Collection<String> attachmentIds,
                                 Collection<String> projectIds) {
        return of(docIds, attachmentIds, projectIds, null);
    }

    /** 四维全给（密级为 {@code null} 或空 = 该维度不加约束）。 */
    public static SearchScope of(Collection<String> docIds, Collection<String> attachmentIds,
                                 Collection<String> projectIds, Collection<String> securityLevels) {
        return new SearchScope(clean(docIds), clean(attachmentIds), clean(projectIds),
                clean(securityLevels));
    }

    /** 是否有任何约束（无约束时 OpenSearch 不加 filter、本地路径不做交集）。 */
    public boolean constrained() {
        return !docIds.isEmpty() || !attachmentIds.isEmpty() || !projectIds.isEmpty()
                || !securityLevels.isEmpty();
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
        if (!projectIds.isEmpty() && !projectIds.contains(effective.projectId())) {
            return false;
        }
        // 密级与其它维度同一纪律：给了值就必须命中，元数据缺失（空串）一律排除
        return securityLevels.isEmpty() || securityLevels.contains(effective.securityLevel());
    }

    /**
     * 本作用域 ∩ {@code upper}（交集）。
     *
     * <p>用途：把"请求作用域"当作不可放大的<b>上界</b>，与"模型给的 docId"求交集——
     * 模型只可能把范围收窄，不可能把范围撑开。空维度按"该维度无约束"处理，
     * 因此交集在无约束维度上就取另一侧的值。
     *
     * <p>注意：本方法<b>只看 docIds 维度能不能被证明越界</b>（返回后的 {@link #constrained()}
     * 为 true 而 {@code docIds} 为空时，其余维度仍会在检索层生效）。
     * 是否"越界即空"由调用方按需判断（见 {@code Tools.searchDocuments}）。
     */
    public SearchScope intersect(SearchScope upper) {
        if (upper == null || !upper.constrained()) {
            return this;
        }
        if (!constrained()) {
            return upper;
        }
        return new SearchScope(
                intersectSets(docIds, upper.docIds),
                intersectSets(attachmentIds, upper.attachmentIds),
                intersectSets(projectIds, upper.projectIds),
                intersectSets(securityLevels, upper.securityLevels));
    }

    /**
     * 交集是否已被证明为空（越界即空）。
     *
     * <p>只判 {@code docIds} 维度，理由：它是唯一"逐个枚举"的维度，能<b>确定地</b>判空；
     * 其余维度（项目/附件/密级）在检索层按元数据求交，判空需要读元数据，
     * 不在这一层做（避免为了一个布尔判断多读一遍元数据）。
     *
     * <p>调用方通常是"已经把 {@code upper} 并进来了的 {@code this}"（即 {@code this} 已是交集），
     * 此时传 {@code upper} 作上界即可。
     */
    public boolean provablyEmptyAgainst(SearchScope upper) {
        if (docIds.isEmpty()) {
            return false;
        }
        Set<String> bound = upper == null ? Set.of() : upper.docIds;
        for (String docId : docIds) {
            if (bound.isEmpty() || bound.contains(docId)) {
                return false;
            }
        }
        return true;
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
        if (!securityLevels.isEmpty()) {
            sb.append(sb.length() > 0 ? ", " : "").append("security_levels=").append(securityLevels);
        }
        return sb.toString();
    }

    private static Set<String> intersectSets(Set<String> left, Set<String> right) {
        if (right.isEmpty()) {
            return left;
        }
        if (left.isEmpty()) {
            return right;
        }
        Set<String> out = new LinkedHashSet<>(left);
        out.retainAll(right);
        return out;
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
