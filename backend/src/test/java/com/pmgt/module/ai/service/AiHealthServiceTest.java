package com.pmgt.module.ai.service;

import com.pmgt.module.ai.client.AiServiceClient;
import com.pmgt.module.ai.client.AiUnavailableException;
import com.pmgt.module.ai.config.AiProperties;
import com.pmgt.module.ai.dto.AiHealthVO;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiHealthService} 的自检测试（§9 #1）。
 *
 * <p>核心约定：<b>自检接口永远给结论，不抛异常</b>。AI 服务连不上时也要正常返回，
 * 用 {@code available=false + message} 说明原因——否则运维拿到的只是一个通用错误，
 * 分不清"开关关了""服务没起来""平台网关不通"。
 */
class AiHealthServiceTest {

    private AiServiceClient ai;
    private AttachmentAiTaskMapper taskMapper;

    @BeforeEach
    void setUp() {
        ai = mock(AiServiceClient.class);
        taskMapper = mock(AttachmentAiTaskMapper.class);
        when(taskMapper.selectCount(any())).thenReturn(2L);
    }

    private AiHealthService service(AiProperties props) {
        return new AiHealthService(props, ai, taskMapper);
    }

    @Test
    void 服务正常时available为真并带出检索引擎() {
        AiProperties props = new AiProperties();
        when(ai.listDocuments()).thenReturn(List.of(Map.of("doc_id", "a"), Map.of("doc_id", "b")));
        when(ai.health(true, false, false)).thenReturn(Map.of(
                "code", 0,
                "config", Map.of("vec_backend", "local"),
                "ocr", Map.of("ok", true, "detail", "")));

        AiHealthVO vo = service(props).health();

        assertTrue(vo.isAvailable());
        assertEquals("local", vo.getVectorBackend());
        assertEquals(true, vo.getPlatformReachable());
        assertEquals(2, vo.getDocumentCount());
        assertEquals(2L, vo.getPendingTaskCount());
        assertEquals(props.getBaseUrl(), vo.getAiServiceBaseUrl());
        assertTrue(vo.getCheckedAt() != null && vo.getCheckedAt().length() >= 19);
        assertTrue(vo.getMessage().contains("正常"), vo.getMessage());
        // 本次没探的模型给 null（"未知"），不能写成 false 让人以为模型坏了
        assertNull(vo.getModels().getChat());
        assertEquals(true, vo.getModels().getOcr());
    }

    @Test
    void AI服务连不上时available为假且说明原因() {
        AiProperties props = new AiProperties();
        when(ai.health(true, false, false))
                .thenThrow(new AiUnavailableException("连接失败（http://127.0.0.1:8100）：Connection refused"));

        AiHealthVO vo = service(props).health();

        assertFalse(vo.isAvailable());
        assertTrue(vo.getMessage().contains("无法连接 AI 能力服务"), vo.getMessage());
        assertTrue(vo.getMessage().contains("Connection refused"), vo.getMessage());
        // 主系统侧的任务数不依赖 AI 服务，仍然要有值
        assertEquals(2L, vo.getPendingTaskCount());
    }

    @Test
    void 平台网关探针不通过时available为假() {
        AiProperties props = new AiProperties();
        when(ai.listDocuments()).thenReturn(List.of());
        when(ai.health(true, false, false)).thenReturn(Map.of(
                "code", 0,
                "config", Map.of("vec_backend", "local"),
                "ocr", Map.of("ok", false, "detail", "网关 401 missing api key")));

        AiHealthVO vo = service(props).health();

        assertFalse(vo.isAvailable(), "平台不可达时不能硬说可用（解析与问答都依赖它）");
        assertTrue(vo.getMessage().contains("401"), vo.getMessage());
        assertEquals(false, vo.getPlatformReachable());
    }

    @Test
    void 开关关闭时不请求AI只说明关闭原因() {
        AiProperties props = new AiProperties();
        props.setEnabled(false);

        AiHealthVO vo = service(props).health();

        assertFalse(vo.isAvailable());
        assertTrue(vo.getMessage().contains("pm.ai.enabled=false"), vo.getMessage());
        assertEquals(2L, vo.getPendingTaskCount());
        // 关闭后不该再去打扰 AI 服务
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.never()).health(
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void 文档数取不到时给null不影响可用性结论() {
        AiProperties props = new AiProperties();
        when(ai.health(true, false, false)).thenReturn(Map.of(
                "config", Map.of("vec_backend", "opensearch"),
                "ocr", Map.of("ok", true)));
        when(ai.listDocuments()).thenThrow(new AiUnavailableException("文档列表超时"));

        AiHealthVO vo = service(props).health();

        assertTrue(vo.isAvailable());
        assertNull(vo.getDocumentCount(), "拿不到文档数就给 null，不要编 0");
        assertEquals("opensearch", vo.getVectorBackend());
        // 顺带钉住：任务状态常量与健康探针用的枚举一致
        assertEquals("RUNNING", AttachmentAiTask.RUNNING);
    }
}
