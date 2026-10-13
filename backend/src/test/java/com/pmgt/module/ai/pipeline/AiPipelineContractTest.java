package com.pmgt.module.ai.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.module.ai.pipeline.dto.ArtifactContentVO;
import com.pmgt.module.ai.pipeline.dto.ClaimRequest;
import com.pmgt.module.ai.pipeline.dto.ClaimVO;
import com.pmgt.module.ai.pipeline.dto.EventReportRequest;
import com.pmgt.module.ai.pipeline.dto.FinishRequest;
import com.pmgt.module.ai.pipeline.dto.PipelineRunDetailVO;
import com.pmgt.module.ai.pipeline.dto.PipelineStageVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两侧契约的字段名测试（<b>snake_case 主名 + camelCase 别名</b>）。
 *
 * <p>为什么值得单独测：字段名错了不会抛异常，只会<b>静默变成 null</b>——
 * AI 侧读 `run_id` 拿到 null 就会以为"没领到任务"，前端读 `stage_key` 拿到 undefined
 * 就会画出一条空白阶段条。这正是 ai-1.1.0 修过的 `doc_ids`/`docIds` 那类缺陷
 * （契约写 snake_case、Java 字段是 camelCase，发出来被静默忽略）。
 * 这里把"两个名字都在、值一样"钉死。
 */
class AiPipelineContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void claim响应同时给snake_case主名与camelCase别名() throws Exception {
        ClaimVO vo = new ClaimVO();
        vo.setRunId("run-12");
        vo.setTaskId(12L);
        vo.setAttachmentId(34L);
        vo.setTaskKey("abc");
        vo.setPipelineToken("eyJhbGciOiJIUzI1NiJ9.fake.token");
        vo.setLeaseExpiresAt("2026-10-13 10:00:00");
        vo.setAttempts(1);
        vo.setHeartbeatSeconds(30);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(vo));

        assertEquals("run-12", json.get("run_id").asText());
        assertEquals("run-12", json.get("runId").asText(), "camelCase 别名必须在（前端用 camelCase）");
        assertEquals(json.get("run_id").asText(), json.get("runId").asText());
        assertTrue(json.has("task_key") && json.has("taskKey"));
        assertTrue(json.has("attachment_id") && json.has("attachmentId"));
        assertTrue(json.has("pipeline_token") && json.has("pipelineToken"));
        assertTrue(json.has("lease_expires_at") && json.has("leaseExpiresAt"));
        assertTrue(json.has("heartbeat_seconds") && json.has("heartbeatSeconds"));
    }

    @Test
    void 详情与嵌套阶段都带别名() throws Exception {
        PipelineStageVO stage = new PipelineStageVO();
        stage.setStageKey("PARSE");
        stage.setStageName("识别");
        stage.setItemCount(20);
        stage.setItemFailed(0);
        stage.setDisplayStatus("STALE");
        stage.setStale(true);

        PipelineRunDetailVO detail = new PipelineRunDetailVO();
        detail.setRunId("run-12");
        detail.setStageKey("PARSE");
        detail.setStageDone(20);
        detail.setClaimExpiresAt("2026-10-13 10:00:00");
        detail.setStages(List.of(stage));
        detail.setStaleStageKeys(List.of("PARSE"));
        detail.setProjectionNote("阶段 PARSE 仍为 RUNNING 且已超过 300 秒没有任何上报");
        detail.setStaleAfterSeconds(300);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(detail));

        assertTrue(json.has("projection_note") && json.has("projectionNote"));
        assertTrue(json.has("stale_stage_keys") && json.has("staleStageKeys"));
        assertTrue(json.has("stale_after_seconds") && json.has("staleAfterSeconds"));
        JsonNode nested = json.get("stages").get(0);
        assertEquals("PARSE", nested.get("stage_key").asText());
        assertEquals("PARSE", nested.get("stageKey").asText());
        assertTrue(nested.has("item_count") && nested.has("itemCount"));
        assertTrue(nested.has("display_status") && nested.has("displayStatus"));
        assertTrue(nested.has("stage_name") && nested.has("stageName"));
    }

    @Test
    void 请求两种字段名都能解析() throws Exception {
        ClaimRequest snake = mapper.readValue(
                "{\"task_type\":\"RETRY\",\"limit\":1,\"claimed_by\":\"ai-1\",\"lease_seconds\":90}",
                ClaimRequest.class);
        assertEquals("RETRY", snake.getTaskType());
        assertEquals(1, snake.getLimit());
        assertEquals("ai-1", snake.getClaimedBy());
        assertEquals(90, snake.getLeaseSeconds());

        ClaimRequest camel = mapper.readValue(
                "{\"taskType\":\"RETRY\",\"limit\":1,\"claimedBy\":\"ai-1\",\"leaseSeconds\":90}",
                ClaimRequest.class);
        assertEquals("RETRY", camel.getTaskType(), "camelCase 别名必须也能收（否则静默丢参）");
        assertEquals("ai-1", camel.getClaimedBy());
        assertEquals(90, camel.getLeaseSeconds());
    }

    @Test
    void 阶段产物与终态报文两种字段名都能解析() throws Exception {
        var stage = mapper.readValue(
                "{\"status\":\"PARTIAL\",\"item_count\":20,\"item_failed\":2,\"elapsed_ms\":1234,"
                        + "\"error_msg\":\"第 3 页 OCR 失败\",\"input_digest\":\"20 页\"}",
                com.pmgt.module.ai.pipeline.dto.StageReportRequest.class);
        assertEquals("PARTIAL", stage.getStatus());
        assertEquals(20, stage.getItemCount());
        assertEquals(2, stage.getItemFailed());
        assertEquals(1234L, stage.getElapsedMs());
        assertEquals("第 3 页 OCR 失败", stage.getErrorMsg());

        var finish = mapper.readValue("{\"status\":\"DONE\",\"docId\":\"doc-1\",\"cleanRev\":1}",
                FinishRequest.class);
        assertEquals("DONE", finish.getStatus());
        assertEquals("doc-1", finish.getDocId());
        assertEquals(1, finish.getCleanRev());

        var event = mapper.readValue("{\"fromStatus\":\"RUNNING\",\"to_status\":\"DONE\"}",
                EventReportRequest.class);
        assertEquals("RUNNING", event.getFromStatus());
        assertEquals("DONE", event.getToStatus());
    }

    @Test
    void 产物内容的截断说明只在被截断时出现() throws Exception {
        ArtifactContentVO vo = new ArtifactContentVO();
        vo.setArtifactType("chunk_manifest");
        vo.setCharCount(4000L);
        vo.setContentChars(4000);
        vo.setTruncated(false);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(vo));

        assertTrue(json.has("artifact_type") && json.has("artifactType"));
        assertTrue(json.has("content_chars") && json.has("contentChars"));
        assertTrue(json.has("char_count") && json.has("charCount"));
        assertFalse(json.get("truncated").asBoolean());
    }
}
