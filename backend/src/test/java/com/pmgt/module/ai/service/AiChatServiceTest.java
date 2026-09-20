package com.pmgt.module.ai.service;

import com.pmgt.common.security.AuthContext;
import com.pmgt.common.security.Role;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiChatRequest;
import com.pmgt.module.ai.dto.AiChatResponse;
import com.pmgt.module.ai.entity.AiAskLog;
import com.pmgt.module.ai.mapper.AiAskLogMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatService} 的主链路测试：作用域 → 代理 → 适配 → 留痕。
 *
 * <p>AI 服务用 Mockito 打桩，覆盖四条不能出错的路径：
 * 正常问答（含留痕落库）、AI 关闭、作用域内无可检索文档、AI 服务不可用（不降级）。
 */
class AiChatServiceTest {

    private AiServiceClient ai;
    private AiScopeResolver scopeResolver;
    private AiAskLogMapper askLogMapper;
    private OperationLogService operationLogService;
    private AiChatService chatService;

    @BeforeEach
    void setUp() {
        // 附件索引回写会用到 MyBatis-Plus 的 lambda 元信息缓存，纯单测需要显式初始化
        TestTableInfoInitializer.init();
        AiProperties props = new AiProperties();
        props.setEnabled(true);
        ai = mock(AiServiceClient.class);
        scopeResolver = mock(AiScopeResolver.class);
        askLogMapper = mock(AiAskLogMapper.class);
        operationLogService = mock(OperationLogService.class);
        chatService = new AiChatService(props, ai, new AiAnswerAdapter(), scopeResolver,
                askLogMapper, mock(AttachmentMapper.class), operationLogService);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private static Attachment readyAttachment() {
        Attachment a = new Attachment();
        a.setId(11L);
        a.setFileName("中标通知书.pdf");
        a.setAiIndexStatus(AiScopeResolver.STATUS_READY);
        a.setAiDocId("docA");
        return a;
    }

    private static Map<String, Object> aiAnswer() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", "中标金额为 7,182,700.00 元 [1]");
        data.put("citations", List.of(Map.of("index", 1, "doc_id", "docA",
                "filename", "中标通知书.pdf", "page_no", 1, "snippet", "人民币 7,182,700.00 元", "score", 0.93)));
        data.put("trace", List.of(Map.of("name", "search_documents", "brief", "命中 1 段：中标通知书.pdfP1",
                "elapsed", 0.31)));
        data.put("stopped_reason", "done");
        data.put("error", "");
        return data;
    }

    @Test
    void 正常问答把作用域换算结果传给AI并写留痕() {
        AuthContext.set(new AuthContext.Current(9L, "zhangsan", "张三", Role.MANAGER));
        when(scopeResolver.resolveForChat(null, List.of(11L)))
                .thenReturn(new AiScopeResolver.ChatScope(null, null, List.of(11L), List.of(11L), List.of("docA")));
        when(scopeResolver.selectByIds(any())).thenReturn(List.of(readyAttachment()));
        when(ai.chat(eq("中标金额是多少？"), eq(List.of("docA")), eq(null), eq(5))).thenReturn(aiAnswer());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("中标金额是多少？");
        request.setAttachmentIds(List.of(11L));
        request.setTopK(5);

        AiChatResponse response = chatService.ask(request);

        // 契约字段
        assertEquals("中标金额为 7,182,700.00 元 [1]", response.getAnswer());
        assertEquals(1, response.getCitations().size());
        // 关键：attachmentId 由主系统回填
        assertEquals(11L, response.getCitations().get(0).getAttachmentId());
        assertFalse(response.isDegraded());
        assertTrue(response.getSystemData().isEmpty());
        assertTrue(response.getElapsedMs() >= 0);
        assertTrue(response.getConversationId() != null && !response.getConversationId().isBlank());

        // 留痕：ai_ask_log 必须落库（审计硬要求），并带上实际作用域
        ArgumentCaptor<AiAskLog> captor = ArgumentCaptor.forClass(AiAskLog.class);
        verify(askLogMapper).insert(captor.capture());
        AiAskLog log = captor.getValue();
        assertEquals(9L, log.getUserId());
        assertEquals("张三", log.getUserName());
        assertEquals("中标金额是多少？", log.getQuestion());
        assertEquals("11", log.getAttachmentIds());
        assertEquals("docA", log.getDocIds());
        assertEquals(1, log.getCitedCount());
        assertEquals(0, log.getDegraded());
        // 沿用既有 operate_log 留痕
        verify(operationLogService).log(eq("PROJECT"), eq(null), eq("AI_ASK"), any());
    }

    @Test
    void AI开关关闭时直接报明确错误不调用AI服务() {
        AiProperties off = new AiProperties();
        off.setEnabled(false);
        AiChatService disabled = new AiChatService(off, ai, new AiAnswerAdapter(), scopeResolver,
                askLogMapper, mock(AttachmentMapper.class), operationLogService);

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("问题");

        AiUnavailableException e = assertThrows(AiUnavailableException.class, () -> disabled.ask(request));

        assertTrue(e.getMessage().contains("关闭"), e.getMessage());
        verify(ai, never()).chat(any(), anyList(), any(), any());
    }

    @Test
    void 作用域内没有可检索文档时给明确答复且不调用AI() {
        // 请求带 projectId=1，打桩必须匹配同一组参数（否则 Mockito 返回 null）
        when(scopeResolver.resolveForChat(1L, null))
                .thenReturn(new AiScopeResolver.ChatScope(1L, "项目一", List.of(), List.of(), List.of()));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("项目预算多少？");
        request.setProjectId(1L);

        AiChatResponse response = chatService.ask(request);

        // 不是错误，而是"合法的没东西可查"；但绝不能把空范围当成"检索全部"传给 AI
        assertTrue(response.getAnswer().contains("还没有可用于问答的文档"), response.getAnswer());
        assertTrue(response.getAnswer().contains("项目一"));
        assertTrue(response.getCitations().isEmpty());
        verify(ai, never()).chat(any(), anyList(), any(), any());
        verify(askLogMapper).insert(any(AiAskLog.class));
    }

    @Test
    void AI服务不可用时抛503且不降级成未找到() {
        when(scopeResolver.resolveForChat(null, List.of(11L)))
                .thenReturn(new AiScopeResolver.ChatScope(null, null, List.of(11L), List.of(11L), List.of("docA")));
        when(scopeResolver.selectByIds(any())).thenReturn(List.of(readyAttachment()));
        when(ai.chat(any(), anyList(), any(), any()))
                .thenThrow(new AiUnavailableException("连接失败（http://127.0.0.1:8100）：Connection refused"));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("中标金额是多少？");
        request.setAttachmentIds(List.of(11L));

        AiUnavailableException e = assertThrows(AiUnavailableException.class, () -> chatService.ask(request));

        assertEquals(503, e.getCode());
        assertTrue(e.getMessage().contains("AI 服务不可用"));
        assertFalse(e.getMessage().contains("未找到"), "绝不能把不可用说成没找到");
        // 失败也要留痕：审计上"问过但失败了"同样是事实
        verify(askLogMapper).insert(any(AiAskLog.class));
    }

    @Test
    void 作用域被拒绝时不调用AI且不留痕() {
        when(scopeResolver.resolveForChat(null, List.of(99L)))
                .thenThrow(new AiScopeDeniedException("无权访问或不可问答的作用域：附件 99 不存在或已删除"));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("问题");
        request.setAttachmentIds(List.of(99L));

        AiScopeDeniedException e = assertThrows(AiScopeDeniedException.class, () -> chatService.ask(request));

        assertEquals(403, e.getCode());
        verify(ai, never()).chat(any(), anyList(), any(), any());
        verify(askLogMapper, never()).insert(any(AiAskLog.class));
    }

    @Test
    void 空问题被拒绝() {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("   ");

        assertThrows(com.pmgt.common.exception.BizException.class, () -> chatService.ask(request));
        verify(askLogMapper, times(0)).insert(any(AiAskLog.class));
    }
}
