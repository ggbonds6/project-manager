package com.pmgt.ai.module.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台 OCR 客户端（PaddleOCR-VL，走内网平台网关）。对应 Python 的 {@code pm_ai/platform_ocr.py}。
 *
 * <p>接口契约见 {@code ai-backend/docs/平台OCR调用使用手册.md}（随迁移从 Python 侧迁入）；只用 JDK 的
 * {@link HttpClient} + Jackson，不引第三方 HTTP 客户端。
 *
 * <table border="1">
 *   <caption>要点</caption>
 *   <tr><th>项</th><th>值</th></tr>
 *   <tr><td>端点</td><td>{@code POST {gateway.baseUrl}/ocr}、{@code GET {gateway.baseUrl}/ocr/health}</td></tr>
 *   <tr><td>地址/密钥</td><td>复用网关的 baseUrl/apiKey（同一网关、同一把 sk），见 {@link AiSettings.Gateway}</td></tr>
 *   <tr><td>输出</td><td>每页 {@code markdown} + {@code blocks}（版面块，含 bbox）</td></tr>
 *   <tr><td>性能</td><td>单页 ~1.8s；实测并发 12 → 2.45 页/秒（同页两次结果完全一致，服务端是确定性的）</td></tr>
 * </table>
 *
 * <h2>必须保留的实测结论（三条，其中两条有测试钉住）</h2>
 *
 * <p><b>① {@code blocks} 的位置随请求形式变化。</b>单页请求（{@code {"image": ...}}）在**顶层**；
 * 批量请求（{@code {"images": [...]}}）顶层**没有** blocks，在 {@code results[i].blocks}。
 * 本类统一从 {@code results} 取，两种形式都能拿到（实测确认）。测试里故意在顶层放"诱饵块"，
 * 断言解析只认 {@code results}——否则一旦服务端两种形式都返回，就会拿到错位/重复的块，出处定位跟着错。
 *
 * <p><b>② 平台不返回置信度。</b>{@link PlatformPage} 里根本没有 confidence 字段，上层只能填 null，
 * {@code low_confidence_pages} 恒空。**绝不能编一个出来**：历史上出过"OCR 平均置信度 0.97 的文件里金额被认错"的教训，
 * 编一个 0.9x 等于把人骗去相信一个不存在的信号。
 *
 * <p><b>③ 印章默认不开，而且低 DPI 下会编造。</b>默认（{@code seal=false}）平台仍会返回
 * {@code label == "seal"} 的块（内容为空）——这是"这页有章"的**免费探测**信号，不是噪声，别丢。
 * 开 {@code seal=true} 会按 {@code sealMinPixels} 放大印章裁剪图再单独跑一次 VL，
 * 实测 150 DPI 图上读出过完全不相干的银行名（真实印章是另一家公司的），故默认关闭；
 * 确需读印章文字要用高 DPI 原图重跑（手册的"两遍法"）。
 *
 * <h2>与 Python 版的行为对齐（照搬，不自己发明）</h2>
 * <ul>
 *   <li><b>分批</b>：每批 {@code ocr.batchPages}（默认 8）页，硬上限 {@link #MAX_BATCH_PAGES} = 16（手册 §7.2）。</li>
 *   <li><b>并发</b>：同时在飞的请求数 = {@code min(ocr.concurrency, 批数)}，至少 1；为 1 时串行执行（不走线程池）。
 *       平台是本项目专用的，可以打满（实测并发 12 → 2.45 页/秒）。</li>
 *   <li><b>失败页占位</b>：整批失败（HTTP 错误 / 连不上 / 超时 / 响应不是合法 JSON）时，
 *       把同一句错误**分发给该批每一页**并保留 {@code elapsed}，不中断其它批；失败页在返回列表里**占位**，
 *       所以 {@code 返回顺序 == 输入图片顺序}，页号靠下标对齐。</li>
 *   <li><b>不重试、不退避</b>：Python 版没有重试逻辑（失败就是失败，交给上传任务层报错），这里也不加。</li>
 *   <li><b>单页耗时是估算值</b>：平台按批返回，拿不到单页耗时，按服务端 {@code total_ms} 均摊到本批各页；
 *       拿不到就退回客户端墙钟。</li>
 *   <li><b>健康探测 TTL 缓存</b>：ok 缓存 60s、fail 缓存 15s（失败不缓存太久，平台恢复后能自动接回）。</li>
 * </ul>
 *
 * <p>唯一一处**有意偏离** Python：读图失败（文件丢了/读不动）在 Python 里会从批次里抛出去、
 * 让整个 {@code ocr_images} 炸掉；这里把它算作该批的失败（同样逐页占位带 error）。
 * 理由：失败页占位是上层（上传任务）赖以分流的契约，让一个坏文件毁掉整份文档的进度不合理。
 */
@Component
public class PlatformOcrClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformOcrClient.class);

    /** 单次请求的最大页数（手册 §7.2：≤16 页/请求）。 */
    public static final int MAX_BATCH_PAGES = 16;

    /** 探测成功后的缓存时长（秒）：期间复用，避免每次上传都去探一次。 */
    public static final double HEALTH_TTL_OK_SECONDS = 60.0;

    /** 探测失败后的缓存时长（秒）：短 TTL，平台恢复后能自动接回。 */
    public static final double HEALTH_TTL_FAIL_SECONDS = 15.0;

    /** {@code health(boolean)} 的默认探测超时（与 Python {@code health(timeout=10.0)} 一致）。 */
    public static final double DEFAULT_HEALTH_TIMEOUT_SECONDS = 10.0;

    private static final int HEALTH_ERROR_BODY_CHARS = 160;
    private static final int OCR_ERROR_BODY_CHARS = 300;

    /**
     * 建连超时。Python 的 {@code urlopen(timeout)} 一个参数同时管建连和读取，
     * 而 JDK 的 {@code connectTimeout} 与请求超时是分开的：这里把建连固定成 10s
     * （内网网关连不上就该快速失败），请求超时按每个请求设置（OCR 用 {@code ocr.timeoutSeconds}）。
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 只做树模型读写，不需要 Spring 定制过的 ObjectMapper，故用静态实例（纯解析函数也就能是 static）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettings settings;
    private final HttpClient http;
    private final Object progressLock = new Object();

    /** 健康探测缓存（Python 的模块级 {@code _HEALTH} + {@code _HEALTH_LOCK}）。 */
    private volatile CacheEntry healthCache;

    public PlatformOcrClient(AiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ── 识别 ─────────────────────────────────────────────────────────

    /** 方便调用方不关心进度。 */
    public List<PlatformPage> recognize(List<Path> images) {
        return recognize(images, ProgressFn.NONE);
    }

    /**
     * 批量识别图片，**返回顺序与输入严格一致**（失败页占位并带 {@code error}）。
     *
     * <p>分批、并发、印章开关全部来自 {@link AiSettings.Ocr}；进度阶段名为 {@code ocr}，
     * 每完成一批回调一次 {@code (已完成页数, 总页数)}（与 Python 的 {@code on_progress} 一致）。
     */
    public List<PlatformPage> recognize(List<Path> images, ProgressFn progress) {
        List<Path> items = images == null ? List.of() : List.copyOf(images);
        // 空输入在组装 payload 之前就返回：不碰配置、不碰网络（Python 测试钉住了这条）。
        if (items.isEmpty()) {
            return List.of();
        }

        AiSettings.Ocr ocr = settings.getOcr();
        int size = Math.max(1, Math.min(ocr.getBatchPages(), MAX_BATCH_PAGES));
        int groupCount = (items.size() + size - 1) / size;
        int workers = Math.max(1, Math.min(ocr.getConcurrency(), groupCount));
        boolean seal = ocr.isSeal();
        int sealMinPixels = ocr.getSealMinPixels();
        ProgressFn onProgress = progress == null ? ProgressFn.NONE : progress;

        // 先全部铺空页：任何没被写回的槽位也是"空页"而不是 null，页号靠下标对齐。
        List<PlatformPage> out = new ArrayList<>(Collections.nCopies(items.size(), emptyPage()));
        AtomicInteger done = new AtomicInteger();

        if (workers == 1) {
            for (int start = 0; start < items.size(); start += size) {
                runGroup(items, out, start, size, seal, sealMinPixels, onProgress, done);
            }
            return out;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "platform-ocr");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(groupCount);
            for (int start = 0; start < items.size(); start += size) {
                int s = start;
                futures.add(pool.submit(() -> runGroup(items, out, s, size, seal, sealMinPixels, onProgress, done)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("平台 OCR 等待被中断，剩余批次不再等待：{}", e.toString());
                    break;
                } catch (Exception e) {
                    // runGroup 内部已兜底；真漏出来了也只影响该批（那批保持空页占位），不能让整份文档失败。
                    log.warn("平台 OCR 批次异常退出：{}", e.toString());
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        return out;
    }

    /** 识别一批页面（**一次请求**，≤16 页）。失败时把错误分发给整组，不中断其它组。 */
    private void runGroup(List<Path> items, List<PlatformPage> out, int start, int size,
                          boolean seal, int sealMinPixels, ProgressFn progress, AtomicInteger done) {
        int end = Math.min(start + size, items.size());
        int count = end - start;
        long t0 = System.nanoTime();

        List<PlatformPage> pages = null;
        String payloadError = null;

        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode arr = payload.putArray("images");
        try {
            for (int i = start; i < end; i++) {
                arr.add(Base64.getEncoder().encodeToString(Files.readAllBytes(items.get(i))));
            }
        } catch (IOException e) {
            // 有意的偏离：Python 这里会把异常抛到整个 ocr_images；这里按批失败逐页占位。
            payloadError = simpleName(e) + ": " + e.getMessage() + "（图片读不出来：" + items.get(start) + "）";
        }

        if (payloadError == null) {
            if (seal) {
                payload.put("seal", true);
                if (sealMinPixels > 0) {
                    payload.put("seal_min_pixels", sealMinPixels);
                }
            }
            try {
                JsonNode d = post(payload);
                pages = pagesWithElapsed(d, count, elapsedSeconds(t0));
            } catch (RuntimeException e) {
                payloadError = e.getMessage() == null ? simpleName(e) : e.getMessage();
            }
        }

        double elapsed = round2(elapsedSeconds(t0));
        if (pages == null) {
            log.warn("平台 OCR 一批 {} 页识别失败：{}", count, payloadError);
            pages = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                pages.add(new PlatformPage("", List.of(), elapsed, payloadError));
            }
        }
        for (int i = 0; i < count; i++) {
            out.set(start + i, pages.get(i));
        }

        synchronized (progressLock) {
            progress.on("ocr", done.addAndGet(count), items.size());
        }
    }

    /** {@code POST {base}/ocr}；非 2xx、连不上、超时、响应不是合法 JSON 都抛 {@link OcrCallException}。 */
    private JsonNode post(JsonNode payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/ocr"))
                .timeout(Duration.ofSeconds(Math.max(1, settings.getOcr().getTimeoutSeconds())))
                .header("Content-Type", "application/json");
        if (hasApiKey()) {
            builder.header("Authorization", "Bearer " + settings.getGateway().getApiKey());
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new OcrCallException("HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body(), OCR_ERROR_BODY_CHARS));
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                // Python 的 json.loads("") 会抛错 → 整组失败；Jackson 的 readTree("") 只给 MissingNode，
                // 所以这里显式当成失败，免得"200 + 空体"被静默当成"识别出来是空的"。
                throw new OcrCallException("EmptyResponse: 平台返回 200 但响应体为空，无法解析识别结果");
            }
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new OcrCallException(simpleName(e) + ": " + e.getMessage());
        } catch (IOException e) {
            throw new OcrCallException(simpleName(e) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrCallException(simpleName(e) + ": " + e.getMessage());
        }
    }

    // ── 连通性探测（带缓存）───────────────────────────────────────────
    //
    // 每次上传都去探测一次会拖慢响应；但完全不探测又无法判断"平台能不能用"。
    // 折中：结果缓存 TTL 秒（ok 60s / fail 15s），期间复用；失败时不缓存太久，便于平台恢复后自动接回。

    /** 探测平台 OCR 是否可用（结果带缓存，默认超时 {@value #DEFAULT_HEALTH_TIMEOUT_SECONDS} 秒）。 */
    public OcrHealth health(boolean force) {
        return health(force, DEFAULT_HEALTH_TIMEOUT_SECONDS);
    }

    /** 与 Python {@code health(force, timeout)} 同形：{@code api.py} 的 {@code /health} 用的是 timeout=3。 */
    public OcrHealth health(boolean force, double timeoutSeconds) {
        CacheEntry cached = healthCache;
        if (!force && cached != null) {
            double ttl = cached.health().ok() ? HEALTH_TTL_OK_SECONDS : HEALTH_TTL_FAIL_SECONDS;
            if (nowSeconds() - cached.ts() < ttl) {
                return cached.health();
            }
        }

        OcrHealth result = probe(timeoutSeconds);
        healthCache = new CacheEntry(result, nowSeconds());
        return result;
    }

    /**
     * 平台 OCR 是否可用（供 {@code /health} 探针使用）。
     *
     * <p>注意：本地 OCR 兜底已于 2026-09-18 移除，本方法不再用于"走平台还是走本地"的选路。
     */
    public boolean available(boolean force) {
        return health(force).ok();
    }

    private OcrHealth probe(double timeoutSeconds) {
        String base = baseUrl();
        if (base.isBlank()) {
            return OcrHealth.down("未配置 OCR 地址（OCR_BASE_URL 或 LLM_BASE_URL）");
        }
        String url = base + "/ocr/health";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, (long) Math.ceil(timeoutSeconds))))
                    .GET();
            if (hasApiKey()) {
                builder.header("Authorization", "Bearer " + settings.getGateway().getApiKey());
            }
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return OcrHealth.down("HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body(), HEALTH_ERROR_BODY_CHARS) + "（401 检查 sk 是否有效）");
            }
            JsonNode d = MAPPER.readTree(resp.body() == null ? "" : resp.body());
            int workers = intOf(d, "workers");
            int idle = intOf(d, "idle");
            List<String> options = stringListOf(d, "options");
            boolean ok = "ok".equals(textOf(d, "status"));
            return new OcrHealth(ok, workers, idle, options, "workers=" + workers + " idle=" + idle);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OcrHealth.down(simpleName(e) + ": " + e.getMessage() + "（检查网络能否访问 " + base + "）");
        } catch (Exception e) {
            // 探测要吞掉所有异常并如实上报（Python 的 noqa: BLE001 同理），否则 /health 自己会 500。
            return OcrHealth.down(simpleName(e) + ": " + e.getMessage() + "（检查网络能否访问 " + base + "）");
        }
    }

    // ── 响应解析（纯函数，可单测；不发网络请求）────────────────────────

    /**
     * 把响应 JSON 字符串拆成 n 页。**纯函数**：不碰网络、不起线程，测试直接喂构造的响应。
     *
     * <p>非法 JSON / 空字符串一律返回 n 个空页而不抛异常（HTTP 层的错误在 {@link #post} 里已经分流）。
     */
    public static List<PlatformPage> parseResponse(String json, int n) {
        if (json == null || json.isBlank()) {
            return pagesFromResponse(null, n);
        }
        try {
            return pagesFromResponse(MAPPER.readTree(json), n);
        } catch (JsonProcessingException e) {
            return pagesFromResponse(null, n);
        }
    }

    /**
     * 把响应拆成 n 页（Python 的 {@code _pages_from_response}，逐行对齐）。
     *
     * <p>**统一从 {@code results} 取**：批量请求的 blocks 只在 results 里，单页请求虽有顶层 blocks、
     * 但也附带 results —— 走同一条路少一个分支，少一个 bug。
     * 唯一的例外是"n == 1 且没有 results 但顶层有内容"（少数实现可能不返回 results）时的顶层兜底；
     * 这个兜底**只对单页生效**：批量（n &gt; 1）缺 results 时必须如实返回空页，
     * 不能把顶层那一段文本复制到每一页——那是凭空造出重复内容，比空着更危险。
     */
    public static List<PlatformPage> pagesFromResponse(JsonNode d, int n) {
        List<JsonNode> results = new ArrayList<>();
        if (d != null) {
            JsonNode node = d.get("results");
            if (node != null && node.isArray()) {
                node.forEach(results::add);
            }
        }

        if (results.isEmpty() && n == 1 && d != null
                && (!textOf(d, "markdown").isEmpty() || blockCount(d) > 0)) {
            return List.of(pageOf(d));
        }

        List<PlatformPage> out = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            JsonNode r = i < results.size() ? results.get(i) : null;
            out.add(pageOf(r));
        }
        return out;
    }

    /**
     * 单页：{@code markdown} / {@code blocks}；字段缺失一律按空。
     *
     * <p>Python 的 {@code PlatformPage} 还带 {@code width}/{@code height}（页面像素尺寸）。
     * Java 侧的接缝（{@link PlatformPage} record）没有这两个字段——正文链路不用它，
     * 故这里只把 markdown 与 blocks 带出来，**不为了对齐而擅自扩接缝**。
     */
    private static PlatformPage pageOf(JsonNode r) {
        return new PlatformPage(textOf(r, "markdown"), blocksOf(r), 0.0, null);
    }

    /**
     * 版面块：平台给的是 {@code {"label": ..., "content": ..., "bbox": [x1,y1,x2,y2]}}。
     * {@code content} 映射到 {@link PlatformPage.Block#text()}（兼容个别实现写成 {@code text}）；
     * bbox 缺失就是 {@code null}，不编造坐标。
     */
    private static List<PlatformPage.Block> blocksOf(JsonNode r) {
        if (r == null) {
            return List.of();
        }
        JsonNode blocks = r.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return List.of();
        }
        List<PlatformPage.Block> out = new ArrayList<>(blocks.size());
        for (JsonNode b : blocks) {
            String text = b.hasNonNull("content") ? b.get("content").asText("") : textOf(b, "text");
            out.add(new PlatformPage.Block(textOf(b, "label"), text, bboxOf(b)));
        }
        return out;
    }

    private static double[] bboxOf(JsonNode block) {
        JsonNode bbox = block == null ? null : block.get("bbox");
        if (bbox == null || !bbox.isArray() || bbox.isEmpty()) {
            return null;
        }
        double[] out = new double[bbox.size()];
        for (int i = 0; i < bbox.size(); i++) {
            out[i] = bbox.get(i).asDouble(0);
        }
        return out;
    }

    /** 逐页耗时是**估算值**：平台按批返回，拿不到单页耗时，按服务端 total_ms 均摊（拿不到就用墙钟）。 */
    private static List<PlatformPage> pagesWithElapsed(JsonNode d, int n, double wallSeconds) {
        double totalMs = d == null ? 0.0 : doubleOf(d, "total_ms");
        double serverSeconds = totalMs / 1000.0;
        if (serverSeconds == 0.0) {
            serverSeconds = wallSeconds;
        }
        double per = round2(serverSeconds / Math.max(1, n));
        List<PlatformPage> out = new ArrayList<>(Math.max(0, n));
        for (PlatformPage p : pagesFromResponse(d, n)) {
            out.add(new PlatformPage(p.markdown(), p.blocks(), per, p.error()));
        }
        return out;
    }

    // ── 小工具 ───────────────────────────────────────────────────────

    private static PlatformPage emptyPage() {
        return new PlatformPage("", List.of(), 0.0, null);
    }

    private String baseUrl() {
        String base = settings.getGateway().getBaseUrl();
        return base == null ? "" : base.trim().replaceAll("/+$", "");
    }

    private boolean hasApiKey() {
        String key = settings.getGateway().getApiKey();
        return key != null && !key.isBlank();
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static int intOf(JsonNode node, String field) {
        if (node == null) {
            return 0;
        }
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        // 平台偶尔把数字写成字符串（int("80") 在 Python 里是合法的）；缺/非法一律按 0，不当成探测失败。
        try {
            String s = n.asText("").trim();
            return s.isEmpty() ? 0 : (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleOf(JsonNode node, String field) {
        if (node == null) {
            return 0.0;
        }
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return 0.0;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        try {
            String s = n.asText("").trim();
            return s.isEmpty() ? 0.0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static List<String> stringListOf(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode n = node.get(field);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            out.add(item.asText(""));
        }
        return out;
    }

    private static int blockCount(JsonNode d) {
        if (d == null) {
            return 0;
        }
        JsonNode blocks = d.get("blocks");
        return blocks != null && blocks.isArray() ? blocks.size() : 0;
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    /** 与 Python 的 {@code round(x, 2)} 同口径（两位小数，页面耗时展示用）。 */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double nowSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static String truncate(String body, int max) {
        if (body == null) {
            return "";
        }
        return body.length() > max ? body.substring(0, max) : body;
    }

    private static String simpleName(Throwable e) {
        return e.getClass().getSimpleName();
    }

    /** 缓存条目：值 + 探测时间戳（时间戳不进 {@link OcrHealth}，它不是对外字段）。 */
    private record CacheEntry(OcrHealth health, double ts) {
    }

    /** 平台调用失败（对应 Python 的 {@code PlatformOcrError}）：消息直接写"下一步该查什么"。 */
    private static final class OcrCallException extends RuntimeException {
        OcrCallException(String message) {
            super(message);
        }
    }
}
