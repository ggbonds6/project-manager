package com.pmgt.ai.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 服务配置，全部来自环境变量（见 application.yml 的默认值与 ai-backend/README.md）。
 *
 * <p>与 Python 版一致的设计：<b>所有键名显式列出</b>，不做隐式映射——排查"配置为什么没生效"时，
 * 能一眼看出服务认哪些键。四个能力（对话 / OCR / 向量化 / 重排）默认共用同一个网关与同一把 sk，
 * 只有需要分开指向时才单独配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiSettings {

    /** 工作目录：OCR 中间产物、文档库、向量缓存都在它下面（默认 ./work）。 */
    private Path workDir = Path.of("./work");

    private Gateway gateway = new Gateway();
    private Llm llm = new Llm();
    private Ocr ocr = new Ocr();
    private Vec vec = new Vec();
    private Retrieval retrieval = new Retrieval();
    private OpenSearch openSearch = new OpenSearch();

    /** 平台网关（OCR / 对话 / 向量化 / 重排共用）。 */
    @Getter
    @Setter
    public static class Gateway {
        /** 到 /v1 为止，例如 http://10.254.208.35:8090/v1 */
        private String baseUrl = "http://10.254.208.35:8090/v1";
        /** 用户 sk（与对话、OCR、向量化共用同一把）。 */
        private String apiKey = "";
        private int timeoutSeconds = 300;
    }

    /** 千问对话模型（OpenAI 兼容）。 */
    @Getter
    @Setter
    public static class Llm {
        private String model = "Qwen3.8-27B-W8A8";
        /** 单次送给模型的最大字符数；超过则按页截断，并在提示词里明确告知模型"你没看到全部"。 */
        private int maxInputChars = 20000;
        /** 输出上限。给得较大：思维链会吃掉大量额度，设小了会出现 content 为空。 */
        private int maxTokens = 16384;
        /** 是否保留思维链。默认 false——实测开启后思考耗尽额度、最终答案反而是空的。 */
        private boolean enableThinking = false;
    }

    /** 平台 OCR（PaddleOCR-VL）。本地 RapidOCR 兜底已在 Python 版移除，Java 版不再引入。 */
    @Getter
    @Setter
    public static class Ocr {
        private String model = "PaddleOCR-VL-1.6-0.9B";
        private int timeoutSeconds = 600;
        /** 同时在飞的请求数（实测并发 12 得 2.45 页/秒，是串行的 4.5 倍）。 */
        private int concurrency = 12;
        /** 每个请求带几页（平台建议 ≤16）。 */
        private int batchPages = 8;
        /** 是否识别印章。默认 false：开启会让含章文档吞吐减半，且低 DPI 下会编造印章文字。 */
        private boolean seal = false;
        private int sealMinPixels = 400000;
        /** 发给平台的图：150 DPI JPEG（实测 120~300 DPI 结果一致，但体积差 17 倍）。 */
        private int platformDpi = 150;
        private String platformFormat = "jpeg";
        private int platformQuality = 85;
        /** 判定扫描件的阈值：平均每页字符数低于此值即视为扫描件。 */
        private int scannedCharThreshold = 50;
    }

    /** 向量化与重排（Qwen3-VL-Embedding / Reranker）。 */
    @Getter
    @Setter
    public static class Vec {
        private String embedModel = "Qwen3-VL-Embedding-8B";
        private String rerankModel = "Qwen3-VL-Reranker-8B";
        /** 输出维度；0 = 不传该参数（用平台默认 4096）。
         * 实测本平台部署不支持 MRL 降维：传 dimensions 会 HTTP 400。 */
        private int dimensions = 0;
    }

    /** 检索链路参数。 */
    @Getter
    @Setter
    public static class Retrieval {
        /** 召回条数（送进 Reranker 的候选数）。 */
        private int recall = 50;
        /** 精排后返回条数。 */
        private int topK = 5;
        /** 索引后端：local（进程内余弦，零部署）| opensearch（正式选型）。 */
        private String backend = "local";
    }

    /** OpenSearch（正式选型；集群未部署时 backend 保持 local）。 */
    @Getter
    @Setter
    public static class OpenSearch {
        private String url = "";
        private String index = "pm-ai-chunks";
        private String username = "";
        private String password = "";
    }
}
