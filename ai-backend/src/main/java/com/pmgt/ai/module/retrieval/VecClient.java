package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Qwen3-VL Embedding / Reranker 客户端（平台网关，纯 HTTP）。
 *
 * <p>手册：{@code ai-service/Qwen3-VL-Embedding-Reranker调用手册.md}
 * <ul>
 *   <li>向量化 {@code POST {base}/embeddings} → {@code {"data":[{"index":i,"embedding":[...]}]}}</li>
 *   <li>重排　 {@code POST {base}/rerank}　　 → {@code {"results":[{"index":i,"relevance_score":s}]}}</li>
 *   <li>探活　 {@code GET  {base}/models}</li>
 *   <li>鉴权　 {@code Authorization: Bearer <sk>}（与千问对话、平台 OCR <b>共用同一把 sk</b>，
 *       所以这里直接复用 {@code ai.gateway.base-url / api-key}，与 Python 版"没单独配就回落"同义）</li>
 * </ul>
 *
 * <p>为什么用 JDK 自带的 {@link HttpClient} + Jackson 而不是加 SDK：这里只是"发一个 JSON、收一个
 * JSON"，重活全在网关侧；少一个依赖就少一份内网装机成本（Python 版同理，只是它用的是 urllib）。
 *
 * <p>⚠️ 两个实测坑（手册里写明，别踩回去）：
 * <ol>
 *   <li>直连地址（{@code 10.254.213.135:8091/8092}）有防火墙白名单，<b>普通客户端连不上</b>，一律走网关；</li>
 *   <li>{@code /v1/score} 传图片时必须用 {@code content} 数组结构，写成 {@code {"image": ...}} 会 400。
 *       本类只用 {@code /embeddings} 与 {@code /rerank}（Jina 风格），不碰 {@code /score}。</li>
 * </ol>
 */
@Component
public class VecClient implements VecApi {

    /**
     * 向量化/重排调用失败（网络、鉴权或上游错误）。
     *
     * <p>嵌在 {@code VecClient} 里是为了"调用方只需认一个类型"；继承 {@link RuntimeException}
     * 是刻意的——检索链路要能捕获它并降级（向量不可用 → 关键词），受检异常会让整条链路到处 try。
     */
    public static class VecException extends RuntimeException {
        public VecException(String message) {
            super(message);
        }

        public VecException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 单次向量化请求的条数。手册实测：单请求 32 条 + 并发 8 可达 147.9 条/s（单卡）。 */
    public static final int EMBED_BATCH = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettings settings;
    private final HttpClient http;

    public VecClient(AiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    // 向量化
    // ══════════════════════════════════════════════════════════════

    @Override
    public List<float[]> embedTexts(List<String> texts, String instruction) {
        return embedTexts(texts, instruction, null);
    }

    /**
     * 批量向量化文本，返回顺序与入参一致。
     *
     * @param instruction 自定义指令（{@code messages[0].role=system}）。不传时模型默认
     *                    {@code Represent the user's input.}；官方实测按任务定制指令有 1%~5% 收益。
     *                    <b>注意：带指令时必须走 {@code messages} 形式，而 {@code dimensions} 只在
     *                    {@code input} 形式下传</b>（手册只给了这两种组合，混用未经验证）。
     * @param dimensions  覆盖配置的向量维度；{@code null}/0 表示不传该参数——
     *                    <b>本平台部署不支持 MRL 降维</b>：实测传 {@code dimensions} 直接
     *                    HTTP 400 {@code does not support matryoshka representation}，
     *                    默认 0 = 不传，返回 4096 维（手册写的 64~4096 与<b>实际部署</b>不符，以部署为准）。
     */
    public List<float[]> embedTexts(List<String> texts, String instruction, Integer dimensions) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int dims = dimensions != null ? dimensions : settings.getVec().getDimensions();
        String model = settings.getVec().getEmbedModel();

        if (instruction != null && !instruction.isBlank()) {
            // messages 形式：每次一条，逐条取 data[0]（手册只给了单条 messages 的示例）
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                JsonNode data = post("/embeddings", Payloads.withInstruction(model, instruction, text), null);
                out.add(toVector(firstRow(data)));
            }
            return out;
        }
        JsonNode data = post("/embeddings", Payloads.plainInput(model, texts, dims), null);
        return parseEmbeddings(data);
    }

    /**
     * 请求体构造（单独抽出来是为了能<b>离线断言线上格式</b>——两条"踩坑结论"都在这里：
     * {@code dimensions} 只在 {@code input} 形式下传、且仅当 &gt; 0 才传；带指令时走 {@code messages}）。
     */
    static final class Payloads {

        private Payloads() {
        }

        /** {@code {"model":..., "input":[...]}}；{@code dimensions} 仅当 &gt;0 才带上（本部署不支持 MRL）。 */
        static ObjectNode plainInput(String model, List<String> texts, int dimensions) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            ArrayNode input = payload.putArray("input");
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }
            if (dimensions > 0) {
                payload.put("dimensions", dimensions);
            }
            return payload;
        }

        /** 一条 system（指令）+ 一条 user（文本）；<b>不带 {@code dimensions}</b>（手册只给了这两种组合）。 */
        static ObjectNode withInstruction(String model, String instruction, String text) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            ArrayNode messages = payload.putArray("messages");
            messages.add(message("system", instruction));
            messages.add(message("user", text == null ? "" : text));
            return payload;
        }

        /** 重排请求体。 */
        static ObjectNode rerank(String model, String query, List<String> documents) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            payload.put("query", query == null ? "" : query);
            ArrayNode docs = payload.putArray("documents");
            for (String document : documents) {
                docs.add(document == null ? "" : document);
            }
            return payload;
        }
    }

    /**
     * 按 {@code index} 排序后再取向量——<b>避免上游乱序导致"向量与文本错配"</b>。
     *
     * <p>错配的后果比报错严重得多：向量还是 4096 维、余弦还是算得出，只是"这段话的向量"其实是
     * 另一段的，检索结果看起来正常却全错。所以这里显式排序，不假设上游顺序。
     */
    private List<float[]> parseEmbeddings(JsonNode data) {
        JsonNode rows = data != null && data.has("data") ? data.get("data") : data;
        List<JsonNode> sorted = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            rows.forEach(sorted::add);
        } else if (rows != null && !rows.isMissingNode() && !rows.isNull()) {
            sorted.add(rows);
        }
        sorted.sort(Comparator.comparingInt(r -> r.path("index").asInt(0)));
        List<float[]> out = new ArrayList<>();
        for (JsonNode row : sorted) {
            out.add(toVector(row));
        }
        return out;
    }

    private JsonNode firstRow(JsonNode data) {
        JsonNode rows = data != null && data.has("data") ? data.get("data") : data;
        if (rows != null && rows.isArray() && !rows.isEmpty()) {
            return rows.get(0);
        }
        return rows;
    }

    private static ObjectNode message(String role, String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        ArrayNode content = node.putArray("content");
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "text");
        part.put("text", text == null ? "" : text);
        content.add(part);
        return node;
    }

    /** {@code {"index":0,"embedding":[...]}} → float[]；缺失即视为上游返回了错结构，显式报错。 */
    private static float[] toVector(JsonNode row) {
        if (row == null) {
            throw new VecException("上游响应里没有向量数据（data/embedding 缺失）");
        }
        JsonNode embedding = row.has("embedding") ? row.get("embedding") : row;
        if (!embedding.isArray()) {
            throw new VecException("上游响应的 embedding 不是数组：" + abbreviate(row.toString()));
        }
        float[] vec = new float[embedding.size()];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) embedding.get(i).asDouble();
        }
        return vec;
    }

    // ══════════════════════════════════════════════════════════════
    // 重排
    // ══════════════════════════════════════════════════════════════

    /**
     * 按相关性重排候选，返回 {@code (入参下标, 分数)}，分数降序。
     *
     * <p>⚠️ <b>上游按原顺序返回 results，index 是入参下标，不能把返回顺序当排名用</b>——
     * 调用方必须拿这个 index 回查自己的候选列表（{@code RetrievalService} 就是这么做的）。
     * 这条是手册里写明的实测结论，照抄不解释：把返回顺序当排名会让精排静默失效
     * （结果"看起来有排序"，其实只是候选的原始顺序）。
     */
    @Override
    public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        JsonNode data = post("/rerank", Payloads.rerank(settings.getVec().getRerankModel(), query, documents), null);

        List<JsonNode> rows = new ArrayList<>();
        JsonNode results = data == null ? null : data.path("results");
        if (results != null && results.isArray()) {
            results.forEach(rows::add);
        }
        rows.sort(Comparator.comparingDouble(
                (JsonNode r) -> -r.path("relevance_score").asDouble(0.0)));

        List<RerankHit> out = new ArrayList<>();
        int rank = 0;
        for (JsonNode row : rows) {
            out.add(new RerankHit(row.path("index").asInt(0), row.path("relevance_score").asDouble(0.0), rank++));
        }
        if (topK != null && topK > 0 && out.size() > topK) {
            return new ArrayList<>(out.subList(0, topK));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // 探活与工具函数
    // ══════════════════════════════════════════════════════════════

    /** 探活：{@code GET /models}（轻量，不消耗向量化/重排配额）。 */
    @Override
    public VecHealth health(double timeoutSeconds) {
        JsonNode data;
        try {
            data = request("GET", "/models", null,
                    timeoutSeconds > 0 ? timeoutSeconds : settings.getGateway().getTimeoutSeconds());
        } catch (VecException e) {
            return new VecHealth(false, e.getMessage(), List.of());
        }
        List<String> names = new ArrayList<>();
        JsonNode rows = data == null ? null : data.get("data");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                if (row.isObject()) {
                    names.add(row.path("id").asText(""));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        String embed = settings.getVec().getEmbedModel();
        String rerank = settings.getVec().getRerankModel();
        if (!names.isEmpty()) {
            if (!names.contains(embed)) {
                missing.add(embed);
            }
            if (!names.contains(rerank)) {
                missing.add(rerank);
            }
        }
        if (!missing.isEmpty()) {
            return new VecHealth(
                    true,
                    "网关可达，但模型列表里没有 " + missing + "（仍是 " + names.size() + " 个模型）",
                    names);
        }
        return new VecHealth(true, "网关可达，两个模型都在", names);
    }

    /**
     * 余弦相似度。向量已归一化时它等于点积，但这里<b>不假设上游归一化</b>。
     * 长度不同直接返回 0（例如换了部署导致维度变了——缓存里旧向量与 query 长度不等，
     * 退化成 0 分而不是抛异常，让检索还能继续）。
     */
    @Override
    public double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ══════════════════════════════════════════════════════════════
    // HTTP
    // ══════════════════════════════════════════════════════════════

    private JsonNode post(String path, JsonNode payload, Double timeoutSeconds) {
        return request("POST", path, payload, timeoutSeconds);
    }

    /**
     * 统一的请求出口：任何网络/鉴权/上游异常都转成 {@link VecException}，
     * 上层才能"一句话降级"（向量不可用 → 关键词检索）。
     */
    private JsonNode request(String method, String path, JsonNode payload, Double timeoutSeconds) {
        String base = settings.getGateway().getBaseUrl();
        if (base == null || base.isBlank()) {
            throw new VecException("向量服务地址未配置（ai.gateway.base-url 为空）");
        }
        double timeout = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds
                : settings.getGateway().getTimeoutSeconds();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base.replaceAll("/+$", "") + path))
                .timeout(Duration.ofMillis(Math.max(1, (long) (timeout * 1000))))
                .header("Content-Type", "application/json");
        String apiKey = settings.getGateway().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if ("POST".equals(method)) {
            try {
                builder.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            } catch (Exception e) {
                throw new VecException("请求体序列化失败：" + e.getMessage(), e);
            }
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VecException("请求被中断：" + e.getMessage(), e);
        } catch (Exception e) {
            // 网络层异常统一转 VecError，便于上层降级
            throw new VecException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 手册的错误码表：401 鉴权、400 结构写错、502 网关连不上上游、500 上游异常
            throw new VecException("HTTP " + response.statusCode() + ": " + abbreviate(body));
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new VecException("响应不是合法 JSON：" + abbreviate(body), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}
