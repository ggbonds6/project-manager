package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.store.DocStore;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch kNN 索引适配层（{@code backend=opensearch} 时启用）。
 *
 * <p>⚠️⚠️ <b>已接线、未实测</b>：集群尚未部署，本类<b>没有对真实集群跑过任何一次调用</b>——
 * 所有字段名、查询结构都只是按 OpenSearch kNN 文档与《知识库落地实施方案》写出来的。
 * 调用链已经通了（{@code RetrievalService.search} 在 {@code backend=opensearch} 时会走
 * {@link #ensureIndexOnce} → {@link #upsertAll} → {@link #search}），所以命中结果里的
 * {@code note} 会带上"OpenSearch 路由已接线、未实测"这句话：<b>看到它就知道这条路径还没被验证过</b>。
 * 集群部署后<b>必须按 {@code docs/迁移方案与对照表.md} §5 的验证协议验证</b>：
 * <ol>
 *   <li>建索引：{@code PUT /<index>}，确认 mapping 里 {@code vector} 是 {@code knn_vector}、
 *       {@code dimension} 与实际向量维度一致（平台 {@code 4096}）、{@code space_type=cosinesimil}；</li>
 *   <li>写入：{@code POST /_bulk}，同一份文档写两遍，确认 {@code _id}（切片键）幂等、文档数不翻倍；</li>
 *   <li>查询：{@code search} 返回的 {@code page_no} 与文档库里的页码一致，且
 *       <b>与 local 后端（进程内余弦）的 top-k 顺序对得上</b>——顺序对不上说明字段或 space_type 写错了；</li>
 *   <li>对同一份语料跑 §5 的检索基线（{@code hybrid} + 已重排、命中带页码），给出同级数字后再把
 *       {@code VEC_BACKEND} 切到 opensearch。</li>
 * </ol>
 *
 * <p>未启用（{@code ai.open-search.url} 为空）时本类<b>必须能安全创建</b>：不建连接、不探活，
 * 什么都不做——守卫在 {@code RetrievalService.checkBackend()} 里，检索时才生效。
 * "启动即失败"会让开发机（没有集群）根本跑不起来，那不是我们想要的安全。
 *
 * <p><b>绝不静默退回 local</b>：{@code backend=opensearch} 时 url 为空 / 集群不可达 / 索引状态异常，
 * 都在检索时<b>显式抛 {@link VecClient.VecException}</b>，错误文案直接指向该查什么
 * （"OPENSEARCH_URL 未配置"、"集群不可达（http://…）：ConnectException: …"）。宁可让使用者看到一条
 * 明确报错，也不要让他拿到一个"以为在用集群、其实在进程内算"的结果。
 *
 * <p>id 设计：{@code _id} = 切片键 {@code (docId, pageNo, 内容 sha1 前 12 位)}——
 * 内容变了 id 就变（新切片），内容没变重复写入就是覆盖（幂等 upsert），不需要额外的版本号或重建流程。
 */
@Component
public class OpenSearchIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 配置。<b>刻意是 protected</b>：本类未实测，离线测试需要一个"能替换掉 HTTP 层"的替身，
     * 子类要能读到 url/index 名并覆写 {@link #health} / {@link #search}（见
     * {@code RetrievalServiceTest} 的 {@code StubIndex}）。生产代码不该用它做别的事。
     */
    protected final AiSettings settings;

    public OpenSearchIndex(AiSettings settings) {
        this.settings = settings;
    }

    /** 集群是否已配置（配了 url 才算启用）。 */
    public boolean configured() {
        String url = settings.getOpenSearch().getUrl();
        return url != null && !url.isBlank();
    }

    public String indexName() {
        String index = settings.getOpenSearch().getIndex();
        return index == null || index.isBlank() ? "pm-ai-chunks" : index;
    }

    private String url() {
        String url = settings.getOpenSearch().getUrl();
        if (url == null || url.isBlank()) {
            throw new VecClient.VecException(
                    "OpenSearch 未配置（ai.open-search.url / OPENSEARCH_URL 为空）："
                            + "集群部署前请保持 VEC_BACKEND=local");
        }
        return url.replaceAll("/+$", "");
    }

    /** 一行切片：写入与查询共用的字段。 */
    public record ChunkDoc(String id, String docId, String filename, int pageNo, String text, float[] vector) {
    }

    /**
     * kNN 命中的一行。
     *
     * <p>{@code score} 来自 OpenSearch 的 {@code _score}：索引用 {@code space_type=cosinesimil}，
     * 所以它与 local 后端的余弦分同一量纲（这一点<b>未实测</b>，集群起来后要对着 local 的 top-k 核）。
     */
    public record SearchHit(String chunkKey, String docId, String filename, int pageNo, double score, String text) {
    }

    // ══════════════════════════════════════════════════════════════
    // 建索引
    // ══════════════════════════════════════════════════════════════

    /**
     * 建索引（已存在则原样返回，不报错）。
     *
     * @param dimension 向量维度，取<b>实际</b>维度（平台默认 4096；本部署不支持 MRL 降维）
     */
    public void ensureIndex(int dimension) {
        int dim = dimension > 0 ? dimension : 4096;
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("settings").put("index.knn", true);
        ObjectNode vector = body.putObject("mappings").putObject("properties").putObject("vector");
        vector.put("type", "knn_vector");
        vector.put("dimension", dim);
        vector.put("space_type", "cosinesimil");     // 与 local 后端的余弦对齐
        vector.putObject("method").put("name", "hnsw").put("engine", "lucene").put("space_type", "cosinesimil");

        ObjectNode properties = (ObjectNode) body.path("mappings").path("properties");
        properties.putObject("doc_id").put("type", "keyword");
        properties.putObject("filename").put("type", "text");
        properties.putObject("page_no").put("type", "integer");
        properties.putObject("text").put("type", "text");
        properties.putObject("chunk_key").put("type", "keyword");

        send("PUT", "/" + indexName(), body, 60);
    }

    // ══════════════════════════════════════════════════════════════
    // 写入（幂等 upsert）
    // ══════════════════════════════════════════════════════════════

    /** 单条写入：{@code _id} = 切片键。 */
    public void upsert(ChunkDoc chunk) {
        upsertAll(List.of(chunk));
    }

    /**
     * 批量写入：{@code POST /<index>/_bulk}。
     *
     * <p>NDJSON 三段式：{@code {"index":{"_index":...,"_id":...}}} / 文档 / 同样的两行再来一条……
     * <b>每行末尾都必须有 {@code \n}，最后一行也不例外</b>（少了会被 OpenSearch 判为非法 NDJSON）。
     */
    public void upsertAll(List<ChunkDoc> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (ChunkDoc chunk : chunks) {
            ObjectNode action = MAPPER.createObjectNode();
            ObjectNode meta = action.putObject("index");
            meta.put("_index", indexName());
            meta.put("_id", chunk.id());
            ndjson.append(action).append('\n');
            ndjson.append(toDocument(chunk)).append('\n');
        }
        sendRaw("POST", "/" + indexName() + "/_bulk", ndjson.toString(), "application/x-ndjson", 300);
    }

    private ObjectNode toDocument(ChunkDoc chunk) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("doc_id", chunk.docId());
        doc.put("filename", chunk.filename());
        doc.put("page_no", chunk.pageNo());
        doc.put("text", chunk.text());
        doc.put("chunk_key", chunk.id());
        ArrayNode vector = doc.putArray("vector");
        for (float value : chunk.vector()) {
            vector.add(value);
        }
        return doc;
    }

    // ══════════════════════════════════════════════════════════════
    // kNN 查询
    // ══════════════════════════════════════════════════════════════

    /**
     * kNN 查询，返回带页码的命中（页码是来源标注的前提，缺了整条链路就白做）。
     *
     * <p>查询体：{@code {"size":k,"query":{"knn":{"vector":{"vector":[...],"k":k}}}}}。
     */
    public List<SearchHit> search(float[] queryVector, int k) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", k);
        ObjectNode knn = body.putObject("query").putObject("knn").putObject("vector");
        ArrayNode vector = knn.putArray("vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        knn.put("k", k);

        JsonNode response = send("POST", "/" + indexName() + "/_search", body, 120);
        List<SearchHit> hits = new ArrayList<>();
        JsonNode rows = response.path("hits").path("hits");
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode source = row.path("_source");
                hits.add(new SearchHit(
                        source.path("chunk_key").asText(""),
                        source.path("doc_id").asText(""),
                        source.path("filename").asText(""),
                        source.path("page_no").asInt(0),
                        row.path("_score").asDouble(0.0),
                        source.path("text").asText("")));
            }
        }
        return hits;
    }

    /**
     * 索引是否已建好（{@code HEAD /<index>}）：用于"首次检索时确保索引存在"。
     *
     * <p>只看 HTTP 200/404：
     * <ul>
     *   <li>200 → 已存在；</li>
     *   <li>404 → 尚未创建（<b>不是错误</b>，随后 {@link #ensureIndex} 会建）；</li>
     *   <li>其余（401 鉴权、5xx 集群异常、连不上）→ 抛 {@link VecClient.VecException}，
     *       由 {@code RetrievalService} 显式冒泡——<b>不静默退回 local</b>。</li>
     * </ul>
     */
    public void ensureIndexOnce(int dimension) {
        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url() + "/" + indexName()))
                            .timeout(Duration.ofSeconds(15))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VecClient.VecException("OpenSearch 请求被中断：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new VecClient.VecException("集群不可达（" + url() + "）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        if (response.statusCode() == 200) {
            return;
        }
        if (response.statusCode() == 404) {
            ensureIndex(dimension);
            return;
        }
        throw new VecClient.VecException("集群返回 HTTP " + response.statusCode() + "（HEAD " + indexName() + "）："
                + (response.body() == null ? "" : response.body()));
    }

    /**
     * 幂等写路径：把文档库里的一组切片写进索引。
     *
     * <p>调用方（{@code RetrievalService}）负责算向量；这里只管"{@code _id} = 切片键"的 upsert 语义。
     *
     * @param dimension 实际向量维度（{@code VEC_EMBED_DIMENSIONS=0} 时取上游返回的长度，平台 4096）
     */
    public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension) {
        List<ChunkDoc> docs = toDocs(chunks, vectors);
        if (docs.isEmpty()) {
            return 0;
        }
        ensureIndexOnce(dimension);
        upsertAll(docs);
        return docs.size();
    }

    /**
     * 切片 + 向量 → 写入文档（{@code _id} = 切片键）。
     *
     * <p>⚠️ 这里按<b>下标</b> zip，所以调用方必须保证两个 List 一一对应。
     * {@code RetrievalService} 走的是"逐切片按切片键取向量"的写法（向量 Map 的迭代顺序没有保证，
     * 直接 zip {@code values()} 会让向量挂到别的切片上），本方法留给"手上本来就是并排数组"的调用方。
     */
    public List<ChunkDoc> toDocs(List<DocStore.Chunk> chunks, List<float[]> vectors) {
        List<ChunkDoc> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size() && i < vectors.size(); i++) {
            DocStore.Chunk chunk = chunks.get(i);
            docs.add(new ChunkDoc(
                    RetrievalService.chunkKey(chunk),
                    chunk.docId(),
                    chunk.filename(),
                    chunk.pageNo(),
                    chunk.text(),
                    vectors.get(i)));
        }
        return docs;
    }

    /**
     * 删除某个文档的全部切片（按 {@code doc_id} 条件删，用 {@code delete_by_query}）。
     *
     * <p>当前 {@code RetrievalService} <b>没调它</b>：文档删除后的陈旧切片检索时会因为
     * 切片键（含内容指纹）在本地文档库里找不到而被丢弃，不会污染结果；
     * 但索引会留下垃圾，量大时应接上（{@code DocStore.delete} 之后调用）。
     */
    public void deleteDoc(String docId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("query").putObject("term").put("doc_id", docId);
        send("POST", "/" + indexName() + "/_delete_by_query", body, 120);
    }

    // ══════════════════════════════════════════════════════════════
    // 探活
    // ══════════════════════════════════════════════════════════════

    /** 探活：{@code GET /_cluster/health}（轻量）。连不上/鉴权失败都抛 {@link VecClient.VecException}。 */
    public VecHealth health(double timeoutSeconds) {
        JsonNode response = send("GET", "/_cluster/health", null, timeoutSeconds);
        String status = response.path("status").asText("unknown");
        String cluster = response.path("cluster_name").asText("");
        return new VecHealth(true, "集群可达（cluster=" + cluster + ", status=" + status + "）", List.of());
    }

    // ══════════════════════════════════════════════════════════════
    // HTTP（懒创建连接，未启用时不产生任何副作用）
    // ══════════════════════════════════════════════════════════════

    private JsonNode send(String method, String path, JsonNode body, double timeoutSeconds) {
        try {
            return sendRaw(method, path, body == null ? null : MAPPER.writeValueAsString(body),
                    "application/json", timeoutSeconds);
        } catch (VecClient.VecException e) {
            throw e;
        } catch (Exception e) {
            throw new VecClient.VecException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private JsonNode sendRaw(String method, String path, String body, String contentType, double timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url() + path))
                .timeout(Duration.ofMillis(Math.max(1, (long) (timeoutSeconds * 1000))))
                .header("Content-Type", contentType);
        String username = settings.getOpenSearch().getUsername();
        String password = settings.getOpenSearch().getPassword();
        if (username != null && !username.isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            // 每次现建 client：未启用时完全不碰网络，也不持有连接池
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VecClient.VecException("OpenSearch 请求被中断：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new VecClient.VecException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        String text = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new VecClient.VecException("HTTP " + response.statusCode() + ": "
                    + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        try {
            return text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
        } catch (Exception e) {
            throw new VecClient.VecException("OpenSearch 响应不是合法 JSON：" + text, e);
        }
    }

    /** 供日志/排错：当前生效的配置摘要（不含密码）。 */
    public Map<String, Object> configSummary() {
        return Map.of(
                "enabled", configured(),
                "url", settings.getOpenSearch().getUrl(),
                "index", indexName(),
                "auth", settings.getOpenSearch().getUsername() == null
                        || settings.getOpenSearch().getUsername().isBlank() ? "未配置" : "已配置");
    }
}
