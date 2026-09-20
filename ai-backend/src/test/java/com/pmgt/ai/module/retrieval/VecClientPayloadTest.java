package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化/重排的<b>线上格式</b>（离线，不发请求）。
 *
 * <p>这两条是 Python 版真实调试出来的"踩坑结论"，也是最容易被"顺手重写得优雅一点"改坏的地方，
 * 所以在这里钉死：
 * <ol>
 *   <li><b>本平台部署不支持 MRL 降维</b>：{@code dimensions} 只在 {@code input} 形式下传，
 *       且默认（0）<b>不传</b>——传了会 HTTP 400 {@code does not support matryoshka representation}；</li>
 *   <li>带 instruction 时必须走 {@code messages}（一条 system + 一条 user），
 *       且<b>不传 {@code dimensions}</b>（手册只给了这两种组合）。</li>
 * </ol>
 */
class VecClientPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void plainInputOmitsDimensionsWhenZero() throws Exception {
        JsonNode payload = read(VecClient.Payloads.plainInput("Qwen3-VL-Embedding-8B", List.of("甲", "乙"), 0));

        assertEquals("Qwen3-VL-Embedding-8B", payload.get("model").asText());
        assertEquals(2, payload.get("input").size());
        assertFalse(payload.has("dimensions"),
                "dimensions=0 时绝不能带该参数：本部署传了直接 400（不支持 MRL 降维）");
    }

    @Test
    void plainInputCarriesDimensionsOnlyWhenConfigured() throws Exception {
        JsonNode payload = read(VecClient.Payloads.plainInput("m", List.of("甲"), 1024));
        assertEquals(1024, payload.get("dimensions").asInt());
    }

    @Test
    void instructionUsesMessagesWithoutDimensions() throws Exception {
        JsonNode payload = read(VecClient.Payloads.withInstruction("m", "Represent the user's input.", "甲"));

        assertFalse(payload.has("input"), "带指令只能走 messages 形式");
        assertFalse(payload.has("dimensions"), "messages 形式不传 dimensions（手册只给了两种组合）");
        JsonNode messages = payload.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("Represent the user's input.", messages.get(0).get("content").get(0).get("text").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("甲", messages.get(1).get("content").get(0).get("text").asText());
        assertEquals("text", messages.get(1).get("content").get(0).get("type").asText());
    }

    @Test
    void rerankPayloadKeepsDocumentOrder() throws Exception {
        JsonNode payload = read(VecClient.Payloads.rerank("Qwen3-VL-Reranker-8B", "查询", List.of("一", "二", "三")));

        assertEquals("查询", payload.get("query").asText());
        assertEquals(3, payload.get("documents").size());
        // 顺序必须与入参一致：上游返回的 index 就是这个数组的下标，错位就全错
        assertEquals("一", payload.get("documents").get(0).asText());
        assertEquals("三", payload.get("documents").get(2).asText());
    }

    @Test
    void cosineReturnsZeroOnDimensionMismatch() {
        VecClient client = new VecClient(new com.pmgt.ai.common.config.AiSettings());
        assertEquals(0.0, client.cosine(new float[] {1f, 0f}, new float[] {1f, 0f, 0f}),
                "维度不同返回 0（换部署后旧缓存与 query 长度不等时退化成 0 分，而不是抛异常）");
        assertEquals(0.0, client.cosine(new float[0], new float[0]));
        assertEquals(1.0, client.cosine(new float[] {1f, 2f}, new float[] {1f, 2f}), 1e-9);
        assertTrue(client.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}) < 1e-9);
    }

    private static JsonNode read(JsonNode node) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(node));
    }
}
