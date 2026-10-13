package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.module.store.DocStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * OpenSearch kNN 索引适配层（{@code VEC_BACKEND=opensearch} 时启用）。
 *
 * <h2>为什么用 JDK HttpClient + Jackson 原生 REST（而不是 OpenSearch Java Client）</h2>
 * <ol>
 *   <li><b>依赖面</b>：Java Client 会拉进 opensearch-java + Apache HttpClient 5 + transport 一堆传递依赖，
 *       而本项目要的只有 5 个 REST 动作（{@code PUT /<index>}、{@code HEAD /<index>}、{@code POST /_bulk}、
 *       {@code POST /_search}、{@code GET /_cluster/health}）。内网离线装机时"少一个依赖少一份风险"
 *       （与 {@code LlmClient}/{@code VecClient}/{@code PlatformOcrClient} 同一条纪律）；</li>
 *   <li><b>可控性</b>：查询体是**给人看的 JSON 字符串**，能直接贴进报告、与《OpenSearch部署与接入.md》
 *       §附录 E 的示例逐字对照；Client 的 Builder 会把结构藏起来，而"3.x 的 {@code knn} 下第一层是字段名"
 *       这类版本差异恰恰是最容易写错、也最需要肉眼核对的地方；</li>
 *   <li><b>错误语义</b>：本类要求"连不上/401/5xx 一律显式抛错并说清是哪个环节"，
 *       自己写 HTTP 出口比把 Client 的异常体系再映射一遍更短。</li>
 * </ol>
 *
 * <h2>索引自建（3.x 写法，见 docs/OpenSearch部署与接入.md 附录 E）</h2>
 * <ul>
 *   <li>{@code vector}: {@code knn_vector} / <b>4096 维</b> / {@code data_type: float} /
 *       {@code space_type: cosinesimil}（<b>写在字段层</b>，不混用 {@code method.space_type}）/
 *       HNSW {@code engine: lucene} / {@code method.parameters: {m:16, ef_construction:100}}
 *       （3.x 的键名是 {@code parameters}，<b>不是 {@code params}</b>）；</li>
 *   <li>{@code settings.index}: {@code knn:true} / {@code number_of_shards:1} / {@code number_of_replicas:0}
 *       （单节点上副本永远无法分配，只会让状态变 yellow 且白占一份盘）；
 *       {@code refresh_interval:1s}——文档示例给批量灌库用 {@code -1}，<b>我们刻意用 1s</b>：
 *       本服务既是写入方也是查询方（解析入库后立刻可能要检索），关掉 refresh 会让"刚写的切片查不到"；</li>
 *   <li><b>keyword 元数据字段</b>：{@code doc_id} / {@code attachment_id} / {@code project_id} /
 *       {@code phase_code} / {@code security_level} / {@code chunk_key}——它们是"<b>先过滤再检索</b>"的前提，
 *       也是把请求作用域做成<b>硬约束</b>的前提（kNN 的 {@code filter} 只能作用在 keyword/数值等可精确匹配字段上）。</li>
 * </ul>
 *
 * <h2>降级口径（硬要求，别改）</h2>
 * <p><b>绝不静默回退到 local 或关键词检索</b>：{@code backend=opensearch} 时 url 为空 / 集群不可达 /
 * 鉴权失败 / 索引状态异常，一律抛 {@link VecClient.VecException}，消息以
 * <b>"向量后端不可用"</b> 开头并带上具体环节（连不上 / HTTP 401 / mapping 冲突…）。
 * 宁可让使用者看到一条明确报错，也不要让他拿到一个"以为在用集群、其实在进程内算"的结果。
 *
 * <h2>幂等与断点续跑</h2>
 * <p>{@code _id} = 切片键 {@code (docId, pageNo, 内容 sha1 前 12 位)}：内容变了 id 就变（新切片），
 * 内容没变重复写入就是覆盖（幂等 upsert）。重建索引据此<b>按 chunk_key 跳过已存在的切片</b>
 * （见 {@link #existingChunkKeys(Collection)}），所以"可中断、可续跑"不需要额外的版本号或状态文件。
 */
@Component
public class OpenSearchIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认维度（平台 {@code Qwen3-VL-Embedding-8B} 固定输出 4096，且本部署不支持 MRL 降维）。 */
    public static final int DEFAULT_DIMENSION = 4096;

    /** HNSW 建图参数：{@code m=16}（官方默认）、{@code ef_construction=100}（官方默认，够用且建索引更快）。 */
    public static final int HNSW_M = 16;
    public static final int HNSW_EF_CONSTRUCTION = 100;

    /** {@code existingChunkKeys} 单次查询的 key 上限（terms 查询体不能无限大）。 */
    private static final int KEY_QUERY_LIMIT = 1000;

    /** 一致性/断点查询里 terms 聚合返回的最大桶数。 */
    private static final int TERMS_AGG_SIZE = 10000;

    /**
     * 配置。<b>刻意是 protected</b>：离线测试需要一个"能替换掉 HTTP 层"的替身，
     * 子类要能读到 url/index 名并覆写 {@link #health} / {@link #search}（见
     * {@code RetrievalServiceTest} 的 {@code StubIndex}）。生产代码不该用它做别的事。
     */
    protected final AiSettings settings;

    private final Machine machine;

    /**
     * 进程内<b>唯一</b>的 HTTP 出口：连接池与 keep-alive 因此才真正生效。
     *
     * <p>为什么必须共享（原来的写法是"每次请求现建一个 {@code HttpClient}"）：
     * 每次 {@code new} 都会新建自己的连接池与 selector 线程，请求结束后连接无法复用，
     * 于是每一次 {@code _search}/{@code _bulk} 都要重付一次 TCP 握手与线程创建。
     * 本服务一次问答会走 2 次 kNN 检索、一次导入会走多次 {@code _bulk}，这个开销是白付的。
     *
     * <p>三条刻意的配置：
     * <ol>
     *   <li>{@code HTTP_1_1}：OpenSearch 3.x 的 REST 接口默认是 HTTP/1.1，显式声明可省掉
     *       每次请求的 h2 升级尝试；</li>
     *   <li>{@code followRedirects(NEVER)}：集群 REST 不该有重定向，出现重定向属于配置错误，
     *       我们要的是明确报错而不是被悄悄引到别处；</li>
     *   <li>connectTimeout 取 {@code min(10, 单次请求超时)}：连不上要快速失败，
     *       读超时仍由每个请求的 {@code HttpRequest.timeout()} 单独控制。</li>
     * </ol>
     *
     * <p>与"未启用时完全不碰网络"的既有语义不冲突：本字段只在构造期创建对象，
     * <b>不发起任何请求</b>；真正的网络访问仍只在 {@code VEC_BACKEND=opensearch} 的调用路径上发生。
     */
    private final HttpClient http;

    public OpenSearchIndex(AiSettings settings) {
        this(settings, null);
    }

    @Autowired
    public OpenSearchIndex(AiSettings settings, MeterRegistry registry) {
        this.settings = settings;
        this.machine = new Machine(registry == null ? new SimpleMeterRegistry() : registry);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        Math.min(10, Math.max(1, (long) Math.ceil(timeoutSeconds())))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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

    public String backend() {
        return "opensearch";
    }

    private double timeoutSeconds() {
        double configured = settings.getOpenSearch().getTimeoutSeconds();
        return configured > 0 ? configured : 20.0;
    }

    private int bulkSize() {
        return Math.max(1, settings.getOpenSearch().getBulkSize());
    }

    private String url() {
        String url = settings.getOpenSearch().getUrl();
        if (url == null || url.isBlank()) {
            throw new VecClient.VecException(unavailable(
                    "OPENSEARCH_URL / ai.open-search.url 为空（没有可连的集群地址）"));
        }
        return url.replaceAll("/+$", "");
    }

    /** 报错前缀：一眼看出"是向量后端不可用"，而不是"没检索到内容"。 */
    private String unavailable(String reason) {
        return "向量后端不可用（VEC_BACKEND=opensearch，" + safeUrl() + "）：" + reason
                + "。不静默回退到 local 或关键词检索——请修好集群，或把 VEC_BACKEND 改回 local。";
    }

    /** 打印用 URL：<b>抹掉 userinfo</b>（口令绝不能出现在日志/异常里）。 */
    private String safeUrl() {
        String raw = settings.getOpenSearch().getUrl();
        if (raw == null || raw.isBlank()) {
            return "OPENSEARCH_URL 未配置";
        }
        return raw.replaceAll("//[^/@]*@", "//");
    }

    /**
     * 一行切片：写入与查询共用的字段。
     *
     * <p>{@code meta} 是 doc 级过滤元数据（项目/附件/密级…）；没有就传
     * {@link DocMeta#EMPTY}（缺字段就不写，查询侧按"不在作用域内"处理）。
     */
    public record ChunkDoc(String id, String docId, String filename, int pageNo, String text, float[] vector,
                           DocMeta meta) {

        /** 不带元数据的便捷构造（老调用点与测试用）。 */
        public ChunkDoc(String id, String docId, String filename, int pageNo, String text, float[] vector) {
            this(id, docId, filename, pageNo, text, vector, DocMeta.EMPTY);
        }
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

    /** 建索引请求体（3.x 写法；**单独抽出来是为了能离线断言 mapping**）。 */
    public ObjectNode indexDefinition(int dimension) {
        int dim = dimension > 0 ? dimension : DEFAULT_DIMENSION;
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode index = body.putObject("settings").putObject("index");
        index.put("knn", true);
        index.put("number_of_shards", 1);
        index.put("number_of_replicas", 0);
        // 本服务既是写入方也是查询方：关掉 refresh 会让"刚写完的切片查不到"，所以用 1s（文档示例给离线灌库用 -1）。
        index.put("refresh_interval", "1s");

        ObjectNode properties = body.putObject("mappings").putObject("properties");
        ObjectNode vector = properties.putObject("vector");
        vector.put("type", "knn_vector");
        vector.put("dimension", dim);
        vector.put("data_type", "float");
        // ⚠️ space_type 只在【字段层】写（3.x 两种写法都合法，混用会让人不知道以哪个为准）
        vector.put("space_type", "cosinesimil");
        ObjectNode method = vector.putObject("method");
        method.put("name", "hnsw");
        method.put("engine", "lucene");
        // 3.x 的键名是 parameters（不是 params）
        ObjectNode parameters = method.putObject("parameters");
        parameters.put("m", HNSW_M);
        parameters.put("ef_construction", HNSW_EF_CONSTRUCTION);

        properties.putObject("text").put("type", "text");
        ObjectNode filename = properties.putObject("filename");
        filename.put("type", "text");
        filename.putObject("fields").putObject("kw").put("type", "keyword");
        // ── 过滤前提：这些 keyword 字段是"先过滤再检索"与硬作用域的基础 ──
        properties.putObject("doc_id").put("type", "keyword");
        properties.putObject("attachment_id").put("type", "keyword");
        properties.putObject("project_id").put("type", "keyword");
        properties.putObject("phase_code").put("type", "keyword");
        properties.putObject("security_level").put("type", "keyword");
        properties.putObject("chunk_key").put("type", "keyword");
        properties.putObject("doc_type").put("type", "keyword");
        properties.putObject("source").put("type", "keyword");
        properties.putObject("page_no").put("type", "integer");
        properties.putObject("created_at").put("type", "date");
        return body;
    }

    /**
     * 建索引（已存在则原样返回，不报错——重启后第一次写入不该失败）。
     *
     * @param dimension 向量维度，取<b>实际</b>维度（平台默认 4096；本部署不支持 MRL 降维）
     */
    public void ensureIndex(int dimension) {
        try {
            send("PUT", "/" + indexName(), indexDefinition(dimension), timeoutSeconds());
        } catch (VecClient.VecException e) {
            // 已存在 = 幂等成功（并发启动/重建时两个进程同时建索引是正常情形）
            if (e.getMessage() != null && e.getMessage().contains("resource_already_exists_exception")) {
                return;
            }
            throw e;
        }
    }

    /**
     * 索引是否已建好（{@code HEAD /<index>}）：用于"首次写入/首次检索时确保索引存在"。
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
        withRetry("ensureIndex", () -> {
            HttpResponse<String> response = execute("HEAD", "/" + indexName(), null, "application/json",
                    timeoutSeconds(), false);
            if (response.statusCode() == 200) {
                return null;
            }
            if (response.statusCode() == 404) {
                ensureIndex(dimension);
                return null;
            }
            throw new VecClient.VecException(unavailable("建索引前探测失败（HEAD " + indexName()
                    + " 返回 HTTP " + response.statusCode() + "）" + snippet(response.body())));
        });
    }

    // ══════════════════════════════════════════════════════════════
    // 写入（幂等 upsert）
    // ══════════════════════════════════════════════════════════════

    /** 单条写入：{@code _id} = 切片键。 */
    public void upsert(ChunkDoc chunk) {
        upsertAll(List.of(chunk));
    }

    /** {@link #_bulk} 的结果统计（重建索引的进度与失败计数都基于它）。 */
    public record BulkResult(int written, int failed, List<String> firstErrors) {
    }

    /**
     * 批量写入：{@code POST /<index>/_bulk}（按 {@code OPENSEARCH_BULK_SIZE} 分批，默认 500）。
     *
     * <p>NDJSON：{@code {"index":{"_index":...,"_id":...}}} / 文档 / 同样的两行再来一条……
     * <b>每行末尾都必须有 {@code \n}，最后一行也不例外</b>（少了会被判为非法 NDJSON）。
     *
     * <p>幂等：{@code _id} = 切片键，同内容重写是覆盖（{@code _version} 递增、文档数不翻倍）。
     */
    public void upsertAll(List<ChunkDoc> chunks) {
        bulkUpsert(chunks);
    }

    /** 与 {@link #upsertAll} 同一实现，只是把统计结果返回给调用方（重建索引要它算进度）。 */
    public BulkResult bulkUpsert(List<ChunkDoc> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new BulkResult(0, 0, List.of());
        }
        int written = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += bulkSize()) {
            List<ChunkDoc> batch = chunks.subList(start, Math.min(start + bulkSize(), chunks.size()));
            BulkResult result = bulkBatch(batch);
            written += result.written();
            failed += result.failed();
            if (errors.size() < 3) {
                errors.addAll(result.firstErrors().subList(0, Math.min(3 - errors.size(), result.firstErrors().size())));
            }
        }
        return new BulkResult(written, failed, errors);
    }

    private BulkResult bulkBatch(List<ChunkDoc> batch) {
        StringBuilder ndjson = new StringBuilder();
        for (ChunkDoc chunk : batch) {
            ObjectNode action = MAPPER.createObjectNode();
            ObjectNode meta = action.putObject("index");
            meta.put("_index", indexName());
            meta.put("_id", chunk.id());
            ndjson.append(action).append('\n');
            ndjson.append(toDocument(chunk)).append('\n');
        }
        // _bulk 是幂等的（同 _id 覆盖），所以允许重试（仅连接/超时/5xx/429）
        JsonNode response = withRetry("bulk", () -> sendRaw("POST", "/" + indexName() + "/_bulk",
                ndjson.toString(), "application/x-ndjson", timeoutSeconds()));

        boolean hasErrors = response.path("errors").asBoolean(false);
        int written = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        JsonNode items = response.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                JsonNode outcome = item.path("index");
                int status = outcome.path("status").asInt(0);
                if (hasErrors && status >= 300) {
                    failed++;
                    if (errors.size() < 3) {
                        errors.add("_id=" + outcome.path("_id").asText("")
                                + " status=" + status + " " + snippet(outcome.path("error").toString()));
                    }
                } else {
                    written++;
                }
            }
        }
        if (hasErrors && failed > 0) {
            // 明确报错而不是"部分成功算成功"：批量里丢了切片会让检索静默少召回
            throw new VecClient.VecException(unavailable("_bulk 写入有 " + failed + " 条失败（共 "
                    + batch.size() + " 条）：" + errors));
        }
        return new BulkResult(written, failed, errors);
    }

    /** 一份索引文档（字段名与 mapping 一一对应；元数据缺失就不写该字段，查询侧按"不在作用域内"处理）。 */
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
        DocMeta meta = chunk.meta();
        if (meta != null && !meta.isEmpty()) {
            meta.sparseMap().forEach((field, value) -> doc.put(field, String.valueOf(value)));
        }
        return doc;
    }

    /**
     * 幂等写路径：把文档库里的一组切片写进索引（**不带元数据**，留给兼容老调用点）。
     *
     * @param dimension 实际向量维度（{@code VEC_EMBED_DIMENSIONS=0} 时取上游返回的长度，平台 4096）
     */
    public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension) {
        return indexChunks(chunks, vectors, dimension, Map.of());
    }

    /**
     * 幂等写路径（带 doc 级元数据）：解析入库（{@code DocIndexer}）与重建索引都走它。
     *
     * @param metaByDocId docId → 元数据（{@code attachment_id}/{@code project_id}/密级…）；
     *                    没有元数据的文档就不写这些字段——查询侧会把它当作"不在作用域内"
     */
    public int indexChunks(List<DocStore.Chunk> chunks, List<float[]> vectors, int dimension,
                           Map<String, DocMeta> metaByDocId) {
        List<ChunkDoc> docs = toDocs(chunks, vectors, metaByDocId);
        if (docs.isEmpty()) {
            return 0;
        }
        ensureIndexOnce(dimension);
        int written = 0;
        for (int start = 0; start < docs.size(); start += bulkSize()) {
            List<ChunkDoc> batch = docs.subList(start, Math.min(start + bulkSize(), docs.size()));
            written += bulkBatch(batch).written();
        }
        return written;
    }

    /**
     * 切片 + 向量 → 写入文档（{@code _id} = 切片键）。
     *
     * <p>⚠️ 这里按<b>下标</b> zip，所以调用方必须保证两个 List 一一对应。
     * {@code RetrievalService} 走的是"逐切片按切片键取向量"的写法（向量 Map 的迭代顺序没有保证，
     * 直接 zip {@code values()} 会让向量挂到别的切片上），本方法留给"手上本来就是并排数组"的调用方。
     */
    public List<ChunkDoc> toDocs(List<DocStore.Chunk> chunks, List<float[]> vectors) {
        return toDocs(chunks, vectors, Map.of());
    }

    /** 同上，但按 docId 补上过滤元数据。 */
    public List<ChunkDoc> toDocs(List<DocStore.Chunk> chunks, List<float[]> vectors,
                                 Map<String, DocMeta> metaByDocId) {
        List<ChunkDoc> docs = new ArrayList<>();
        Map<String, DocMeta> metas = metaByDocId == null ? Map.of() : metaByDocId;
        for (int i = 0; i < chunks.size() && i < vectors.size(); i++) {
            DocStore.Chunk chunk = chunks.get(i);
            docs.add(new ChunkDoc(
                    RetrievalService.chunkKey(chunk),
                    chunk.docId(),
                    chunk.filename(),
                    chunk.pageNo(),
                    chunk.text(),
                    vectors.get(i),
                    metas.getOrDefault(chunk.docId(), DocMeta.EMPTY)));
        }
        return docs;
    }

    // ══════════════════════════════════════════════════════════════
    // 断点续跑：已存在的切片键
    // ══════════════════════════════════════════════════════════════

    /**
     * 查询这些切片键里<b>已经存在于索引里</b>的那些（重建索引的断点依据）。
     *
     * <p>为什么按 chunk_key 而不是"按 doc_id 看有没有"：一个文档的切片是分批写入的，
     * 中途中断会留下"半份文档"，只按 doc_id 判断会把它当成"已完成"而永久漏掉后半段。
     * 切片键里含内容指纹，所以"存在即正确"。
     */
    public Set<String> existingChunkKeys(Collection<String> chunkKeys) {
        Set<String> out = new LinkedHashSet<>();
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return out;
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(chunkKeys));
        for (int start = 0; start < keys.size(); start += KEY_QUERY_LIMIT) {
            List<String> batch = keys.subList(start, Math.min(start + KEY_QUERY_LIMIT, keys.size()));
            ObjectNode body = MAPPER.createObjectNode();
            body.put("size", 0);
            ArrayNode terms = body.putObject("query").putObject("terms").putArray("chunk_key");
            batch.forEach(terms::add);
            ObjectNode agg = body.putObject("aggs").putObject("keys").putObject("terms");
            agg.put("field", "chunk_key");
            agg.put("size", batch.size());
            JsonNode response = withRetry("existingChunkKeys",
                    () -> send("POST", "/" + indexName() + "/_search", body, timeoutSeconds()));
            for (JsonNode bucket : response.path("aggregations").path("keys").path("buckets")) {
                out.add(bucket.path("key").asText(""));
            }
        }
        return out;
    }

    /** 每个 {@code doc_id} 在索引里的切片数（进度上报与"跳过了几份文档"的统计用）。 */
    public Map<String, Long> chunkCountsByDoc() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", 0);
        ObjectNode agg = body.putObject("aggs").putObject("docs").putObject("terms");
        agg.put("field", "doc_id");
        agg.put("size", TERMS_AGG_SIZE);
        JsonNode response = withRetry("countsByDoc",
                () -> send("POST", "/" + indexName() + "/_search", body, timeoutSeconds()));
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonNode bucket : response.path("aggregations").path("docs").path("buckets")) {
            out.put(bucket.path("key").asText(""), bucket.path("doc_count").asLong(0));
        }
        return out;
    }

    /** 索引里的切片总数（{@code _count}）。 */
    public long count() {
        JsonNode response = withRetry("count",
                () -> send("GET", "/" + indexName() + "/_count", null, timeoutSeconds()));
        return response.path("count").asLong(0);
    }

    /** 删除某个文档的全部切片（按 {@code doc_id} 条件删，用 {@code delete_by_query}）。 */
    public void deleteDoc(String docId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("query").putObject("term").put("doc_id", docId);
        withRetry("deleteByQuery",
                () -> send("POST", "/" + indexName() + "/_delete_by_query", body, timeoutSeconds()));
    }

    // ══════════════════════════════════════════════════════════════
    // kNN 查询
    // ══════════════════════════════════════════════════════════════

    /**
     * kNN 查询（不带作用域过滤）。
     *
     * <p>保留这个两参重载是为了兼容既有调用点与离线测试替身；
     * <b>生产路径请用 {@link #search(float[], int, SearchScope)}</b>——只有它能下推 filter。
     */
    public List<SearchHit> search(float[] queryVector, int k) {
        return search(queryVector, k, SearchScope.empty());
    }

    /**
     * kNN 查询（带<b>作用域硬过滤</b>）：{@code knn.vector.filter} 用请求作用域
     * （{@code doc_ids} / {@code attachmentIds} / {@code project_id}）在<b>检索前</b>收窄候选集。
     *
     * <p>"先过滤再检索"是权限合规的前提，也是效率前提：不过滤就等于"先在全库找最近邻、
     * 再在客户端丢掉越界的"——越界文档虽然不会返回，但它<b>占了 k 个候选位</b>，会把本该命中的内容挤出去。
     */
    public List<SearchHit> search(float[] queryVector, int k, SearchScope scope) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", k);
        ArrayNode source = body.putArray("_source");
        for (String field : List.of("chunk_key", "doc_id", "filename", "page_no", "text",
                "attachment_id", "project_id", "phase_code", "security_level", "doc_type", "source")) {
            source.add(field);
        }
        ObjectNode knn = body.putObject("query").putObject("knn").putObject("vector");
        ArrayNode vector = knn.putArray("vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        knn.put("k", k);
        ObjectNode filter = filterOf(scope);
        if (filter != null) {
            knn.set("filter", filter);
        }
        JsonNode response = withRetry("search",
                () -> send("POST", "/" + indexName() + "/_search", body, timeoutSeconds()));
        return toHits(response);
    }

    /**
     * hybrid（BM25 + kNN，两路 + 客户端按 chunk_key 融合）——<b>默认关闭</b>，见
     * {@link AiSettings.OpenSearch#isHybrid()} 的三条理由。
     *
     * <p>融合口径与本项目既有一致：<b>向量命中优先</b>，关键词命中补齐（不做分数归一，
     * 因为 BM25 与 cosinesimil 不同量纲，直接加权会系统性偏向分数量纲大的那一路）。
     */
    public List<SearchHit> search(float[] queryVector, String queryText, int k, SearchScope scope) {
        List<SearchHit> vectorHits = search(queryVector, k, scope);
        if (!settings.getOpenSearch().isHybrid() || queryText == null || queryText.isBlank()) {
            return vectorHits;
        }
        List<SearchHit> keywordHits = bm25Search(queryText, k, scope);
        Map<String, SearchHit> merged = new LinkedHashMap<>();
        for (SearchHit hit : vectorHits) {
            merged.putIfAbsent(hit.chunkKey(), hit);
        }
        for (SearchHit hit : keywordHits) {
            merged.putIfAbsent(hit.chunkKey(), hit);
        }
        return new ArrayList<>(merged.values());
    }

    /** BM25 那一路（同一套 filter）。 */
    public List<SearchHit> bm25Search(String queryText, int k, SearchScope scope) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", k);
        ObjectNode bool = body.putObject("query").putObject("bool");
        bool.putObject("must").putObject("match").put("text", queryText);
        ArrayNode clauses = clausesOf(scope);
        if (!clauses.isEmpty()) {
            bool.set("filter", clauses);
        }
        JsonNode response = withRetry("bm25",
                () -> send("POST", "/" + indexName() + "/_search", body, timeoutSeconds()));
        return toHits(response);
    }

    private List<SearchHit> toHits(JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        JsonNode rows = response.path("hits").path("hits");
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode src = row.path("_source");
                hits.add(new SearchHit(
                        src.path("chunk_key").asText(row.path("_id").asText("")),
                        src.path("doc_id").asText(""),
                        src.path("filename").asText(""),
                        src.path("page_no").asInt(0),
                        row.path("_score").asDouble(0.0),
                        src.path("text").asText("")));
            }
        }
        return hits;
    }

    /** 作用域 → {@code bool.filter} 子句；无约束返回 {@code null}（不加 filter 节点）。 */
    private ObjectNode filterOf(SearchScope scope) {
        ArrayNode clauses = clausesOf(scope);
        if (clauses.isEmpty()) {
            return null;
        }
        ObjectNode filter = MAPPER.createObjectNode();
        filter.putObject("bool").set("filter", clauses);
        return filter;
    }

    /**
     * 作用域 → terms 子句数组（四个维度各自一个 {@code terms} 子句，交给 bool.filter 做交集）。
     *
     * <p>密级是第四个维度：它与其余三维同一纪律——给了值就必须命中，
     * 文档缺 {@code security_level} 字段时不会被 terms 命中（缺失 ≠ 通配）。
     */
    private ArrayNode clausesOf(SearchScope scope) {
        ArrayNode clauses = MAPPER.createArrayNode();
        if (scope == null || !scope.constrained()) {
            return clauses;
        }
        addTerms(clauses, "doc_id", scope.docIds());
        addTerms(clauses, "attachment_id", scope.attachmentIds());
        addTerms(clauses, "project_id", scope.projectIds());
        addTerms(clauses, "security_level", scope.securityLevels());
        return clauses;
    }

    private static void addTerms(ArrayNode clauses, String field, Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ObjectNode clause = MAPPER.createObjectNode();
        ArrayNode terms = clause.putObject("terms").putArray(field);
        values.forEach(terms::add);
        clauses.add(clause);
    }

    // ══════════════════════════════════════════════════════════════
    // 探活
    // ══════════════════════════════════════════════════════════════

    /**
     * 探活：{@code GET /_cluster/health}（轻量）。连不上/鉴权失败都抛
     * {@link VecClient.VecException}（消息以"向量后端不可用"开头）。
     *
     * <p>调用点：{@code RetrievalService.checkBackend()}（守卫）与重建索引入口。
     */
    public VecHealth health(double timeoutSeconds) {
        return probe(timeoutSeconds, true);
    }

    /**
     * 探活（<b>不抛异常</b>）：给 {@code /health?with_vec=true} 用——自检页要如实显示
     * "不可用 + 原因"，而不是让整个 /health 500。
     */
    public VecHealth probeHealth(double timeoutSeconds) {
        return probe(timeoutSeconds, false);
    }

    private VecHealth probe(double timeoutSeconds, boolean throwOnFailure) {
        double timeout = timeoutSeconds > 0 ? timeoutSeconds : timeoutSeconds();
        try {
            JsonNode response = send("GET", "/_cluster/health", null, timeout);
            String status = response.path("status").asText("unknown");
            String cluster = response.path("cluster_name").asText("");
            int nodes = response.path("number_of_nodes").asInt(0);
            String detail = "集群可达（cluster=" + cluster + ", status=" + status
                    + ", nodes=" + nodes + ", index=" + indexName() + ", url=" + safeUrl() + "）";
            return new VecHealth(!"red".equals(status), detail, List.of());
        } catch (VecClient.VecException e) {
            if (throwOnFailure) {
                throw e;
            }
            return new VecHealth(false, e.getMessage(), List.of());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HTTP（懒创建连接，未启用时不产生任何副作用）
    // ══════════════════════════════════════════════════════════════

    /**
     * 幂等重试包装（P0-2：只对<b>幂等</b>调用重试——检索/建索引/批量写入/探活都是）。
     *
     * <p>为什么不用 {@code ModelRegistry} 的信号量：OpenSearch 不是"平台模型"，
     * 它的并发由集群自己管；这里只需要"重试 + 计数"，所以复用
     * {@link ModelRegistry#retryable(Throwable)} 的判据，指标单独一个
     * {@code ai.opensearch.retries}。backoff 与上限取 {@code ai.limits.retry-*}（与模型一致，运维只学一套）。
     */
    private <T> T withRetry(String operation, Supplier<T> body) {
        int maxAttempts = Math.max(1, settings.getLimits().getRetryMax() + 1);
        RuntimeException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return body.get();
            } catch (VecClient.VecException e) {
                last = e;
                if (attempt >= maxAttempts - 1 || !ModelRegistry.retryable(e)) {
                    throw e;
                }
                machine.retries.increment();
                sleepQuietly(backoffMillis(attempt));
            }
        }
        throw last == null ? new VecClient.VecException(unavailable(operation + " 失败")) : last;
    }

    private long backoffMillis(int attempt) {
        AiSettings.Limits limits = settings.getLimits();
        double base = limits.getRetryBackoffMs()
                * Math.pow(Math.max(1.0, limits.getRetryBackoffMultiplier()), Math.max(0, attempt));
        long jitter = limits.getRetryJitterMs() <= 0 ? 0 : (long) (Math.random() * limits.getRetryJitterMs());
        return Math.max(0, (long) base) + jitter;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VecClient.VecException("向量后端重试等待被中断", e);
        }
    }

    private JsonNode send(String method, String path, JsonNode body, double timeoutSeconds) {
        try {
            return sendRaw(method, path, body == null ? null : MAPPER.writeValueAsString(body),
                    "application/json", timeoutSeconds);
        } catch (VecClient.VecException e) {
            throw e;
        } catch (Exception e) {
            throw new VecClient.VecException(unavailable(e.getClass().getSimpleName() + ": " + e.getMessage()), e);
        }
    }

    private JsonNode sendRaw(String method, String path, String body, String contentType, double timeoutSeconds) {
        HttpResponse<String> response = execute(method, path, body, contentType, timeoutSeconds, true);
        String text = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 401/403 明说鉴权（口令只从环境变量读，这里不回显任何凭据）
            String hint = response.statusCode() == 401 || response.statusCode() == 403
                    ? "（鉴权失败：检查 OPENSEARCH_USER / OPENSEARCH_PASSWORD）" : "";
            throw new VecClient.VecException(unavailable("HTTP " + response.statusCode()
                    + "（" + method + " " + path + "）" + hint + " " + snippet(text)));
        }
        try {
            return text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
        } catch (Exception e) {
            throw new VecClient.VecException(unavailable("响应不是合法 JSON：" + snippet(text)), e);
        }
    }

    /** 统一出口：连接异常一律转成"向量后端不可用（连不上…）"。 */
    private HttpResponse<String> execute(String method, String path, String body, String contentType,
                                         double timeoutSeconds, boolean ignoredForCompatibility) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url() + path))
                .timeout(Duration.ofMillis(Math.max(1, (long) Math.ceil(timeoutSeconds * 1000))))
                .header("Content-Type", contentType)
                .header("Accept", "application/json");
        String username = settings.getOpenSearch().getUsername();
        String password = settings.getOpenSearch().getPassword();
        if (username != null && !username.isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            // 复用进程内唯一的 client（见字段注释：原来每次现建会丢掉连接池与 keep-alive）
            machine.requests.increment();
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VecClient.VecException(unavailable("请求被中断：" + e.getMessage()), e);
        } catch (Exception e) {
            throw new VecClient.VecException(unavailable("集群不可达（" + safeUrl() + "）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage()), e);
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    /** 供日志/排错：当前生效的配置摘要（<b>不含口令</b>）。 */
    public Map<String, Object> configSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", configured());
        out.put("url", safeUrl());
        out.put("index", indexName());
        out.put("timeout_seconds", timeoutSeconds());
        out.put("bulk_size", bulkSize());
        out.put("hybrid", settings.getOpenSearch().isHybrid());
        out.put("auth", settings.getOpenSearch().getUsername() == null
                || settings.getOpenSearch().getUsername().isBlank() ? "未配置" : "已配置");
        return out;
    }

    /** 指标挂载点（单独一个小对象，避免把 protected 的测试替身污染成"必须实现指标"）。 */
    private static final class Machine {
        private final Counter retries;
        private final Counter requests;

        private Machine(MeterRegistry registry) {
            this.retries = Counter.builder("ai.opensearch.retries")
                    .description("OpenSearch 幂等调用的重试次数")
                    .register(registry);
            // 出口请求数：与"每次现建 HttpClient"那个缺陷的验收口径配对——
            // 请求数增长而进程线程数不增长，才说明连接真的被复用了
            // （见 docs/RAG流水线设计与并发管理.md §6.1 α3 的验收指标）。
            this.requests = Counter.builder("ai.opensearch.requests")
                    .description("OpenSearch HTTP 出口请求数（连接复用后应只随请求数增长，不再新建连接）")
                    .register(registry);
        }
    }
}
