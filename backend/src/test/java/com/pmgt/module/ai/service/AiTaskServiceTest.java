package com.pmgt.module.ai.service;

import com.pmgt.common.exception.BizException;
import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiPageVO;
import com.pmgt.module.ai.dto.AiTaskVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiTaskService} 的解析触发与状态对账测试。
 *
 * <p>重点：AI 服务的任务枚举（QUEUED/PARSING/DONE/FAILED/CANCELLED）
 * 必须被翻译成 §9 的枚举；提交失败必须落到任务状态上（不能停在 QUEUED 转圈）。
 */
class AiTaskServiceTest {

    private AiProperties props;
    private AiServiceClient ai;
    private AttachmentAiTaskMapper taskMapper;
    private AttachmentMapper attachmentMapper;
    private AttachmentStorage storage;
    private AiScopeResolver scopeResolver;
    private AiTaskService taskService;

    @BeforeEach
    void setUp() {
        // 代码里用了 LambdaUpdateWrapper（显式把 doc_id 置 NULL），
        // 需要 MyBatis-Plus 的实体元信息缓存；纯单测没有 Spring 启动过程，这里补上
        TestTableInfoInitializer.init();
        props = new AiProperties();
        props.setEnabled(true);
        ai = mock(AiServiceClient.class);
        taskMapper = mock(AttachmentAiTaskMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        storage = mock(AttachmentStorage.class);
        scopeResolver = mock(AiScopeResolver.class);
        taskService = new AiTaskService(props, ai, taskMapper, attachmentMapper, storage,
                scopeResolver, mock(OperationLogService.class));
    }

    private static Attachment attachment() {
        Attachment a = new Attachment();
        a.setId(11L);
        a.setFileName("中标通知书.pdf");
        a.setFilePath("2026/09/abc.pdf");
        a.setAiIndexStatus(AiScopeResolver.STATUS_NOT_PARSED);
        return a;
    }

    @Test
    void AI任务状态翻译成契约枚举() {
        assertEquals("RUNNING", AiTaskService.translateStatus("PARSING"));
        assertEquals("RUNNING", AiTaskService.translateStatus("RUNNING"));
        assertEquals("DONE", AiTaskService.translateStatus("DONE"));
        assertEquals("FAILED", AiTaskService.translateStatus("FAILED"));
        // 前端 P0 只有四个标签，"取消"归入失败
        assertEquals("FAILED", AiTaskService.translateStatus("CANCELLED"));
        // 未知值按"排队中"处理，避免前端拿到不认识的状态导致标签空白
        assertEquals("QUEUED", AiTaskService.translateStatus("WHATEVER"));
        assertEquals("QUEUED", AiTaskService.translateStatus(null));
    }

    @Test
    void 自动触发时已有进行中任务则静默跳过而不是报错() {
        // 自动触发是后台行为：没有用户在等这个异常，抛 400 只会变成日志噪音
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        AttachmentAiTask running = new AttachmentAiTask();
        running.setId(5L);
        running.setAttachmentId(11L);
        running.setStatus(AttachmentAiTask.RUNNING);
        when(taskMapper.selectList(any())).thenReturn(List.of(running));

        AttachmentAiTask returned = taskService.autoParse(11L, 9L);

        assertEquals(5L, returned.getId(), "应返回既有任务而不是新建");
        verify(ai, never()).submitParse(any(), anyString(), any());
        verify(taskMapper, never()).insert(any(AttachmentAiTask.class));
    }

    @Test
    void 自动触发走通时新建任务并调用AI() throws Exception {
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        when(scopeResolver.projectIdOf(any())).thenReturn(1L);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("task_id", "ai-task-9");
        submitted.put("status", "PARSING");
        submitted.put("percent", 12.5);
        when(ai.submitParse(any(), anyString(), any())).thenReturn(submitted);

        AttachmentAiTask task = taskService.autoParse(11L, 9L);

        // AI 服务的 PARSING 要翻译成契约枚举 RUNNING
        assertEquals("RUNNING", task.getStatus());
        assertEquals("ai-task-9", task.getAiTaskId());
        assertEquals(13, task.getProgress(), "12.5% 取整后为 13");
        verify(taskMapper).insert(any(AttachmentAiTask.class));
    }

    @Test
    void 触发解析登记任务并把附件标记为解析中() throws Exception {        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        when(scopeResolver.projectIdOf(any())).thenReturn(1L);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(storage.open("2026/09/abc.pdf")).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("task_id", "ai-task-1");
        submitted.put("status", "QUEUED");
        submitted.put("percent", 0.0);
        when(ai.submitParse(eq("中标通知书.pdf"), anyString(), any())).thenReturn(submitted);

        AttachmentAiTask task = taskService.parse(11L);

        assertEquals("QUEUED", task.getStatus());
        assertEquals("ai-task-1", task.getAiTaskId());
        // 附件要立刻显示"解析中"，用户不用等下一轮对账
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).updateById(captor.capture());
        assertEquals("PARSING", captor.getValue().getAiIndexStatus());
        verify(taskMapper).insert(any(AttachmentAiTask.class));
    }

    @Test
    void 提交解析失败时任务落失败而不是停在排队() throws Exception {
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        when(scopeResolver.projectIdOf(any())).thenReturn(1L);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(ai.submitParse(any(), anyString(), any()))
                .thenThrow(new AiUnavailableException("连接失败（http://127.0.0.1:8100）：Connection refused"));

        assertThrows(AiUnavailableException.class, () -> taskService.parse(11L));

        ArgumentCaptor<AttachmentAiTask> captor = ArgumentCaptor.forClass(AttachmentAiTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMsg().contains("AI 服务不可用"), captor.getValue().getErrorMsg());
        // 附件也要落失败，否则界面永远停在"解析中"。
        // 注意这里被调用两次（先 PARSING 后 FAILED），取最后一次捕获的值
        ArgumentCaptor<Attachment> att = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper, org.mockito.Mockito.atLeastOnce()).updateById(att.capture());
        assertEquals("FAILED", att.getAllValues().get(att.getAllValues().size() - 1).getAiIndexStatus());
    }

    @Test
    void 已有进行中任务时不重复提交() {
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        AttachmentAiTask running = new AttachmentAiTask();
        running.setId(5L);
        running.setAttachmentId(11L);
        running.setStatus(AttachmentAiTask.RUNNING);
        when(taskMapper.selectList(any())).thenReturn(List.of(running));

        BizException e = assertThrows(BizException.class, () -> taskService.parse(11L));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("已有解析任务在进行中"), e.getMessage());
        verify(ai, never()).submitParse(any(), anyString(), any());
    }

    @Test
    void AI关闭时触发解析直接报错() {
        AiProperties off = new AiProperties();
        off.setEnabled(false);
        AiTaskService disabled = new AiTaskService(off, ai, taskMapper, attachmentMapper, storage,
                scopeResolver, mock(OperationLogService.class));

        BizException e = assertThrows(BizException.class, () -> disabled.parse(11L));

        assertEquals(503, e.getCode());
        verify(ai, never()).submitParse(any(), anyString(), any());
    }

    @Test
    void 任务列表对账后把完成状态与docId回写附件() {
        AttachmentAiTask pending = new AttachmentAiTask();
        pending.setId(5L);
        pending.setAttachmentId(11L);
        pending.setAiTaskId("ai-task-1");
        pending.setStatus(AttachmentAiTask.RUNNING);
        pending.setProgress(50);
        when(taskMapper.selectList(any())).thenReturn(List.of(pending));

        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("task_id", "ai-task-1");
        remote.put("status", "DONE");
        remote.put("percent", 100.0);
        remote.put("doc_id", "docZ");
        remote.put("error", "");
        when(ai.listTasks()).thenReturn(List.of(remote));

        AiPageVO<AiTaskVO> page = taskService.list(null, null, 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals("DONE", page.getRecords().get(0).getStatus());
        assertEquals("docZ", page.getRecords().get(0).getDocId());

        // 附件状态必须同步成"已可检索"并落 doc_id，否则前端不知道能不能问
        ArgumentCaptor<Attachment> att = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).updateById(att.capture());
        assertEquals("READY", att.getValue().getAiIndexStatus());
        assertEquals("docZ", att.getValue().getAiDocId());
    }

    @Test
    void 任务列表在AI不可用时用本地缓存状态作答() {
        AttachmentAiTask pending = new AttachmentAiTask();
        pending.setId(5L);
        pending.setAttachmentId(11L);
        pending.setAiTaskId("ai-task-1");
        pending.setStatus(AttachmentAiTask.RUNNING);
        when(taskMapper.selectList(any())).thenReturn(List.of(pending));
        when(ai.listTasks()).thenThrow(new AiUnavailableException("连接失败"));

        AiPageVO<AiTaskVO> page = taskService.list(null, null, 1, 20);

        // 读接口刻意不抛错：任务历史是主系统自己的记录，AI 挂了不该连历史都看不到
        assertEquals(1, page.getTotal());
        assertEquals("RUNNING", page.getRecords().get(0).getStatus());
    }

    @Test
    void 任务被AI侧清理时标记失败而不是永远解析中() {
        AttachmentAiTask pending = new AttachmentAiTask();
        pending.setId(5L);
        pending.setAttachmentId(11L);
        pending.setAiTaskId("gone");
        pending.setStatus(AttachmentAiTask.RUNNING);
        when(taskMapper.selectList(any())).thenReturn(List.of(pending));
        when(ai.listTasks()).thenReturn(List.of());

        AiPageVO<AiTaskVO> page = taskService.list(null, null, 1, 20);

        assertEquals("FAILED", page.getRecords().get(0).getStatus());
        assertTrue(page.getRecords().get(0).getError().contains("请重试"), page.getRecords().get(0).getError());
    }

    @Test
    void 附件状态只在解析中给进度() {
        Attachment ready = attachment();
        ready.setAiIndexStatus(AiScopeResolver.STATUS_READY);
        ready.setAiDocId("docA");
        when(scopeResolver.requireAccessibleAll(anyList())).thenReturn(List.of(ready));
        AttachmentAiTask done = new AttachmentAiTask();
        done.setId(5L);
        done.setAttachmentId(11L);
        done.setStatus(AttachmentAiTask.DONE);
        done.setProgress(100);
        when(scopeResolver.latestTaskByAttachment(anyList())).thenReturn(Map.of(11L, done));

        var statuses = taskService.attachmentStatus(List.of(11L));

        assertEquals(1, statuses.size());
        assertEquals("READY", statuses.get(0).getIndexStatus());
        assertEquals("docA", statuses.get(0).getDocId());
        // 已完成的任务不给进度，避免前端显示"100% 还在跑"
        assertEquals(null, statuses.get(0).getProgress());
    }
}
