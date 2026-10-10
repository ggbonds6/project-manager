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
    private Limits limits = new Limits();
    private BizQuery bizQuery = new BizQuery();

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
        /**
         * 召回条数（<b>送进 Reranker 的候选数</b>）。
         *
         * <p>它的作用与耗时（P0-2/P0-7 要求写清，别只看名字）：
         * 每次检索的重排打分对数 = 向量榜 recall 条 + 关键词榜 recall/2 条（去重后），
         * 而一次问答实测会走 2 次检索 ⇒ <b>每次问答 ≈ 1.5 × recall 对</b>。
         * 平台重排实测吞吐约 24 对/s ⇒ recall=50 时 ≈75 对/次 ≈3.1s、每次问答 ≈6.3s，
         * 问答上限被压到 ≈0.16 QPS；recall 降到 20~25 可把上限提到 ≈0.4 QPS（约 2.5×）。
         * <b>但降召回会动到答案质量</b>，所以默认仍是 50（见 {@link #recallMode}）。
         */
        private int recall = 50;

        /** 精排后返回条数。 */
        private int topK = 5;

        /**
         * 召回档位（P0-7 的"便于后续降配"开关，<b>默认 baseline</b>）：
         * <ul>
         *   <li>{@code baseline} → 用 {@link #recall}（默认 50）；</li>
         *   <li>{@code low} → 用 {@link #recallLow}（默认 25），并记一条日志/指标说明"已降配"；</li>
         *   <li>{@code custom} → 仍用 {@link #recall}（运维自己填值，语义与 baseline 相同，
         *       只是让"我确实手填了"在配置里显式可见）。</li>
         * </ul>
         * ⚠️ <b>把默认值改成 low/25 之前必须先跑评测对比</b>：用 {@code eval/smoke-30.json}
         * 跑降配前后两轮，<b>引用命中率与数字一致率都不下降</b>才允许改默认
         * （口径见 {@code docs/AI模型调用与任务队列评估.md} §6.1 P0-2 与 §3.3）。
         */
        private String recallMode = "baseline";

        /** {@code recall-mode=low} 时使用的召回条数（P0-2 建议区间 20~25）。 */
        private int recallLow = 25;

        /** 索引后端：local（进程内余弦，零部署）| opensearch（正式选型）。 */
        private String backend = "local";
    }

    /**
     * OpenSearch 向量后端（P1-6 / 本轮接入）。
     *
     * <p>⚠️ <b>URL 必须指向"实际作为生产的那一台"</b>：本仓库文档里的 .110 与 .111 是
     * <b>两个彼此独立的单节点集群</b>（各自 {@code discovery.type: single-node}，数据集不共享），
     * <b>不要同时接业务</b>（否则同一个问题在两个后端上会给出不同召回，索引各自演化）。
     * 生产为 {@code http://10.254.212.110:9200}；{@code 10.254.212.111} 是冷备/演练机。
     */
    @Getter
    @Setter
    public static class OpenSearch {
        /** 生产集群地址；空值 = 未配置（{@code VEC_BACKEND=opensearch} 时会在检索前显式报错）。 */
        private String url = "http://10.254.212.110:9200";
        private String index = "pm-ai-chunks";
        /** basic auth 用户名（生产是 admin；口令见 {@link #password}）。 */
        private String username = "admin";
        /**
         * 口令：<b>只从环境变量 / .env 读</b>（{@code OPENSEARCH_PASSWORD}）。
         * 绝不写进仓库、日志、异常消息与测试断言；{@code /health} 与 {@code configSummary()}
         * 只报"配没配"。
         */
        private String password = "";
        /** 单次请求超时（秒）。与模型超时分开：这是检索基础设施，不是 GPU 模型调用。 */
        private double timeoutSeconds = 20;
        /** {@code _bulk} 每批文档数（默认 500；切片向量 4096 维，单批约 10MB 级）。 */
        private int bulkSize = 500;
        /**
         * 是否启用 hybrid（BM25 + kNN 两路，再按 chunk_key 融合）。
         *
         * <p><b>默认关闭</b>，理由（不是懒得做）：① BM25 分与 {@code cosinesimil} 分<b>不同量纲</b>，
         * 加权求和必须先各自归一化，而本项目既有的融合口径是"向量命中优先、关键词补齐"，
         * 两套口径混用会让召回顺序不可解释；② 关键词那一路<b>本地已经有一份</b>
         * （{@code RetrievalService.keywordScores}，2-gram + IDF），开 hybrid 会出现"两套字面召回"；
         * ③ 开与不开的召回质量<b>没有评测基线</b>（{@code eval/smoke-30.json} 需先补页码标注），
         * 所以先留可配置开关、默认走纯 kNN，等有对比数据再决定。
         */
        private boolean hybrid = false;
    }

    /**
     * 模型调用的超时 / 并发 / 重试上限（P0-1、P0-5、P0-3，见
     * {@code docs/AI模型调用与任务队列评估.md} §4 与 §5.2.2）。
     *
     * <p>取值依据（都写在这里，避免"调参靠猜"）：
     * <ul>
     *   <li><b>超时</b>：{@code chat 150s}（硬约束 &lt; 调用方 180s，见
     *       {@link #callerTimeoutSeconds}）、{@code embed 30s}（单批 32 条，实测 P50 42ms/条，
     *       30s 已是 20 倍余量）、{@code rerank 20s}（75 对 ÷ 24 对/s ≈ 3.1s）、
     *       OCR 沿用 {@code ai.ocr.timeout-seconds=600}（单页 ~1.8s × 16 页 + 排队余量）；</li>
     *   <li><b>并发</b>：{@code chat 4}（对话平台 4 副本，每个同时只跑 1 条序列）、
     *       {@code ocr 12}（= 既有 {@code OCR_CONCURRENCY}，平台实测甜点在飞 12 → 2.45 页/s）、
     *       {@code embed 8}（手册实测"批 32 + 并发 8 = 147.9 条/s"，单卡饱和点，再大无收益）、
     *       {@code rerank 2}（单实例、峰值 24.8 对/s，且它是问答链路的单点）。</li>
     * </ul>
     */
    @Getter
    @Setter
    public static class Limits {
        /** 对话单轮超时（秒）。 */
        private double chatTimeoutSeconds = 150;
        /** 向量化单次请求超时（秒）。 */
        private double embedTimeoutSeconds = 30;
        /** 重排单次请求超时（秒）。 */
        private double rerankTimeoutSeconds = 20;

        /**
         * 调用方（主系统 → AI，{@code AI_CHAT_TIMEOUT}）的预算（秒）= 硬约束上界。
         *
         * <p>不变式：<b>所有 AI 侧模型超时都必须小于它</b>，否则"用户已超时、槽位仍被占用"
         * （P0-1 的根因）。启动时校验并把结论打进日志（见 {@code StartupDiagnostics}）。
         */
        private double callerTimeoutSeconds = 180;

        /** 同时在飞的对话请求上限（平台 4 副本）。 */
        private int chatConcurrency = 4;
        /** 同时在飞的向量化请求上限（手册：批 32 + 并发 8 = 147.9 条/s）。 */
        private int embedConcurrency = 8;
        /** 同时在飞的重排请求上限（单实例，问答链路单点，保守取 2）。 */
        private int rerankConcurrency = 2;

        /**
         * 交互式调用等 permit 的时长（毫秒）：超时即"模型繁忙"快速失败（503），
         * 而不是无限排队到用户超时。
         */
        private long interactiveAcquireMs = 1500;
        /** 批量调用（解析/重建索引）等 permit 的时长：没人等，可以排队。 */
        private long batchAcquireMs = 60000;

        /** 幂等调用的额外重试次数（OCR / embedding / 检索；<b>chat 永不重试</b>）。 */
        private int retryMax = 2;
        /** 首次退避（毫秒），此后按倍数增长（1s → 4s）。 */
        private long retryBackoffMs = 1000;
        /** 退避倍数。 */
        private double retryBackoffMultiplier = 4.0;
        /** 退避抖动上限（毫秒），避免多路重试同时打上去。 */
        private long retryJitterMs = 250;
    }

    /**
     * P2 受控查询（{@code docs/AI前端与集成方案.md} §11）的配置。
     *
     * <p>⚠️ 这里<b>只有超时</b>：回调地址与 {@code scope_token} 由主系统在每次 {@code /chat}
     * 请求里现给（{@code biz_query}），AI 侧不配置、也不持有任何长期凭据——
     * 这正是 §11.1"反向回调"的意思（AI 服务不直连库，地址由主系统告诉它）。
     */
    @Getter
    @Setter
    public static class BizQuery {
        /**
         * 回调主系统 {@code /api/ai/query/{entity}} 的超时（秒）。
         *
         * <p>为什么是 20s（15~30s 区间内）：主系统侧是"一条受控 SQL + 写 operate_log"，
         * 正常亚秒级，这个量级足够吸收并发与偶发慢查询；同时它远小于网关的 300s——
         * 回调要是卡死，宁可这一轮问答明说"系统数据没查到"，也不能把整轮问答拖成几分钟
         * （工具调用是问答链路里的一环，它慢，用户就一起等）。
         */
        private int timeoutSeconds = 20;
    }
}
