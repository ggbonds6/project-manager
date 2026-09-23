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
        // 本次没探的模型给 null（前端显示"未探测"），不能写成 false 让人以为模型坏了
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

    // ── 深度自检（deep=true） ────────────────────────────────────────

    @Test
    void 深度自检探出对话向量化重排而快速自检三者仍为null() {
        AiProperties props = new AiProperties();
        when(ai.listDocuments()).thenReturn(List.of());
        // 深探：with_llm=with_vec=true，AI 服务给出 llm / vec 两块结论
        when(ai.health(true, true, true)).thenReturn(Map.of(
                "code", 0,
                "config", Map.of("vec_backend", "local"),
                "ocr", Map.of("ok", true, "detail", ""),
                "llm", Map.of("ok", true, "detail", ""),
                "vec", Map.of(
                        "ok", true,
                        "detail", "网关可达，两个模型都在",
                        "models", List.of("bge-m3", "bge-reranker"),
                        "embed_model", "bge-m3",
                        "rerank_model", "bge-reranker")));
        // 快速探活：deep=false 时仍然只探 OCR
        when(ai.health(true, false, false)).thenReturn(Map.of(
                "code", 0,
                "config", Map.of("vec_backend", "local"),
                "ocr", Map.of("ok", true, "detail", "")));

        AiHealthVO deep = service(props).health(true);

        assertEquals(true, deep.getModels().getChat(), "chat ← llm.ok");
        assertEquals(true, deep.getModels().getEmbedding(), "embedding ← vec.ok（按模型名匹配）");
        assertEquals(true, deep.getModels().getReranker(), "reranker ← vec.ok（按模型名匹配）");
        assertTrue(deep.isAvailable());

        // 两条断言都要有：默认（快速）自检不探模型，三者必须是 null（前端显示"未探测"），
        // 不能因为"没探"就写成 false
        AiHealthVO quick = service(props).health(false);
        assertNull(quick.getModels().getChat());
        assertNull(quick.getModels().getEmbedding());
        assertNull(quick.getModels().getReranker());
        assertEquals(true, quick.getModels().getOcr());
    }

    @Test
    void 深度自检时网关模型列表里没有重排模型则重排报不可用而向量化仍可用() {
        AiProperties props = new AiProperties();
        when(ai.listDocuments()).thenReturn(List.of());
        // 平台网关可达（vec.ok=true），但模型列表里只有向量化模型——
        // AI 服务只把这件事写在 detail 里，主系统必须自己按模型名分别判定
        when(ai.health(true, true, true)).thenReturn(Map.of(
                "ocr", Map.of("ok", true),
                "llm", Map.of("ok", true),
                "vec", Map.of(
                        "ok", true,
                        "detail", "网关可达，但模型列表里没有 [bge-reranker]",
                        "models", List.of("bge-m3"),
                        "embed_model", "bge-m3",
                        "rerank_model", "bge-reranker")));

        AiHealthVO vo = service(props).health(true);

        assertEquals(true, vo.getModels().getEmbedding());
        assertEquals(false, vo.getModels().getReranker(),
                "模型不在网关列表里就不能报可用（vec.ok 这时仍是 true）");
    }

    @Test
    void 深度自检时AI服务没返回llmvec块则保持未探测而不是不可用() {
        AiProperties props = new AiProperties();
        when(ai.listDocuments()).thenReturn(List.of());
        // 老版本 AI 服务不认识 with_llm/with_vec：只回 ocr 块，不回空结论
        when(ai.health(true, true, true)).thenReturn(Map.of(
                "code", 0,
                "config", Map.of("vec_backend", "local"),
                "ocr", Map.of("ok", true)));

        AiHealthVO vo = service(props).health(true);

        assertNull(vo.getModels().getChat(), "没探到就保持 null（未探测），不写 false");
        assertNull(vo.getModels().getEmbedding());
        assertNull(vo.getModels().getReranker());
        assertTrue(vo.isAvailable());
    }
}
