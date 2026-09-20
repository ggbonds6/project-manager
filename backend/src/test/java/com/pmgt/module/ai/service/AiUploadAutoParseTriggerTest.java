package com.pmgt.module.ai.service;

import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.event.AttachmentIndexRequestedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AiUploadAutoParseTrigger} 的自动解析触发测试（方案 §3.3）。
 *
 * <p>执行器用<b>同步执行</b>的实现（{@code Runnable::run}），
 * 这样断言不必等线程、也不会 flaky；真实环境里是单线程池，见 {@code AiAsyncConfig}。
 *
 * <p>四条覆盖对应用户要求的四种情形：开关开且可触发、AI 不可用（**最关键**）、
 * 开关关、以及触发期异常绝不冒泡。
 */
class AiUploadAutoParseTriggerTest {

    private AiProperties props;
    private AiTaskService taskService;
    private ExecutorService executor;
    private AiUploadAutoParseTrigger trigger;

    /** 同步执行器：让"派发出去"在调用线程里立即执行，断言不受线程时序影响。 */
    private static final Executor SYNC = Runnable::run;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        props.setEnabled(true);
        props.setAutoParse(true);
        taskService = mock(AiTaskService.class);
        executor = Executors.newSingleThreadExecutor();
        trigger = new AiUploadAutoParseTrigger(props, taskService, SYNC);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 开关开启时按附件id触发解析并带上触发人() {
        trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, 9L));

        verify(taskService).autoParse(77L, 9L);
    }

    @Test
    void AI不可用时触发失败被吞掉不向上冒泡() {
        // 这是最关键的一条：附件已经上传成功，AI 的毛病不能变成用户的问题
        doThrow(new com.pmgt.module.ai.client.AiUnavailableException("连接失败（http://127.0.0.1:8100）：Connection refused"))
                .when(taskService).autoParse(anyLong(), any());

        assertDoesNotThrow(() -> trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, 9L)));

        verify(taskService).autoParse(77L, 9L);
    }

    @Test
    void 触发期任意异常都被吞掉只记日志() {
        doThrow(new IllegalStateException("读取附件内容失败")).when(taskService).autoParse(anyLong(), any());

        assertDoesNotThrow(() -> trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, null)));
    }

    @Test
    void 总开关关闭时不触发() {
        props.setEnabled(false);

        trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, 9L));

        assertFalse(trigger.autoParseEnabled());
        verify(taskService, never()).autoParse(anyLong(), any());
    }

    @Test
    void 自动解析开关关闭时不触发() {
        props.setAutoParse(false);

        trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, 9L));

        assertFalse(trigger.autoParseEnabled());
        verify(taskService, never()).autoParse(anyLong(), any());
    }

    @Test
    void 执行器拒绝时不影响调用方() {
        ExecutorService rejecting = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejecting).execute(any(Runnable.class));
        AiUploadAutoParseTrigger busy = new AiUploadAutoParseTrigger(props, taskService, rejecting);

        assertDoesNotThrow(() -> busy.onAttachmentUploaded(new AttachmentIndexRequestedEvent(77L, 9L)));
        verify(taskService, never()).autoParse(anyLong(), any());
    }

    @Test
    void attachmentId为空时安全跳过() {
        assertDoesNotThrow(() -> trigger.onAttachmentUploaded(new AttachmentIndexRequestedEvent(null, 9L)));
        verify(taskService, never()).autoParse(anyLong(), any());
    }

    @Test
    void 触发人会被显式传给服务层用于留痕() {
        // 同步执行器，保证断言不受线程时序影响
        AiUploadAutoParseTrigger sync = new AiUploadAutoParseTrigger(props, taskService, SYNC);

        sync.onAttachmentUploaded(new AttachmentIndexRequestedEvent(88L, 42L));

        // 后台线程没有 AuthContext，上传者必须随事件带过去，否则留痕里操作人为空
        verify(taskService).autoParse(eq(88L), eq(42L));
    }
}
