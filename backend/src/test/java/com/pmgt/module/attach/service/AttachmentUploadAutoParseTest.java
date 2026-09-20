package com.pmgt.module.attach.service;

import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.event.AttachmentIndexRequestedEvent;
import com.pmgt.module.ai.service.AiTaskService;
import com.pmgt.module.ai.service.AiUploadAutoParseTrigger;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.entity.AttachmentUploadTask;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.attach.mapper.AttachmentUploadTaskMapper;
import com.pmgt.module.log.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传完成后自动触发 AI 解析的集成测试（方案 §3.3 + 本次要求的四条覆盖）。
 *
 * <p>这条链路横跨「attach 模块 → Spring 事件 → ai 模块」，单测里用一个
 * <b>真实的监听者</b>（{@link AiUploadAutoParseTrigger} + 同步执行器 + mock 的
 * {@link AiTaskService}）接在 mock 的事件发布器上，从而真的跑通
 * 「上传成功 → 发事件 → 触发解析」这整条线，而不是只断言"发了个事件"。
 *
 * <p>只 mock 数据访问层与 AI HTTP 客户端，<b>不起 Spring 上下文、不连库</b>。
 */
class AttachmentUploadAutoParseTest {

    private AttachmentStorage storage;
    private AttachmentMapper attachmentMapper;
    private AttachmentUploadTaskMapper taskMapper;
    private ApplicationEventPublisher publisher;
    private AiTaskService aiTaskService;
    private AiUploadAutoParseTrigger listener;
    private Path tmpFile;

    @BeforeEach
    void setUp() throws Exception {
        storage = mock(AttachmentStorage.class);
        attachmentMapper = mock(AttachmentMapper.class);
        taskMapper = mock(AttachmentUploadTaskMapper.class);
        publisher = mock(ApplicationEventPublisher.class);
        aiTaskService = mock(AiTaskService.class);

        // 真实监听者 + 同步执行的"执行器"，让事件分发在上传线程里同步跑完，断言稳定
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(true);
        listener = new AiUploadAutoParseTrigger(aiProps, aiTaskService, Runnable::run);
        // 发布事件时直接调用监听者：等价于 Spring 的事件分发，但不引入上下文
        doAnswer(inv -> {
            listener.onAttachmentUploaded(inv.getArgument(0));
            return null;
        }).when(publisher).publishEvent(any(AttachmentIndexRequestedEvent.class));

        // 临时文件：store() 会去读它（模拟对象存储推送的源文件）
        tmpFile = Files.createTempFile("upload-test", ".part");
        Files.write(tmpFile, "pdf-bytes".getBytes());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tmpFile != null) {
            Files.deleteIfExists(tmpFile);
        }
    }

    /** 构造上传服务（注入给定的 AI 配置与「事件发布器」）。 */
    private AttachmentUploadService service(AiProperties aiProps) {
        return new AttachmentUploadService(storage, attachmentMapper, taskMapper,
                mock(OperationLogService.class), publisher, aiProps, tmpFile.getParent().toString());
    }

    /** 上传任务行：推送阶段会先把它读出来。 */
    private AttachmentUploadTask givenTask() {
        AttachmentUploadTask task = new AttachmentUploadTask();
        task.setId(5L);
        task.setProjectId(1L);
        task.setBizType("PROJECT");
        task.setBizId(1L);
        task.setFileName("中标通知书.pdf");
        task.setFileSize(9L);
        task.setFileExt("pdf");
        task.setStoredName("abc.pdf");
        task.setFilePath("2026/09/abc.pdf");
        task.setTempPath(tmpFile.toString());
        task.setStatus(AttachmentUploadTask.PENDING);
        task.setProgress(0);
        task.setUploadUserId(9L);
        when(taskMapper.selectById(5L)).thenReturn(task);
        try {
            when(storage.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // MyBatis-Plus 会在 insert 后把自增主键写回实体，mock 里手动模拟
        doAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(123L);
            return 1;
        }).when(attachmentMapper).insert(any(Attachment.class));
        return task;
    }

    @Test
    void 开关开启且AI可用时上传成功后自动触发解析() {
        givenTask();
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(true);

        service(aiProps).store(5L);

        // 1) 上传本身成功（任务置成功 + 落了附件 id）
        assertTaskSucceeded();
        // 2) 真的触发了 AI 解析，并且带上上传者（后台线程没有 AuthContext）
        verify(aiTaskService).autoParse(123L, 9L);
    }

    /**
     * 【最关键的一条】AI 服务不可用 → 上传必须仍然成功。
     *
     * <p>这里连「AI 的异常会不会把上传标成失败」一起钉住：若发布点没有自己吞异常，
     * store() 的 catch 会把已经成功的上传改写成 FAILED——那正是要避免的事故。
     */
    @Test
    void AI服务不可用时上传仍然成功() {
        givenTask();
        doThrow(new AiUnavailableException("连接失败（http://127.0.0.1:8100）：Connection refused"))
                .when(aiTaskService).autoParse(anyLong(), any());
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(true);

        assertDoesNotThrow(() -> service(aiProps).store(5L));

        assertTaskSucceeded();
        verify(aiTaskService).autoParse(123L, 9L);
    }

    /** 同理：触发期抛出任何未预期异常（如读文件失败、NPE）也不能影响上传。 */
    @Test
    void 触发期未预期异常不影响上传结果() {
        givenTask();
        doThrow(new IllegalStateException("读取附件内容失败")).when(aiTaskService).autoParse(anyLong(), any());
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(true);

        assertDoesNotThrow(() -> service(aiProps).store(5L));

        assertTaskSucceeded();
    }

    @Test
    void 自动解析开关关闭时不触发且不写AI状态() {
        givenTask();
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(false);

        service(aiProps).store(5L);

        assertTaskSucceeded();
        verify(aiTaskService, never()).autoParse(anyLong(), any());
        // 也不该发布事件：关闭时行为与"没有这个功能"完全一致
        verify(publisher, never()).publishEvent(any(AttachmentIndexRequestedEvent.class));
    }

    @Test
    void AI总开关关闭时不触发() {
        givenTask();
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(false);
        aiProps.setAutoParse(true);

        service(aiProps).store(5L);

        assertTaskSucceeded();
        verify(aiTaskService, never()).autoParse(anyLong(), any());
        verify(publisher, never()).publishEvent(any(AttachmentIndexRequestedEvent.class));
    }

    /** 上传本身失败时不该触发解析（没有可读的附件）。 */
    @Test
    void 上传失败时不触发解析() throws Exception {
        AttachmentUploadTask task = givenTask();
        // 对象存储推送失败 → 不存在可读附件
        doThrow(new RuntimeException("OBS 写入超时")).when(storage)
                .save(anyString(), any(), anyLong(), any());
        AiProperties aiProps = new AiProperties();
        aiProps.setEnabled(true);
        aiProps.setAutoParse(true);

        service(aiProps).store(5L);

        assertNull(task.getAttachmentId());
        verify(aiTaskService, never()).autoParse(anyLong(), any());
    }

    /** 上传成功的关键断言：任务被置 SUCCESS 且带上新附件 id。 */
    private void assertTaskSucceeded() {
        ArgumentCaptor<AttachmentUploadTask> captor = ArgumentCaptor.forClass(AttachmentUploadTask.class);
        verify(taskMapper, atLeastOnce()).updateById(captor.capture());
        AttachmentUploadTask last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AttachmentUploadTask.SUCCESS, last.getStatus());
        assertEquals(123L, last.getAttachmentId());
        assertEquals(100, last.getProgress());
    }
}
