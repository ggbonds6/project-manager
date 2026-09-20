package com.pmgt.module.ai.service;

import com.pmgt.module.ai.client.AiJson;
import com.pmgt.module.ai.dto.AiChatResponse;
import com.pmgt.module.ai.dto.AiCitationVO;
import com.pmgt.module.ai.dto.AiSystemDataVO;
import com.pmgt.module.ai.dto.AiToolTraceVO;
import com.pmgt.module.attach.entity.Attachment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 服务 {@code /chat} 响应 → §9 #9 契约的<b>适配器</b>。
 *
 * <p>单独成类的理由：这是整个集成里字段映射最密集的一段（snake_case→camelCase、
 * 秒→毫秒、trace→toolTrace、引用回填 attachmentId），把它做成一个<b>纯函数式组件</b>
 * 就能用单测把契约钉死，而不必启动 Spring 上下文或连数据库。
 *
 * <h2>引用（citations）怎么来的</h2>
 * 直接读 AI 服务的 {@code data.citations}：
 * {@code [{index, doc_id, filename, page_no, snippet, score}]}，编号由 AI 服务内部的引用
 * 注册表分配，与答案里的 {@code [1]}、{@code [2]} 一一对应。
 * <b>字段缺失（AI 服务尚未发版）时一律给空数组</b>——绝不从 {@code trace.brief} 里
 * 正则抠页码来"凑"引用：那种引用页码不可靠，而"引用可核验"是本项目建立信任的关键，
 * 宁可前端显示"本次没有可跳转的出处"。
 *
 * <h2>为什么会 degraded</h2>
 * AI 服务在向量检索不可用时会降级为关键词召回，并把说明写进检索工具的 {@code note}
 * （随 {@code trace.brief} 带出，形如"…（向量服务不可用，本次仅关键词召回）"）。
 * 这个标记必须透给用户，否则"答不出来"会被误判成"文档里没有"。
 */
@Component
public class AiAnswerAdapter {

    private static final String NO_DOC_ANSWER_HINT = "还没有可问答的文档";

    /** 从 brief 里取命中条数：AI 服务的 search_documents 摘要形如「命中 3 段：…」。 */
    private static final Pattern HIT_COUNT = Pattern.compile("(?:命中|找到)\\s*(\\d+)\\s*(?:段|条)");

    /**
     * 把 AI 响应组装成前端契约。
     *
     * @param data           AI 服务 {@code /chat} 的 {@code data} 段
     * @param attachmentsById 作用域内附件（key = 主系统附件 id），用于回填引用
     * @param conversationId 原样回显的会话 id
     * @param elapsedMs      主系统侧统计的总耗时
     */
    public AiChatResponse adapt(Map<String, Object> data,
                               Map<Long, Attachment> attachmentsById,
                               String conversationId,
                               long elapsedMs) {
        AiChatResponse out = new AiChatResponse();
        out.setConversationId(conversationId);
        out.setAnswer(AiJson.text(data, "answer"));
        out.setCitations(citations(data, attachmentsById));
        out.setSystemData(systemData());
        out.setToolTrace(toolTrace(data));
        out.setDegraded(degraded(data));
        out.setNotice(notice(data));
        out.setElapsedMs(elapsedMs);
        return out;
    }

    /** 引用：snake_case → camelCase，并用 {@code attachment.ai_doc_id} 回填 {@code attachmentId}。 */
    private List<AiCitationVO> citations(Map<String, Object> data, Map<Long, Attachment> attachmentsById) {
        List<AiCitationVO> out = new ArrayList<>();
        Map<String, Attachment> byDocId = byDocId(attachmentsById);
        List<Map<String, Object>> raw = AiJson.asList(data.get("citations"));
        int fallbackIndex = 0;
        for (Map<String, Object> item : raw) {
            AiCitationVO vo = new AiCitationVO();
            // index 缺失时按出现顺序兜底编号——前端要靠它和答案里的 [n] 对上，
            // 给 null 会让整条引用无法定位，顺序编号至少不比现状更差
            vo.setIndex(AiJson.has(item, "index") ? AiJson.intValue(item, "index", ++fallbackIndex) : ++fallbackIndex);
            vo.setDocId(AiJson.nullableText(item, "doc_id"));
            vo.setFilename(AiJson.nullableText(item, "filename"));
            vo.setPageNo(AiJson.has(item, "page_no") ? AiJson.intValue(item, "page_no", 0) : null);
            vo.setSnippet(AiJson.nullableText(item, "snippet"));
            vo.setScore(AiJson.has(item, "score") ? AiJson.doubleValue(item, "score", 0.0) : null);
            Attachment att = vo.getDocId() == null ? null : byDocId.get(vo.getDocId());
            // 映射不到（文档已删 / 不在本次活动范围）时保持 null：前端会渲染成
            // 不可点击的「文件名 第 N 页（附件已删除）」，而不是把映射责任推给前端
            vo.setAttachmentId(att == null ? null : att.getId());
            out.add(vo);
        }
        return out;
    }

    /**
     * P0 恒为空数组：受控查询工具（预算/付款/统计）属 P2。
     *
     * <p>保留空实现而不是删掉该字段：前端契约现在就定型，P2 只需在这里填值。
     */
    private List<AiSystemDataVO> systemData() {
        return List.of();
    }

    /** 工具轨迹：{@code elapsed}（秒）→ {@code elapsedMs}（毫秒），{@code brief} → {@code summary}。 */
    private List<AiToolTraceVO> toolTrace(Map<String, Object> data) {
        List<AiToolTraceVO> out = new ArrayList<>();
        for (Map<String, Object> item : AiJson.asList(data.get("trace"))) {
            AiToolTraceVO vo = new AiToolTraceVO();
            vo.setName(AiJson.text(item, "name"));
            vo.setSummary(AiJson.nullableText(item, "brief"));
            vo.setElapsedMs(Math.round(AiJson.doubleValue(item, "elapsed", 0.0) * 1000));
            vo.setHitCount(hitCount(vo.getSummary()));
            out.add(vo);
        }
        return out;
    }

    /**
     * 命中条数：AI 服务只在 brief 文案里带了这个数字，没有独立字段。
     *
     * <p>解析失败就返回 null（前端把 null 当"未知"处理），不要用 0 冒充——
     * "命中 0 条"与"不知道命中几条"在排障时含义不同。
     */
    private Integer hitCount(String brief) {
        if (brief == null || brief.isBlank()) {
            return null;
        }
        Matcher m = HIT_COUNT.matcher(brief);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * 是否降级。
     *
     * <p>两个来源：① trace/brief 里的降级文案（向量不可用改关键词召回）；
     * ② 模型侧出错（{@code stopped_reason=error}，answer 里是"问答失败"）。
     * 第二种也标 degraded，因为用户必须知道"这次不是没有答案，而是没答成"。
     */
    private boolean degraded(Map<String, Object> data) {
        String stopped = AiJson.text(data, "stopped_reason");
        if ("error".equals(stopped)) {
            return true;
        }
        String note = retrievalNote(data);
        return note != null && (note.contains("降级") || note.contains("关键词"));
    }

    /**
     * 降级/异常说明。
     *
     * <p>AI 服务的 {@code note} 在检索工具返回值里，经过 ToolAgent 只留了 brief 文案，
     * 所以这里从 trace 的 brief 与 {@code error}/{@code stopped_reason} 综合判断。
     * 没有可说的就返回 null（前端不显示提示条），不要造一句"一切正常"。
     */
    private String notice(Map<String, Object> data) {
        String stopped = AiJson.text(data, "stopped_reason");
        String error = AiJson.text(data, "error");
        String answer = AiJson.text(data, "answer");
        if ("error".equals(stopped)) {
            return error.isBlank() ? "AI 服务报告本次问答失败（未给出原因）" : "问答失败：" + error;
        }
        String note = retrievalNote(data);
        if (note != null) {
            return note;
        }
        if (answer.contains(NO_DOC_ANSWER_HINT)) {
            return "当前作用域内没有可问答的文档";
        }
        if ("max_rounds".equals(stopped)) {
            return "已达到工具调用轮数上限，答案可能不完整";
        }
        return null;
    }

    /** 在 trace 的 brief 里找检索降级说明；找不到返回 null。 */
    private String retrievalNote(Map<String, Object> data) {
        for (Map<String, Object> item : AiJson.asList(data.get("trace"))) {
            String brief = AiJson.text(item, "brief");
            int start = brief.indexOf('（');
            int end = brief.indexOf('）');
            if (start >= 0 && end > start) {
                String inside = brief.substring(start + 1, end);
                if (inside.contains("降级") || inside.contains("关键词") || inside.contains("不可用")) {
                    return inside;
                }
            }
        }
        return null;
    }

    private Map<String, Attachment> byDocId(Map<Long, Attachment> attachmentsById) {
        Map<String, Attachment> out = new LinkedHashMap<>();
        if (attachmentsById != null) {
            for (Attachment a : attachmentsById.values()) {
                if (a != null && a.getAiDocId() != null && !a.getAiDocId().isBlank()) {
                    out.putIfAbsent(a.getAiDocId(), a);
                }
            }
        }
        return out;
    }
}
