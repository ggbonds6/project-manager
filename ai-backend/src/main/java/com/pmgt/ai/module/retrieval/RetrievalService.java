package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索链路：向量召回 + 关键词召回 → 去重融合 → Reranker 精排。
 *
 * <p>手册：{@code Qwen3-VL-Embedding-Reranker调用手册.md} §5（召回 50~100 条 → 重排取 Top 5~10）。
 *
 * <p>三步：
 * <ol>
 *   <li><b>召回</b>：向量（Qwen3-VL-Embedding-8B）与关键词（字符 2-gram + IDF，不引分词库）各取一批；</li>
 *   <li><b>融合</b>：按 {@code (docId, pageNo, 文本 sha1 前 12 位)} 去重——向量命中的优先（语义召回），
 *       关键词命中补齐（各取 recall 与 recall/2）；</li>
 *   <li><b>精排</b>：Reranker（Qwen3-VL-Reranker-8B）逐对打分，取 topK。</li>
 * </ol>
 *
 * <p>三条设计约束（来自《知识库总体架构与演进路线》）：
 * <ul>
 *   <li>向量与索引都是<b>可重建物</b>，所以缓存在 {@code workDir/vectors/} 下，删掉重算即可，不进主系统；</li>
 *   <li>索引后端可切：{@code local}（进程内余弦，当前默认、零部署）→ {@code opensearch}
 *       （<b>正式选型</b>，集群尚未部署，见 {@link OpenSearchIndex} 的"未实测"标注）；</li>
 *   <li>向量服务不可用时<b>退化为关键词检索</b>并在结果里写明原因。</li>
 * </ul>
 *
 * <p><b>为什么检索降级可以接受、OCR 降级不可以</b>（Python 版注释里的原话，必须保留）：
 * 检索降级后答案<b>仍然带页码来源</b>，召回只是变少——"没找到"是<b>可见的</b>；
 * 而 OCR 降级会产出"看起来对但实际编造"的文本，错误被藏在结果里。所以这里降级 + note 说明即可，
 * OCR 那边必须让失败页显式暴露 error。
 */
@Service
public class RetrievalService implements SearchPort {

    /** 单个检索片段返回给模型的最大字数——太长会白占上下文。 */
    public static final int MAX_SNIPPET_CHARS = 500;

    private static final int HARD_MAX_TOP_K = 10;

    /**
     * 高频但无区分度的二元组，降权用（没有分词库时的粗糙替代）。
     * 表内容与 Python {@code retrieval._STOP_BIGRAMS} 逐字一致。
     */
    private static final Set<String> STOP_BIGRAMS = Set.of(
            "的是", "了的", "和和", "在在", "有有", "我我", "你你", "他他",
            "什么", "怎么", "哪些", "哪个", "如何", "请问", "告诉");

    /**
     * 英文/数字整词：对应 Python 里的 {@code [a-z0-9][a-z0-9\-\.]*}。
     *
     * <p>中文走 2-gram，英文与编号（如 {@code ZB-2025-0821}）必须整体保留——
     * 拆成单字符会让"招标编号"这类精确检索失效。
     */
    private static final Pattern ENGLISH_TERM = Pattern.compile("[a-z0-9][a-z0-9\\-.]*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSettings settings;
    private final VecApi vec;
    private final DocStore docStore;
    private final OpenSearchIndex openSearchIndex;

    /** 注入用构造器；{@code openSearchIndex} 可以为 null（后端为 opensearch 时才会被用到）。 */
    @Autowired
    public RetrievalService(AiSettings settings, VecApi vec, OpenSearchIndex openSearchIndex) {
        this.settings = settings;
        this.vec = vec;
        this.docStore = DocStore.underWorkDir(settings.getWorkDir());
        this.openSearchIndex = openSearchIndex;
    }

    /** 测试用：显式指定文档库与索引适配层。 */
    public RetrievalService(AiSettings settings, VecApi vec, DocStore docStore, OpenSearchIndex openSearchIndex) {
        this.settings = settings;
        this.vec = vec;
        this.docStore = docStore;
        this.openSearchIndex = openSearchIndex;
    }

    // ══════════════════════════════════════════════════════════════
    // 对外：混合检索
    // ══════════════════════════════════════════════════════════════

    @Override
    public SearchResult search(String query, Integer topK, String docId) {
        return search(query, topK, docId, null);
    }

    /**
     * 混合检索：向量 + 关键词召回 → Reranker 精排 → topK。
     *
     * @param recall 覆盖召回应返回的条数；{@code null} 用配置（默认 50）
     */
    public SearchResult search(String query, Integer topK, String docId, Integer recall) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return new SearchResult(0, List.of(), "none", false, "查询为空");
        }

        // 配了未支持/未配置的后端就直接失败（不静默降级），错误由调用方暴露给使用者
        checkBackend();

        List<DocStore.Chunk> chunks = docStore.loadChunks(docId);
        if (chunks.isEmpty()) {
            return new SearchResult(0, List.of(), "none", false, "文档库里还没有切片（先上传并解析文档）");
        }

        int k = Math.max(1, Math.min(topK != null ? topK : settings.getRetrieval().getTopK(), HARD_MAX_TOP_K));
        int effectiveRecall = Math.max(k, recall != null ? recall : settings.getRetrieval().getRecall());

        Map<String, Double> kw = keywordScores(q, chunks);

        Map<String, Double> vecScore = new LinkedHashMap<>();
        String note = "";
        try {
            Map<String, float[]> vectors = embedChunks(chunks);
            List<float[]> queryVectors = vec.embedTexts(List.of(q), null);
            if (!queryVectors.isEmpty()) {
                float[] queryVector = queryVectors.get(0);
                for (DocStore.Chunk chunk : chunks) {
                    float[] vector = vectors.get(chunkKey(chunk));
                    if (vector != null) {
                        vecScore.put(chunkKey(chunk), vec.cosine(queryVector, vector));
                    }
                }
            }
        } catch (VecClient.VecException e) {
            note = "向量服务不可用（" + e.getMessage() + "），本次仅用关键词召回";
        }

        Map<String, DocStore.Chunk> byKey = new LinkedHashMap<>();
        for (DocStore.Chunk chunk : chunks) {
            byKey.putIfAbsent(chunkKey(chunk), chunk);
        }

        // 融合：向量榜（语义）在前，关键词榜（字面）补齐，去重靠 LinkedHashSet 的插入顺序
        Set<String> candidates = new LinkedHashSet<>(topKeys(vecScore, effectiveRecall));
        candidates.addAll(topKeys(kw, Math.max(1, effectiveRecall / 2)));

        if (candidates.isEmpty()) {
            return new SearchResult(
                    0,
                    List.of(),
                    vecScore.isEmpty() ? "keyword" : "hybrid",
                    false,
                    note.isEmpty() ? "没有检索到相关内容" : note);
        }

        boolean reranked = false;
        List<Map.Entry<String, Double>> order;
        List<String> candidateList = new ArrayList<>(candidates);
        try {
            List<String> texts = candidateList.stream().map(key -> byKey.get(key).text()).toList();
            List<RerankHit> hits = vec.rerank(q, texts, k);
            // ⚠️ hit.index() 是**入参下标**，不是排名——上游按原顺序返回 results（见 vec_client.py 注释）
            order = new ArrayList<>();
            for (RerankHit hit : hits) {
                if (hit.index() >= 0 && hit.index() < candidateList.size()) {
                    order.add(Map.entry(candidateList.get(hit.index()), hit.relevanceScore()));
                }
            }
            reranked = true;
        } catch (VecClient.VecException e) {
            String reason = "重排服务不可用（" + e.getMessage() + "），按召回分数排序";
            note = note.isEmpty() ? reason : note + "；" + reason;
            // 降级排序：向量分加权 2 倍 + 关键词分（与 Python 一致）
            List<String> fallback = candidateList.stream()
                    .sorted(Comparator.comparingDouble(
                            (String key) -> -(vecScore.getOrDefault(key, 0.0) * 2 + kw.getOrDefault(key, 0.0))))
                    .limit(k)
                    .toList();
            order = new ArrayList<>();
            for (String key : fallback) {
                order.add(Map.entry(key, vecScore.getOrDefault(key, 0.0)));
            }
        }

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : order) {
            if (hits.size() >= k) {
                break;
            }
            DocStore.Chunk chunk = byKey.get(entry.getKey());
            if (chunk == null) {
                continue;
            }
            String text = chunk.text();
            hits.add(new Hit(
                    chunk.docId(),
                    chunk.filename(),
                    chunk.pageNo(),
                    round(entry.getValue(), 4),
                    round(kw.getOrDefault(entry.getKey(), 0.0), 2),
                    round(vecScore.getOrDefault(entry.getKey(), 0.0), 4),
                    text.length() > MAX_SNIPPET_CHARS
                            ? text.substring(0, MAX_SNIPPET_CHARS) + "…"
                            : text));
        }

        return new SearchResult(
                hits.size(),
                hits,
                vecScore.isEmpty() ? "keyword" : "hybrid",
                reranked,
                note);
    }

    // ══════════════════════════════════════════════════════════════
    // 后端守卫
    // ══════════════════════════════════════════════════════════════

    /**
     * 索引后端守卫：只支持 {@code local}（进程内余弦 + JSON 缓存）；{@code opensearch} 必须
     * <b>配好且真的可用</b>，否则显式抛错。
     *
     * <p><b>为什么不静默退回 local</b>：那会把"以为在用集群"与"实际在进程内算"混在一起——
     * 数据规模、召回质量、运维方式完全不同，静默降级是最难排查的一类问题。宁可让使用者看到一条
     * 明确报错，也不要给他一个"看起来正常"的结果。
     *
     * <p>OpenSearch 是<b>正式选型</b>（集群尚未部署 → 适配层未实测，见 {@link OpenSearchIndex}）：
     * 配成 {@code opensearch} 而 {@code ai.open-search.url} 为空、或集群不可达，都在这里失败。
     */
    private void checkBackend() {
        String backend = settings.getRetrieval().getBackend();
        if (backend == null || backend.isBlank() || "local".equalsIgnoreCase(backend)) {
            return;
        }
        if (!"opensearch".equalsIgnoreCase(backend)) {
            throw new VecClient.VecException(
                    "VEC_BACKEND=" + backend + " 不支持（当前只支持 local 与 opensearch）；"
                            + "OpenSearch 的选型与字段设计见 docs/迁移方案与对照表.md");
        }
        String url = settings.getOpenSearch().getUrl();
        if (url == null || url.isBlank()) {
            throw new VecClient.VecException(
                    "VEC_BACKEND=opensearch 但 ai.open-search.url（OPENSEARCH_URL）未配置；"
                            + "不静默退回 local —— 集群部署后按 docs/迁移方案与对照表.md §5 的验证协议验证");
        }
        if (openSearchIndex == null) {
            throw new VecClient.VecException(
                    "VEC_BACKEND=opensearch 但 OpenSearch 适配层不可用（OpenSearchIndex 未装配）");
        }
        try {
            openSearchIndex.health(5.0);   // 连不上就抛，调用方得到明确错误
        } catch (VecClient.VecException e) {
            throw new VecClient.VecException(
                    "VEC_BACKEND=opensearch 但集群不可达（" + url + "）：" + e.getMessage()
                            + "；不静默退回 local");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 关键词召回（字符 2-gram + IDF，照搬 retrieval.py）
    // ══════════════════════════════════════════════════════════════

    /** 切片唯一键：文档 + 页 + <b>内容指纹</b>。 */
    public static String chunkKey(DocStore.Chunk chunk) {
        String digest = sha1Hex(chunk.text() == null ? "" : chunk.text()).substring(0, 12);
        return chunk.docId() + ":" + chunk.pageNo() + ":" + digest;
    }

    /**
     * 把查询拆成检索项：英文/数字取整词，中文取 2-gram。
     *
     * <p>为什么用 2-gram 而不是分词：中文不引分词库（内网离线装机省事），
     * 2-gram 对"付款条款""中标金额"这类术语足够有效。
     */
    static List<String> queryTerms(String query) {
        String q = query == null ? "" : query.strip().toLowerCase();
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = ENGLISH_TERM.matcher(q);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        String han = q.replaceAll("[^\\u4e00-\\u9fff]", "");
        if (han.length() == 1) {
            terms.add(han);
        }
        for (int i = 0; i + 1 < han.length(); i++) {
            String bigram = han.substring(i, i + 2);
            if (!STOP_BIGRAMS.contains(bigram)) {
                terms.add(bigram);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * 关键词打分（简化版 BM25），返回 {@code {切片键: 分数}}。
     *
     * <ul>
     *   <li>完整查询命中 → 高权重（12 分）；</li>
     *   <li>单个检索项命中 → 基础分 + 词频加成（{@code 1 + min(count,5)*0.25}）；</li>
     *   <li>用 IDF 压制"项目""合同"这类到处都是的词，突出稀有词：
     *       {@code idf = log(1 + n/(1+df))}。</li>
     * </ul>
     */
    Map<String, Double> keywordScores(String query, List<DocStore.Chunk> chunks) {
        List<String> terms = queryTerms(query);
        String raw = query == null ? "" : query.strip().toLowerCase();
        if (terms.isEmpty() && raw.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> df = new LinkedHashMap<>();
        for (String term : terms) {
            df.put(term, 0);
        }
        for (DocStore.Chunk chunk : chunks) {
            String low = chunk.text().toLowerCase();
            for (String term : terms) {
                if (low.contains(term)) {
                    df.merge(term, 1, Integer::sum);
                }
            }
        }
        int n = chunks.size();

        Map<String, Double> scores = new LinkedHashMap<>();
        for (DocStore.Chunk chunk : chunks) {
            String low = chunk.text().toLowerCase();
            double score = 0.0;
            if (!raw.isEmpty() && low.contains(raw)) {
                score += 12.0;
            }
            for (String term : terms) {
                int count = countOccurrences(low, term);
                if (count == 0) {
                    continue;
                }
                double idf = Math.log(1 + (double) n / (1 + df.getOrDefault(term, 0)));   // 稀有词权重高
                score += idf * (1 + Math.min(count, 5) * 0.25);
            }
            if (score > 0) {
                scores.put(chunkKey(chunk), score);
            }
        }
        return scores;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** 按分数降序取前 n 个键（同分按切片键稳定排序，避免每次检索顺序抖动）。 */
    private static List<String> topKeys(Map<String, Double> scores, int n) {
        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> -e.getValue())
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, n))
                .map(Map.Entry::getKey)
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    // 向量召回（含本地缓存）
    // ══════════════════════════════════════════════════════════════

    /**
     * 确保所有切片都有向量（缺的补算并落缓存），返回 {@code {切片键: 向量}}。
     *
     * <p>按文档分组缓存：一份文档的切片进了同一份 JSON，重算粒度就是"这份文档"。
     */
    Map<String, float[]> embedChunks(List<DocStore.Chunk> chunks) {
        Map<String, List<DocStore.Chunk>> byDoc = new LinkedHashMap<>();
        for (DocStore.Chunk chunk : chunks) {
            byDoc.computeIfAbsent(chunk.docId() == null ? "" : chunk.docId(), k -> new ArrayList<>()).add(chunk);
        }

        Map<String, float[]> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<DocStore.Chunk>> entry : byDoc.entrySet()) {
            String docId = entry.getKey();
            List<DocStore.Chunk> docChunks = entry.getValue();
            List<String> keys = docChunks.stream().map(RetrievalService::chunkKey).toList();
            Map<String, float[]> cached = loadVectors(docId, Set.copyOf(keys));
            out.putAll(cached);

            List<DocStore.Chunk> missing = new ArrayList<>();
            for (DocStore.Chunk chunk : docChunks) {
                if (!cached.containsKey(chunkKey(chunk))) {
                    missing.add(chunk);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            for (int start = 0; start < missing.size(); start += VecClient.EMBED_BATCH) {
                List<DocStore.Chunk> batch = missing.subList(start, Math.min(start + VecClient.EMBED_BATCH, missing.size()));
                List<float[]> vectors = vec.embedTexts(batch.stream().map(DocStore.Chunk::text).toList(), null);
                for (int i = 0; i < batch.size() && i < vectors.size(); i++) {
                    out.put(chunkKey(batch.get(i)), vectors.get(i));
                }
            }
            Map<String, float[]> toSave = new LinkedHashMap<>();
            for (String key : keys) {
                float[] vector = out.get(key);
                if (vector != null) {
                    toSave.put(key, vector);
                }
            }
            saveVectors(docId, toSave);
        }
        return out;
    }

    /** 向量缓存文件：{@code workDir/vectors/<doc_id>.json}。 */
    public Path vectorPath(String docId) {
        return settings.getWorkDir().resolve("vectors").resolve(docId + ".json");
    }

    /**
     * 读本地向量缓存。<b>模型名变了、或切片内容变了（键里的 sha1 变了）→ 视为失效。</b>
     *
     * <p>{@code VEC_EMBED_DIMENSIONS=0}（不传维度、用平台默认）时<b>不校验维度</b>：
     * 缓存里存的是实际维度，换部署导致维度变化会让余弦算不出来（长度不等 → 0 分），
     * 届时删掉 {@code workDir/vectors/} 重算即可（向量本来就是可重建物）。
     * 配了维度时反而要校验：维度对不上说明缓存来自另一套参数，直接用会算出一堆 0 分。
     *
     * <p>缓存文件损坏一样当作"没有缓存"——重算的代价远小于让检索失败。
     */
    Map<String, float[]> loadVectors(String docId, Set<String> keep) {
        Path path = vectorPath(docId);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            JsonNode payload = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (!settings.getVec().getEmbedModel().equals(payload.path("model").asText(null))) {
                return Map.of();
            }
            int configured = settings.getVec().getDimensions();
            if (configured > 0 && payload.path("dimensions").asInt(0) != configured) {
                return Map.of();
            }
            Map<String, float[]> out = new LinkedHashMap<>();
            JsonNode items = payload.path("items");
            if (items.isObject()) {
                items.fields().forEachRemaining(item -> {
                    if (keep.contains(item.getKey()) && item.getValue().isArray()) {
                        out.put(item.getKey(), toVector(item.getValue()));
                    }
                });
            }
            return out;
        } catch (Exception e) {
            // 缓存坏了直接重算，不影响功能
            return Map.of();
        }
    }

    /**
     * 写本地向量缓存。维度写<b>实际长度</b>（平台默认 4096，且不支持 MRL 降维）。
     *
     * <p>分量保留 6 位小数：4096 维 × 每切片一份 JSON，全精度浮点会让缓存体积翻倍，
     * 而 6 位对余弦排序毫无影响（本缓存只是过渡，正式向量归 OpenSearch）。
     * 写文件同样走"临时文件 + 原子替换"，避免并发检索读到写了一半的 JSON。
     */
    synchronized void saveVectors(String docId, Map<String, float[]> items) {
        Path path = vectorPath(docId);
        try {
            Files.createDirectories(path.getParent());
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", settings.getVec().getEmbedModel());
            int dims = items.isEmpty() ? 0 : items.values().iterator().next().length;
            payload.put("dimensions", dims);
            ObjectNode rows = payload.putObject("items");
            for (Map.Entry<String, float[]> item : new TreeMap<>(items).entrySet()) {
                var array = rows.putArray(item.getKey());
                for (float value : item.getValue()) {
                    array.add(round(value, 6));
                }
            }
            Path tmp = Files.createTempFile(path.getParent(), "vectors-", ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("向量缓存写入失败：" + path, e);
        }
    }

    private static float[] toVector(JsonNode array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) array.get(i).asDouble();
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════

    static String sha1Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-1（不可能发生）", e);
        }
    }

    /** 四舍五入到 n 位小数（结果里的分数都按这个精度对外，与 Python 的 round 对齐）。 */
    static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
