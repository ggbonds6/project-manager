package com.pmgt.ai.module.system.controller;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.llm.LlmClient;
import com.pmgt.ai.module.ocr.OcrHealth;
import com.pmgt.ai.module.ocr.PlatformOcrClient;
import com.pmgt.ai.module.retrieval.VecClient;
import com.pmgt.ai.module.retrieval.VecHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务自检。响应形状与 Python 版一致（主系统/前端按老契约读它，换语言不该让调用方改代码）。
 *
 * <p>三个探测项分开开关，是因为代价不同：平台 OCR/向量探活很快（且有缓存），
 * 大模型探活要真发一次请求（失败要等十几秒）。默认只探 OCR。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AiSettings settings;
    private final PlatformOcrClient platformOcrClient;
    private final LlmClient llmClient;
    private final VecClient vecClient;

    @GetMapping("/health")
    public Map<String, Object> health(
            @RequestParam(name = "with_llm", defaultValue = "false") boolean withLlm,
            @RequestParam(name = "with_ocr", defaultValue = "true") boolean withOcr,
            @RequestParam(name = "with_vec", defaultValue = "false") boolean withVec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("service", "pm-ai-service");
        body.put("version", "1.0.0-SNAPSHOT");
        body.put("config", configSummary());

        if (withOcr) {
            OcrHealth health = platformOcrClient.health(false);
            Map<String, Object> ocr = new LinkedHashMap<>();
            ocr.put("ok", health.ok());
            ocr.put("workers", health.workers());
            ocr.put("idle", health.idle());
            ocr.put("options", health.options());
            ocr.put("detail", health.detail());
            body.put("ocr", ocr);
        }

        // 引擎只剩平台一条路（本地 OCR 兜底已移除），这里恒为 "platform"。
        // 字段保留是为了不让读取它的主系统/前端拿到 KeyError；真实连通性看上面的 ocr.ok。
        body.put("provider", "platform");

        if (withLlm) {
            LlmClient.LlmPing ping = llmClient.ping();
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("ok", ping.ok());
            llm.put("detail", ping.detail());
            body.put("llm", llm);
        }

        if (withVec) {
            VecHealth vec = vecClient.health(5.0);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("ok", vec.ok());
            info.put("detail", vec.detail());
            info.put("models", vec.models());
            info.put("backend", settings.getRetrieval().getBackend());
            info.put("embed_model", settings.getVec().getEmbedModel());
            info.put("rerank_model", settings.getVec().getRerankModel());
            info.put("dimensions", settings.getVec().getDimensions());
            body.put("vec", info);
        }
        return body;
    }

    private Map<String, Object> configSummary() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("gateway_base_url", settings.getGateway().getBaseUrl());
        config.put("gateway_api_key", mask(settings.getGateway().getApiKey()));
        config.put("llm_model", settings.getLlm().getModel());
        config.put("llm_max_input_chars", settings.getLlm().getMaxInputChars());
        config.put("llm_max_tokens", settings.getLlm().getMaxTokens());
        config.put("llm_enable_thinking", settings.getLlm().isEnableThinking());
        config.put("ocr_model", settings.getOcr().getModel());
        config.put("ocr_timeout", settings.getOcr().getTimeoutSeconds());
        config.put("ocr_concurrency", settings.getOcr().getConcurrency());
        config.put("ocr_batch_pages", settings.getOcr().getBatchPages());
        config.put("ocr_seal", settings.getOcr().isSeal());
        config.put("ocr_platform_image", settings.getOcr().getPlatformDpi() + "dpi/"
                + settings.getOcr().getPlatformFormat() + "/q" + settings.getOcr().getPlatformQuality());
        config.put("scanned_char_threshold", settings.getOcr().getScannedCharThreshold());
        config.put("vec_embed_model", settings.getVec().getEmbedModel());
        config.put("vec_rerank_model", settings.getVec().getRerankModel());
        config.put("vec_embed_dimensions", settings.getVec().getDimensions());
        config.put("retrieval_recall", settings.getRetrieval().getRecall());
        config.put("retrieval_top_k", settings.getRetrieval().getTopK());
        config.put("vec_backend", settings.getRetrieval().getBackend());
        config.put("work_dir", settings.getWorkDir().toAbsolutePath().toString());
        return config;
    }

    /** 密钥只报"配没配"，绝不回显明文。 */
    private String mask(String secret) {
        return secret == null || secret.isBlank() ? "未配置" : "已配置";
    }
}
