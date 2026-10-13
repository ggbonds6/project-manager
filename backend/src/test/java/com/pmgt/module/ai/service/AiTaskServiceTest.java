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
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.pipeline.service.AiPipelineClaimService;
import com.pmgt.module.ai.pipeline.service.AiPipelineEventWriter;
import com.pmgt.module.ai.pipeline.service.AiPipelineRunLookup;
import com.pmgt.module.ai.pipeline.service.AiPipelineTokenService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private AiPipelineEventMapper eventMapper;
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
        eventMapper = mock(AiPipelineEventMapper.class);
        // 真实的 insert 会回填自增主键；领取决议需要 id（run_id 由 task_id 派生），
        // 所以桩也必须把 id 填上，否则测出来的是"没有 id 的任务"这条不存在的路径
        when(taskMapper.insert(any(AttachmentAiTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, AttachmentAiTask.class).setId(100L);
            return 1;
        });
        // 用**真实**的领取服务（只把 Mapper 换成桩）：这样"提交后登记领取"这条新链路
        // 在既有用例里也真的被执行，而不是被一个 mock 悄悄跳过
        AiPipelineClaimService pipeline = new AiPipelineClaimService(
                taskMapper,
                new AiPipelineEventWriter(eventMapper),
                new AiPipelineProperties(),
                new AiPipelineTokenService("unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz"),
                new AiPipelineRunLookup(taskMapper),
                new AiAttachmentIndexWriter(attachmentMapper));
        taskService = new AiTaskService(props, ai, taskMapper, storage,
                scopeResolver, mock(OperationLogService.class),
                new AiAttachmentIndexWriter(attachmentMapper), pipeline);
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

        // 内存里的这个对象是「AI 提交响应」的快照：AI 说 QUEUED 就是 QUEUED（既有行为一字未改）。
        // 注意它不是数据库行的状态——提交成功后主系统会用条件 UPDATE 把这一行登记为 RUNNING（T2 领取），
        // 见下面的「提交解析时写入流水线列并登记领取」
        assertEquals("QUEUED", task.getStatus());
        assertEquals("ai-task-1", task.getAiTaskId());
        // 附件要立刻显示"解析中"，用户不用等下一轮对账
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).updateById(captor.capture());
        assertEquals("PARSING", captor.getValue().getAiIndexStatus());
        verify(taskMapper).insert(any(AttachmentAiTask.class));
    }

    @Test
    void 提交解析时写入流水线列并登记领取() throws Exception {
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        when(scopeResolver.projectIdOf(any())).thenReturn(1L);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("task_id", "ai-task-9");
        submitted.put("status", "PARSING");
        submitted.put("percent", 5.0);
        // 真实的 submitParse 会把 InputStream 读完（multipart 发出去）——桩也必须读，
        // 否则摘要算的是"空内容"，content_hash 就成了一条永远对不上的假指纹
        when(ai.submitParse(any(), anyString(), any())).thenAnswer(inv -> {
            java.io.InputStream in = inv.getArgument(2);
            in.readAllBytes();
            return submitted;
        });
        // 领取的条件 UPDATE 命中（受影响行数 = 1）
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        AttachmentAiTask task = taskService.parse(11L);

        // ① V15 的流水线列：内容指纹 + 版本串（幂等键 task_key 的两半）+ 分类 + 优先级
        assertEquals(AiPipelineKeys.sha1Hex("pdf".getBytes()), task.getContentHash(),
                "content_hash 必须是附件字节的 sha1（边推给 AI 边算，不重复读文件）");
        assertEquals("parse=1,clean=0,chunk=0", task.getPipelineRev());
        assertEquals(0, task.getCleanRev());
        assertEquals(AttachmentAiTask.TYPE_PARSE, task.getTaskType());
        assertEquals(10, task.getPriority(), "手动点击解析是交互式任务");
        // ② 领取登记：claimed_by/租约写进这一行（与 AI 侧 claim 互斥的落点）
        assertNotNull(task.getClaimedBy(), "提交后主系统必须登记领取，否则 AI 侧可能再领一次");
        assertNotNull(task.getClaimExpiresAt());
        // ③ 审计：T1（提交）+ T2（领取）两条事件
        ArgumentCaptor<AiPipelineEvent> events = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper, times(2)).insert(events.capture());
        assertEquals("SUBMIT:PARSE", events.getAllValues().get(0).getReason());
        assertTrue(events.getAllValues().get(1).getReason().startsWith("CLAIM:"),
                events.getAllValues().get(1).getReason());
        // ④ 领取走的是"条件 UPDATE + 受影响行数"，不是"先查再改"
        verify(taskMapper, atLeastOnce()).update(isNull(), any());
    }

    @Test
    void 任务列表把V15阶段字段只增给前端() {
        // 存量任务（stage_key/stage 为 null）仍要能列出来：前端显示"历史任务（无阶段明细）"
        AttachmentAiTask legacy = new AttachmentAiTask();
        legacy.setId(5L);
        legacy.setAttachmentId(11L);
        legacy.setStatus(AttachmentAiTask.RUNNING);
        legacy.setAiTaskId("ai-task-1");
        when(taskMapper.selectList(any())).thenReturn(List.of(legacy));
        when(ai.listTasks()).thenReturn(List.of());

        AiPageVO<AiTaskVO> page = taskService.list(null, null, 1, 20);

        AiTaskVO vo = page.getRecords().get(0);
        assertEquals(null, vo.getStageKey(), "存量行没有阶段明细，不能编一个");
        assertEquals(null, vo.getStage());
        assertEquals(null, vo.getAttempts());
    }

    @Test
    void 任务列表带出当前阶段与阶段进度() {
        AttachmentAiTask running = new AttachmentAiTask();
        running.setId(5L);
        running.setAttachmentId(11L);
        running.setStatus(AttachmentAiTask.RUNNING);
        running.setAiTaskId("ai-task-1");
        running.setTaskType(AttachmentAiTask.TYPE_PARSE);
        running.setStageKey("PARSE");
        running.setStage("识别");
        running.setStageDone(20);
        running.setStageTotal(0);
        running.setElapsedMs(8400L);
        running.setAttempts(1);
        when(taskMapper.selectList(any())).thenReturn(List.of(running));
        when(ai.listTasks()).thenReturn(List.of());

        AiTaskVO vo = taskService.list(null, null, 1, 20).getRecords().get(0);

        assertEquals("PARSE", vo.getStageKey());
        assertEquals("识别", vo.getStage());
        assertEquals(20, vo.getStageDone());
        assertEquals(0, vo.getStageTotal());
        assertEquals(8400L, vo.getElapsedMs());
        assertEquals(1, vo.getAttempts());
        assertEquals(AttachmentAiTask.TYPE_PARSE, vo.getTaskType());
    }

    @Test
    void 自动触发的任务是批量优先级() throws Exception {
        when(scopeResolver.requireAccessible(11L)).thenReturn(attachment());
        when(scopeResolver.projectIdOf(any())).thenReturn(1L);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(storage.open(anyString())).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        when(ai.submitParse(any(), anyString(), any())).thenReturn(Map.of("task_id", "ai-1", "status", "QUEUED"));

        AttachmentAiTask task = taskService.autoParse(11L, 9L);

        assertEquals(AttachmentAiTask.TYPE_PARSE, task.getTaskType());
        assertEquals(0, task.getPriority(), "上传后自动触发是批量（用户在等的是上传，不是解析）");
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
        AiTaskService disabled = new AiTaskService(off, ai, taskMapper, storage,
                scopeResolver, mock(OperationLogService.class),
                new AiAttachmentIndexWriter(attachmentMapper), mock(AiPipelineClaimService.class));

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
