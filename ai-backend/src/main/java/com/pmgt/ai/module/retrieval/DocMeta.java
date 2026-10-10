package com.pmgt.ai.module.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份文档的<b>过滤用元数据</b>（项目 / 附件 / 阶段 / 密级 / 资料类型 / 来源 / 入库时间）。
 *
 * <p>为什么单独一层而不是塞进 {@code StoredDoc}：{@code work/docs/<doc_id>.json} 与
 * {@code /documents} 的响应形状是<b>对外契约</b>（主系统与前端按它读），
 * 而这些字段是"检索过滤用的索引元数据"，来源是主系统的附件表（{@code attachment_id → project_id/密级}），
 * 与解析结果不是一回事。单独放一份 side-car（{@code work/doc-meta.json}）之后：
 * ① 老契约一个字段都不变；② 元数据可以在不重解析文档的情况下补录（重解析一次要跑 OCR）；
 * ③ OpenSearch 只写它需要的 keyword 字段。
 *
 * <p>⚠️ <b>字段缺失的语义</b>：缺失 = "这个信息不存在"，因此在<b>带约束的作用域</b>下
 * 一律视为"不在作用域内"（{@link SearchScope#matches(String, DocMeta)}），
 * 而不是"通配通过"——后者会让权限过滤形同虚设。
 */
public record DocMeta(
        String attachmentId,
        String projectId,
        String phaseCode,
        String securityLevel,
        String docType,
        String source,
        String createdAt) {

    public static final DocMeta EMPTY = new DocMeta("", "", "", "", "", "", "");

    public boolean isEmpty() {
        return blank(attachmentId) && blank(projectId) && blank(phaseCode)
                && blank(securityLevel) && blank(docType) && blank(source) && blank(createdAt);
    }

    /** 索引文档 / 接口响应用的键名（snake_case，与 mapping 的字段名一一对应）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attachment_id", nullSafe(attachmentId));
        out.put("project_id", nullSafe(projectId));
        out.put("phase_code", nullSafe(phaseCode));
        out.put("security_level", nullSafe(securityLevel));
        out.put("doc_type", nullSafe(docType));
        out.put("source", nullSafe(source));
        out.put("created_at", nullSafe(createdAt));
        return out;
    }

    /** 从 snake_case 映射读回（缺字段按空串，不编造）。 */
    public static DocMeta fromMap(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return EMPTY;
        }
        return new DocMeta(
                text(map.get("attachment_id")),
                text(map.get("project_id")),
                text(map.get("phase_code")),
                text(map.get("security_level")),
                text(map.get("doc_type")),
                text(map.get("source")),
                text(map.get("created_at")));
    }

    /** 只保留非空字段的稀疏映射（写索引时用；缺什么就不写什么）。 */
    public Map<String, Object> sparseMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "attachment_id", attachmentId);
        putIfPresent(out, "project_id", projectId);
        putIfPresent(out, "phase_code", phaseCode);
        putIfPresent(out, "security_level", securityLevel);
        putIfPresent(out, "doc_type", docType);
        putIfPresent(out, "source", source);
        putIfPresent(out, "created_at", createdAt);
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (!blank(value)) {
            target.put(key, value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
