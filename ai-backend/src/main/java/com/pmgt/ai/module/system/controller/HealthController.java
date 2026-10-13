package com.pmgt.ai.module.system.controller;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.gate.PipelineGate;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.module.admin.ReindexService;
import com.pmgt.ai.module.llm.LlmClient;
import com.pmgt.ai.module.ocr.OcrHealth;
import com.pmgt.ai.module.ocr.PlatformOcrClient;
import com.pmgt.ai.module.pipeline.PipelineReporter;
import com.pmgt.ai.module.retrieval.OpenSearchIndex;
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
 * <p>探测项分开开关，是因为代价不同：平台 OCR/向量探活很快（且有缓存），
 * 大模型探活要真发一次请求（现在受 <b>chat 每模型超时 150s</b> 约束，
 * {@code ping} 用它自己的 15s——见 {@code LlmClient.effectiveTimeoutMillis}）。
 *
 * <h2>{@code with_vec=true} 的口径（P0：向量后端状态必须真实）</h2>
 * <p>{@code VEC_BACKEND=opensearch} 时，{@code vec.ok} <b>取集群的真实探活结果</b>
 * （{@code GET /_cluster/health}），而不是"平台网关 /models 通不通"——集群挂了但网关好好的，
 * 旧写法会显示 ok，这正是"以为在用集群"的误导来源。此时另外给出
 * {@code upstream_ok}（网关侧向量模型是否可用）与 {@code open_search}（配置摘要，<b>不含口令</b>）。
 *
 * <h2>{@code with_metrics=true}（P0-6）</h2>
 * <p>返回四模型 × 5 项指标（QPS/延迟分位/错误率/在途/排队）+ 解析队列深度。
 * 标准读法是 {@code /actuator/metrics/ai.model.latency} 等（见 ai-backend/README.md），
 * 这里给一份"一眼看全"的聚合，便于运维与验收直接在自检页上看。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AiSettings settings;
    private final PlatformOcrClient platformOcrClient;
    private final LlmClient llmClient;
    private final VecClient vecClient;
    private final OpenSearchIndex openSearchIndex;
    private final ModelRegistry modelRegistry;
    private final ReindexService reindexService;
    private final PipelineGate pipelineGate;
    private final PipelineReporter pipelineReporter;

    @GetMapping("/health")
    public Map<String, Object> health(
            @RequestParam(name = "with_llm", defaultValue = "false") boolean withLlm,
            @RequestParam(name = "with_ocr", defaultValue = "true") boolean withOcr,
            @RequestParam(name = "with_vec", defaultValue = "false") boolean withVec,
            @RequestParam(name = "with_metrics", defaultValue = "false") boolean withMetrics) {
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
            body.put("vec", vecBlock());
        }

        if (withMetrics) {
            body.put("metrics", modelRegistry.snapshot());
            body.put("limits", modelRegistry.configSummary());
            Map<String, Object> queue = new LinkedHashMap<>();
            queue.put("reindex", reindexService.configSummary());
            // L1 业务闸门（设计 §5.5 的 10 项 ai.gate.* 聚合）+ 流水线上报状态（**不含令牌**）
            queue.put("gate", pipelineGate.snapshot());
            queue.put("pipeline", pipelineReporter.configSummary());
            body.put("queues", queue);
        }
        return body;
    }

    /**
     * {@code vec} 块：后端真实状态。
     *
     * <p>⚠️ 不静默降级：{@code VEC_BACKEND=opensearch} 而集群不可达时，这里 {@code ok=false}
     * 且 {@code detail} 以"向量后端不可用"开头（与检索报错同一句话），而不是回一段"网关可达"。
     */
    private Map<String, Object> vecBlock() {
        String backend = settings.getRetrieval().getBackend();
        boolean openSearch = backend != null && "opensearch".equalsIgnoreCase(backend.strip());
        VecHealth gateway = vecClient.health(5.0);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("backend", backend);
        info.put("embed_model", settings.getVec().getEmbedModel());
        info.put("rerank_model", settings.getVec().getRerankModel());
        info.put("dimensions", settings.getVec().getDimensions());
        info.put("upstream_ok", gateway.ok());
        info.put("upstream_detail", gateway.detail());
        info.put("models", gateway.models());

        if (openSearch) {
            VecHealth cluster = openSearchIndex.probeHealth(5.0);
            info.put("ok", cluster.ok());
            info.put("detail", cluster.detail());
            info.put("open_search", openSearchIndex.configSummary());
            if (cluster.ok()) {
                try {
                    info.put("indexed_chunks", openSearchIndex.count());
                } catch (RuntimeException e) {
                    // 探活刚通过、count 失败：如实记下，不让 /health 500
                    info.put("indexed_chunks", null);
                    info.put("detail", cluster.detail() + "；但 _count 失败：" + e.getMessage());
                }
                // 检索用的 query 向量仍来自平台网关：集群通了、网关挂了照样检索不了，所以两件事都报
                info.put("ok", cluster.ok() && gateway.ok());
            }
        } else {
            info.put("ok", gateway.ok());
            info.put("detail", gateway.detail());
        }
        return info;
    }

    private Map<String, Object> configSummary() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("gateway_base_url", settings.getGateway().getBaseUrl());
        config.put("gateway_api_key", mask(settings.getGateway().getApiKey()));
        config.put("llm_model", settings.getLlm().getModel());
        config.put("llm_max_input_chars", settings.getLlm().getMaxInputChars());
        config.put("llm_max_tokens", settings.getLlm().getMaxTokens());
        config.put("llm_enable_thinking", settings.getLlm().isEnableThinking());
        config.put("llm_chat_timeout", settings.getLimits().getChatTimeoutSeconds());
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
        config.put("vec_embed_timeout", settings.getLimits().getEmbedTimeoutSeconds());
        config.put("vec_rerank_timeout", settings.getLimits().getRerankTimeoutSeconds());
        config.put("retrieval_recall", settings.getRetrieval().getRecall());
        config.put("retrieval_recall_mode", settings.getRetrieval().getRecallMode());
        config.put("retrieval_recall_low", settings.getRetrieval().getRecallLow());
        config.put("retrieval_top_k", settings.getRetrieval().getTopK());
        config.put("vec_backend", settings.getRetrieval().getBackend());
        config.put("open_search_index", settings.getOpenSearch().getIndex());
        // 只报"配没配"，不回显地址里的凭据与口令本体
        config.put("open_search_auth", mask(settings.getOpenSearch().getPassword()));
        // L1 业务闸门的关键取值（**逐项与 ai.gate.* 三处一致核对**，见 ai-backend/README.md）
        config.put("gate_enabled", settings.getGate().isEnabled());
        config.put("gate_interactive", settings.getGate().getInteractive().getMax() + "并发/"
                + settings.getGate().getInteractive().getQueue() + "队列/"
                + settings.getGate().getInteractive().getWaitMs() + "ms");
        config.put("gate_batch_parse", settings.getGate().getBatchParse().getMax() + "并发/"
                + settings.getGate().getBatchParse().getQueue() + "队列");
        config.put("gate_batch_reindex", settings.getGate().getBatchReindex().getMax() + "并发");
        config.put("gate_pause_batch_when_interactive", settings.getGate().isPauseBatchWhenInteractive());
        // 流水线上报（**只报"配没配"，绝不回显令牌**）
        config.put("pipeline_report_enabled", pipelineReporter.enabled());
        config.put("pipeline_token", mask(settings.getPipeline().getServiceToken()));
        config.put("pipeline_chunk_key_format", settings.getPipeline().getChunkKeyFormat());
        config.put("pipeline_clean_rev", settings.getPipeline().getCleanRev());
        config.put("pipeline_rev", com.pmgt.ai.module.pipeline.PipelineRev.pipelineRev());
        config.put("work_dir", settings.getWorkDir().toAbsolutePath().toString());
        return config;
    }

    /** 密钥只报"配没配"，绝不回显明文。 */
    private String mask(String secret) {
        return secret == null || secret.isBlank() ? "未配置" : "已配置";
    }
}
