package com.pmgt.module.ai.service;

import com.pmgt.module.ai.dto.AiChatResponse;
import com.pmgt.module.ai.dto.AiCitationVO;
import com.pmgt.module.attach.entity.Attachment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiAnswerAdapter} 的契约映射测试（§9 #9）。
 *
 * <p>这是"契约字段映射"最集中的一段，必须逐字段钉死：
 * 一旦 AI 服务改了字段名，最先坏的就是这里，测试要能让它红。
 */
class AiAnswerAdapterTest {

    private final AiAnswerAdapter adapter = new AiAnswerAdapter();

    @Test
    void 完整响应按契约映射并回填attachmentId() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "中标金额为 7,182,700.00 元 [1]");
        data.put("citations", List.of(
                Map.of("index", 1, "doc_id", "abc123", "filename", "中标通知书.pdf",
                        "page_no", 1, "snippet", "人民币 7,182,700.00 元", "score", 0.93),
                Map.of("index", 2, "doc_id", "zzz999", "filename", "已删除的附件.pdf",
                        "page_no", 4, "snippet", "……", "score", 0.41)));
        data.put("trace", List.of(
                Map.of("round", 1, "name", "search_documents", "brief", "命中 2 段：中标通知书.pdfP1",
                        "elapsed", 0.31, "is_error", false),
                Map.of("round", 1, "name", "calculate", "brief", "7182700.00 = 7182700.00",
                        "elapsed", 0.05, "is_error", false)));
        data.put("stopped_reason", "done");
        data.put("error", "");

        Attachment att = new Attachment();
        att.setId(77L);
        att.setAiDocId("abc123");
        att.setFileName("中标通知书.pdf");

        AiChatResponse vo = adapter.adapt(data, Map.of(77L, att), "conv-1", 10270L);

        assertEquals("conv-1", vo.getConversationId());
        assertEquals("中标金额为 7,182,700.00 元 [1]", vo.getAnswer());
        assertEquals(10270L, vo.getElapsedMs());
        assertFalse(vo.isDegraded());
        assertNull(vo.getNotice());
        assertTrue(vo.getSystemData().isEmpty(), "P0 的 systemData 必须恒为空");

        assertEquals(2, vo.getCitations().size());
        AiCitationVO first = vo.getCitations().get(0);
        assertEquals(1, first.getIndex());
        assertEquals("abc123", first.getDocId());
        // 关键：主系统回填 attachmentId，前端才能跳到附件第 N 页
        assertEquals(77L, first.getAttachmentId());
        assertEquals("中标通知书.pdf", first.getFilename());
        assertEquals(1, first.getPageNo());
        assertEquals("人民币 7,182,700.00 元", first.getSnippet());
        assertEquals(0.93, first.getScore(), 1e-9);

        // 映射不到 doc_id 的引用：attachmentId 给 null（不编造），其余字段仍保留
        AiCitationVO second = vo.getCitations().get(1);
        assertNull(second.getAttachmentId());
        assertEquals(4, second.getPageNo());

        assertEquals(2, vo.getToolTrace().size());
        assertEquals("search_documents", vo.getToolTrace().get(0).getName());
        assertEquals(310L, vo.getToolTrace().get(0).getElapsedMs(), "elapsed 秒 → 毫秒");
        assertEquals(2, vo.getToolTrace().get(0).getHitCount(), "从 brief 里取命中条数");
        assertNull(vo.getToolTrace().get(1).getHitCount(), "非检索工具没有命中数，给 null 而不是 0");
    }

    @Test
    void citations字段缺失时给空数组不伪造引用() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "文档中未找到相关内容。");
        // AI 服务尚未发版：没有 citations 字段
        data.put("trace", List.of());
        data.put("stopped_reason", "done");

        AiChatResponse vo = adapter.adapt(data, Map.of(), null, 1200L);

        assertTrue(vo.getCitations().isEmpty(), "缺字段时必须空数组，不许从 trace 抠页码");
        assertEquals("文档中未找到相关内容。", vo.getAnswer());
    }

    @Test
    void 检索降级时标记degraded并给出notice() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "根据关键词检索，……");
        data.put("trace", List.of(Map.of("name", "search_documents",
                "brief", "命中 3 段：a.pdfP1（向量服务不可用，本次仅关键词召回）", "elapsed", 0.2)));
        data.put("stopped_reason", "done");

        AiChatResponse vo = adapter.adapt(data, Map.of(), null, 500L);

        assertTrue(vo.isDegraded(), "向量降级必须透给用户，否则会被误判成『文档里没有』");
        assertTrue(vo.getNotice().contains("关键词"), "实际 notice：" + vo.getNotice());
    }

    @Test
    void 模型报错时标记degraded并带出原因() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "> ⚠️ **问答失败**：`LlmTimeoutException: read timeout`");
        data.put("trace", List.of());
        data.put("stopped_reason", "error");
        data.put("error", "LlmTimeoutException: read timeout");

        AiChatResponse vo = adapter.adapt(data, Map.of(), null, 60000L);

        assertTrue(vo.isDegraded());
        assertTrue(vo.getNotice().contains("read timeout"));
    }

    @Test
    void index缺失时按出现顺序兜底编号() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "答案 [1][2]");
        data.put("citations", List.of(
                Map.of("doc_id", "a", "filename", "a.pdf", "page_no", 1),
                Map.of("doc_id", "b", "filename", "b.pdf", "page_no", 2)));
        data.put("stopped_reason", "done");

        AiChatResponse vo = adapter.adapt(data, Map.of(), null, 100L);

        assertEquals(1, vo.getCitations().get(0).getIndex());
        assertEquals(2, vo.getCitations().get(1).getIndex());
        assertNull(vo.getCitations().get(0).getScore(), "缺 score 给 null，不编造 0");
    }
}
