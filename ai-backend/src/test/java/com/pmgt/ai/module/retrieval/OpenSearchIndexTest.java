package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.testutil.StubHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenSearch 适配层（{@link OpenSearchIndex}）：mapping、{@code _bulk} 幂等、kNN {@code filter}、
 * 断点查询、不可达时明确报错。
 *
 * <p><b>全程离线</b>：用一个本机 {@link StubHttpServer} 当"假集群"，<b>不连</b>
 * {@code 10.254.212.110:9200}（真机验证由人工按《OpenSearch部署与接入.md》步骤 19 做）。
 * 桩里维护一个 {@code _id → 文档} 的 Map，于是"同 chunk_key 覆盖不重复"是**真的**幂等语义，
 * 而不是只断言"发了一次请求"。
 */
class OpenSearchIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AiSettings settings(String url) {
        AiSettings settings = new AiSettings();
        settings.getOpenSearch().setUrl(url);
        settings.getOpenSearch().setUsername("admin");
        settings.getOpenSearch().setPassword("stub-secret");
        return settings;
    }

    // ── ① mapping 生成正确 ────────────────────────────────────────

    @Test
    @DisplayName("索引定义：4096/cosinesimil/lucene/HNSW(16,100)/float/1 分片 0 副本 + keyword 元数据字段齐全")
    void indexDefinitionIsThreeXStyle() throws Exception {
        OpenSearchIndex index = new OpenSearchIndex(settings("http://stub:9200"));

        JsonNode body = index.indexDefinition(0);
        JsonNode indexSettings = body.path("settings").path("index");
        assertEquals(1, indexSettings.path("number_of_shards").asInt());
        assertEquals(0, indexSettings.path("number_of_replicas").asInt());
        assertTrue(indexSettings.path("knn").asBoolean());

        JsonNode vector = body.path("mappings").path("properties").path("vector");
        assertEquals("knn_vector", vector.path("type").asText());
        assertEquals(4096, vector.path("dimension").asInt());
        assertEquals("float", vector.path("data_type").asText());
        // 3.x：space_type 只在字段层（不在 method 里重复），method.parameters 而不是 method.params
        assertEquals("cosinesimil", vector.path("space_type").asText());
        assertFalse(vector.path("method").has("space_type"),
                "space_type 只在字段层写，避免两种写法混用");
        assertEquals("hnsw", vector.path("method").path("name").asText());
        assertEquals("lucene", vector.path("method").path("engine").asText());
        assertEquals(16, vector.path("method").path("parameters").path("m").asInt());
        assertEquals(100, vector.path("method").path("parameters").path("ef_construction").asInt());
        assertFalse(vector.path("method").has("params"), "3.x 的键名是 parameters");

        JsonNode properties = body.path("mappings").path("properties");
        // "先过滤再检索"的前提：这些 keyword 字段一个都不能少
        for (String field : List.of("doc_id", "attachment_id", "project_id", "phase_code",
                "security_level", "chunk_key")) {
            assertEquals("keyword", properties.path(field).path("type").asText(),
                    "过滤字段必须是 keyword：" + field);
        }
        assertEquals("text", properties.path("text").path("type").asText());
        assertEquals("keyword", properties.path("filename").path("fields").path("kw").path("type").asText());
        assertEquals("integer", properties.path("page_no").path("type").asText());
        assertEquals("date", properties.path("created_at").path("type").asText());
    }

    @Test
    @DisplayName("建索引走 PUT /<index>；索引已存在（400 resource_already_exists）视为成功")
    void ensureIndexIsIdempotent() throws Exception {
        List<String> paths = new ArrayList<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            paths.add(method + " " + path);
            if (method.equals("PUT")) {
                return "{\"error\":{\"type\":\"resource_already_exists_exception\"},\"status\":400}";
            }
            return "{}";
        })) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            // 桩固定返回 400（resource_already_exists），但"已存在"必须被当成成功（重启后第一次写入不该失败）
            index.ensureIndex(4096);
        }
        assertTrue(paths.stream().anyMatch(p -> p.startsWith("PUT /pm-ai-chunks")),
                "建索引是 PUT /<index>：" + paths);
    }

    // ── ③ _bulk 幂等：同一 chunk_key 覆盖不重复 ──────────────────

    @Test
    @DisplayName("_bulk：同一 chunk_key 写两遍是覆盖（_version 递增、文档数不翻倍）")
    void bulkUpsertIsIdempotentByChunkKey() throws Exception {
        Map<String, String> store = new LinkedHashMap<>();      // _id -> 文档 JSON
        Map<String, Integer> versions = new LinkedHashMap<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            if (path.startsWith("/pm-ai-chunks/_bulk")) {
                StringBuilder items = new StringBuilder();
                String[] lines = body.split("\n");
                for (int i = 0; i + 1 < lines.length; i += 2) {
                    JsonNode action = MAPPER.readTree(lines[i]).path("index");
                    String id = action.path("_id").asText();
                    store.put(id, lines[i + 1]);
                    versions.merge(id, 1, Integer::sum);
                    items.append("{\"index\":{\"_index\":\"pm-ai-chunks\",\"_id\":\"").append(id)
                            .append("\",\"_version\":").append(versions.get(id))
                            .append(",\"status\":").append(versions.get(id) == 1 ? 201 : 200).append("}},");
                }
                String body2 = items.length() == 0 ? "" : items.substring(0, items.length() - 1);
                return "{\"took\":1,\"errors\":false,\"items\":[" + body2 + "]}";
            }
            return "{}";
        })) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            float[] vector = {1f, 0f, 0f};
            OpenSearchIndex.ChunkDoc doc = new OpenSearchIndex.ChunkDoc(
                    "d1:1:abcd1234ef56", "d1", "a.pdf", 1, "合同金额一百万元", vector);

            index.upsertAll(List.of(doc));
            index.upsertAll(List.of(doc));

            assertEquals(1, store.size(), "同一 chunk_key 必须只留一份文档（幂等覆盖）");
            assertEquals(2, versions.get("d1:1:abcd1234ef56"), "_version 递增说明是覆盖而不是新增");
        }
    }

    @Test
    @DisplayName("_bulk 分批：超过 OPENSEARCH_BULK_SIZE 时拆成多次请求")
    void bulkSplitsByConfiguredBatchSize() throws Exception {
        List<String> bulkBodies = new ArrayList<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            if (path.startsWith("/pm-ai-chunks/_bulk")) {
                bulkBodies.add(body);
                int lines = body.split("\n").length;
                StringBuilder items = new StringBuilder();
                for (int i = 0; i < lines / 2; i++) {
                    items.append("{\"index\":{\"status\":201}},");
                }
                return "{\"errors\":false,\"items\":[" + items.substring(0, items.length() - 1) + "]}";
            }
            return "{}";
        })) {
            AiSettings settings = settings(server.baseUrl());
            settings.getOpenSearch().setBulkSize(2);
            OpenSearchIndex index = new OpenSearchIndex(settings);
            List<OpenSearchIndex.ChunkDoc> docs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                docs.add(new OpenSearchIndex.ChunkDoc("k" + i, "d1", "a.pdf", i + 1, "文本" + i, new float[] {1f}));
            }
            OpenSearchIndex.BulkResult result = index.bulkUpsert(docs);
            assertEquals(3, bulkBodies.size(), "5 条 / 每批 2 条 = 3 批");
            assertEquals(5, result.written());
            assertEquals(0, result.failed());
        }
    }

    // ── ④ 查询带 filter：作用域下推 ──────────────────────────────

    @Test
    @DisplayName("kNN 查询体：3.x 结构（knn.<字段名>）+ 请求作用域下推成 bool.filter 的 terms")
    void knnQueryCarriesScopeFilter() throws Exception {
        List<String> bodies = new ArrayList<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            if (path.startsWith("/pm-ai-chunks/_search")) {
                bodies.add(body);
                // 桩自己按 filter 过滤：模拟"先过滤再检索"，于是越界文档不可能出现在结果里
                JsonNode request = MAPPER.readTree(body);
                JsonNode clauses = request.path("query").path("knn").path("vector").path("filter")
                        .path("bool").path("filter");
                boolean allowed = true;
                for (JsonNode clause : clauses) {
                    JsonNode terms = clause.path("terms");
                    if (terms.has("doc_id") && !contains(terms.path("doc_id"), "in-scope")) {
                        allowed = false;
                    }
                    if (terms.has("project_id") && !contains(terms.path("project_id"), "P55")) {
                        allowed = false;
                    }
                }
                if (!allowed) {
                    return "{\"hits\":{\"hits\":[]}}";
                }
                return "{\"hits\":{\"hits\":[{\"_score\":0.9,\"_source\":{\"chunk_key\":\"in-scope:1:x\","
                        + "\"doc_id\":\"in-scope\",\"filename\":\"a.pdf\",\"page_no\":1,\"text\":\"命中\"}}]}}";
            }
            return "{}";
        })) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            SearchScope scope = SearchScope.of(List.of("in-scope"), List.of("8842"), List.of("P55"));
            List<OpenSearchIndex.SearchHit> hits = index.search(new float[] {1f, 0f}, 5, scope);

            assertEquals(1, hits.size());
            assertEquals("in-scope", hits.get(0).docId());

            JsonNode request = MAPPER.readTree(bodies.get(0));
            JsonNode knn = request.path("query").path("knn").path("vector");
            // 3.x：knn 下面第一层是【字段名】vector
            assertTrue(request.path("query").path("knn").has("vector"), "knn 下第一层必须是字段名");
            assertEquals(5, knn.path("k").asInt());
            assertEquals(2, knn.path("vector").size());
            assertEquals(5, request.path("size").asInt());
            JsonNode clauses = knn.path("filter").path("bool").path("filter");
            assertTrue(contains(clauses.get(0).path("terms").path("doc_id"), "in-scope"));
            assertTrue(contains(clauses.get(1).path("terms").path("attachment_id"), "8842"));
            assertTrue(contains(clauses.get(2).path("terms").path("project_id"), "P55"));
        }
    }

    @Test
    @DisplayName("无作用域时不加 filter；作用域外文档不可能被返回（桩按 filter 过滤）")
    void scopeFilterExcludesOutOfScopeDocuments() throws Exception {
        List<String> bodies = new ArrayList<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            bodies.add(body);
            JsonNode request = MAPPER.readTree(body);
            JsonNode filter = request.path("query").path("knn").path("vector").path("filter");
            if (filter.isMissingNode()) {
                // 没有 filter = 全库召回：桩照样返回两个文档（模拟"不过滤就会越界"）
                return "{\"hits\":{\"hits\":["
                        + "{\"_score\":0.9,\"_source\":{\"chunk_key\":\"in:1:x\",\"doc_id\":\"in\",\"filename\":\"a.pdf\",\"page_no\":1,\"text\":\"甲\"}},"
                        + "{\"_score\":0.8,\"_source\":{\"chunk_key\":\"out:1:y\",\"doc_id\":\"out\",\"filename\":\"b.pdf\",\"page_no\":1,\"text\":\"乙\"}}]}}";
            }
            return "{\"hits\":{\"hits\":[{\"_score\":0.9,\"_source\":{\"chunk_key\":\"in:1:x\","
                    + "\"doc_id\":\"in\",\"filename\":\"a.pdf\",\"page_no\":1,\"text\":\"甲\"}}]}}";
        })) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));

            assertEquals(2, index.search(new float[] {1f}, 5).size(),
                    "不传作用域时不加 filter（老行为：全库检索，再由调用方按文档库交集丢弃）");
            assertFalse(bodies.get(0).contains("\"filter\""));

            List<OpenSearchIndex.SearchHit> scoped =
                    index.search(new float[] {1f}, 5, SearchScope.ofDocIds(List.of("in")));
            assertEquals(1, scoped.size(), "带作用域时越界文档不可能出现在结果里");
            assertEquals("in", scoped.get(0).docId());
            assertTrue(bodies.get(1).contains("\"filter\""));
        }
    }

    // ── ⑤ 不可达 → 明确报错且不回退 ──────────────────────────────

    @Test
    @DisplayName("集群不可达：报错以'向量后端不可用'开头，且提示不静默回退")
    void unreachableClusterFailsLoudly() throws Exception {
        // 占一个端口再关掉：得到一个"确定拒绝连接"的地址
        int port;
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> "{}")) {
            port = server.baseUrl().lastIndexOf(':') > 0
                    ? Integer.parseInt(server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1)) : 0;
        }
        OpenSearchIndex index = new OpenSearchIndex(settings("http://127.0.0.1:" + port));

        VecClient.VecException error = assertThrows(VecClient.VecException.class, () -> index.health(2.0));
        assertTrue(error.getMessage().startsWith("向量后端不可用"), error.getMessage());
        assertTrue(error.getMessage().contains("不静默回退"), error.getMessage());

        VecClient.VecException search = assertThrows(VecClient.VecException.class,
                () -> index.search(new float[] {1f}, 5));
        assertTrue(search.getMessage().startsWith("向量后端不可用"), search.getMessage());
        // 口令绝不能出现在异常里
        assertFalse(search.getMessage().contains("stub-secret"), "异常消息里不能有口令");
    }

    @Test
    @DisplayName("鉴权失败（401）：报错点明是鉴权，且不回显口令")
    void unauthorizedMentionsAuthWithoutLeakingPassword() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> "{\"status\":401}")) {
            server.setStatus(401);
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            VecClient.VecException error = assertThrows(VecClient.VecException.class,
                    () -> index.search(new float[] {1f}, 5));
            assertTrue(error.getMessage().contains("401"), error.getMessage());
            assertTrue(error.getMessage().contains("OPENSEARCH_USER"), error.getMessage());
            assertFalse(error.getMessage().contains("stub-secret"), "异常消息里不能有口令");
        }
    }

    @Test
    @DisplayName("probeHealth 不抛异常（/health?with_vec=true 要如实显示原因，而不是整个 500）")
    void probeHealthNeverThrows() throws Exception {
        int port;
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> "{}")) {
            port = Integer.parseInt(server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
        }
        OpenSearchIndex index = new OpenSearchIndex(settings("http://127.0.0.1:" + port));
        VecHealth health = index.probeHealth(2.0);
        assertFalse(health.ok());
        assertTrue(health.detail().contains("向量后端不可用"), health.detail());
    }

    @Test
    @DisplayName("探活成功：detail 带 cluster/status/nodes/index，且不含口令")
    void healthReportsClusterFacts() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) ->
                "{\"cluster_name\":\"pm-os\",\"status\":\"green\",\"number_of_nodes\":1}")) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            VecHealth health = index.health(2.0);
            assertTrue(health.ok());
            assertTrue(health.detail().contains("pm-os"), health.detail());
            assertTrue(health.detail().contains("green"), health.detail());
            assertTrue(health.detail().contains("pm-ai-chunks"), health.detail());
            assertFalse(health.detail().contains("stub-secret"));
        }
    }

    // ── ⑥ 断点续跑：已存在的切片键 ────────────────────────────────

    @Test
    @DisplayName("existingChunkKeys：按 chunk_key 查已存在的切片（续跑依据），分片请求")
    void existingChunkKeysQueriesByChunkKey() throws Exception {
        List<String> bodies = new ArrayList<>();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            bodies.add(body);
            JsonNode request = MAPPER.readTree(body);
            List<String> asked = new ArrayList<>();
            request.path("query").path("terms").path("chunk_key").forEach(node -> asked.add(node.asText()));
            StringBuilder buckets = new StringBuilder();
            for (String key : asked) {
                if (key.endsWith(":exists")) {
                    buckets.append("{\"key\":\"").append(key).append("\",\"doc_count\":1},");
                }
            }
            if (buckets.length() == 0) {
                return "{\"aggregations\":{\"keys\":{\"buckets\":[]}}}";
            }
            return "{\"aggregations\":{\"keys\":{\"buckets\":["
                    + buckets.substring(0, buckets.length() - 1) + "]}}}";
        })) {
            OpenSearchIndex index = new OpenSearchIndex(settings(server.baseUrl()));
            var existing = index.existingChunkKeys(List.of("a:1:exists", "b:1:missing"));
            assertEquals(1, existing.size());
            assertTrue(existing.contains("a:1:exists"));

            JsonNode request = MAPPER.readTree(bodies.get(0));
            assertEquals(0, request.path("size").asInt(), "断点查询只要聚合，不要 hits");
            assertEquals("chunk_key", request.path("aggs").path("keys").path("terms").path("field").asText());
        }
    }

    @Test
    @DisplayName("configSummary：只报'口令配没配'，不出现口令与 URL 里的 userinfo")
    void configSummaryNeverLeaksPassword() {
        AiSettings settings = new AiSettings();
        settings.getOpenSearch().setUrl("http://admin:stub-secret@10.254.212.110:9200");
        settings.getOpenSearch().setPassword("stub-secret");
        OpenSearchIndex index = new OpenSearchIndex(settings);

        String summary = index.configSummary().toString();
        assertFalse(summary.contains("stub-secret"), "摘要里不能有口令");
        assertTrue(summary.contains("已配置"));
        assertNotNull(index.indexName());
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }
}
