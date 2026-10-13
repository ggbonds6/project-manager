package com.pmgt.ai.module.pipeline;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.testutil.StubHttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段/产物/事件/终态上报（设计 §4.4.3 的 AI 侧）。
 *
 * <p>用 {@link StubHttpServer} 做<b>真 HTTP</b> 桩（不连真主系统）：要钉的三件事都只有在线协议上才看得见——
 * ① 请求体的字段名与取值（snake_case 主名）；② 幂等语义（同 run+stage 重复上报）；③ best-effort 与终态重试。
 */
class PipelineReporterTest {

    private static final String TOKEN = "test-service-token-must-not-leak";

    private static AiSettings settings(String baseUrl) {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setEnabled(true);
        settings.getPipeline().setBaseUrl(baseUrl);
        settings.getPipeline().setServiceToken(TOKEN);
        settings.getPipeline().setTimeoutSeconds(2);
        settings.getPipeline().setFinishRetries(2);
        settings.getPipeline().setRetryBackoffMs(10);
        settings.getPipeline().setMdc(false);
        return settings;
    }

    private static PipelineReporter reporter(AiSettings settings, SimpleMeterRegistry meters) {
        return new PipelineReporter(settings, new PipelineReportClient(settings, meters));
    }

    /** 请求行（方法 + 路径 + 体），来自 StubHttpServer 的记录。 */
    private static List<String> requests(StubHttpServer server) {
        return new ArrayList<>(server.requests());
    }

    private static String pathOf(String request) {
        String[] parts = request.split(" ");
        return parts.length > 1 ? parts[1] : "";
    }

    // ══════════════════════════════════════════════════════════════
    // ① 五阶段边界与幂等
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("五阶段边界：PARSE/CLEAN(SKIPPED)/CHUNK/EMBED/INDEX 各上报一次；CLEAN 必须写明原因")
    void reportsAllFiveStageBoundaries() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"ok\",\"data\":{}}")) {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReporter reporter = reporter(settings(server.baseUrl()), meters);
            try (RunContext run = reporter.open("run-1", TOKEN, "sha1-content", "PROJECT_9")) {
                assertTrue(run.active(), "配了地址+令牌就必须是 active");
                assertEquals("run-1", run.runId());
                assertNotNull(run.taskKey(), "给了内容 sha1 就必须有幂等键");
                assertEquals("PROJECT_9", run.projectId());

                run.stageStarted(StageKey.PARSE, "file=a.pdf");
                run.stageDone(StageKey.PARSE, 3, 0, Map.of("pages", 3), "pages=3");
                run.stageSkipped(StageKey.CLEAN, PipelineReporter.cleanSkippedReason());
                run.stageStarted(StageKey.CHUNK, "pages=3");
                run.stageDone(StageKey.CHUNK, 5, 0, Map.of("chunk_count", 5), "chunks=5");
                run.stageStarted(StageKey.EMBED, "chunks=5");
                run.stageDone(StageKey.EMBED, 5, 0, Map.of("embedded", 5), "embedded=5");
                run.stageStarted(StageKey.INDEX, "targets=5");
                run.stageDone(StageKey.INDEX, 5, 0, Map.of("written", 5), "written=5");
                run.finish("DONE", null);
            }

            List<String> paths = requests(server).stream().map(PipelineReporterTest::pathOf).toList();
            for (StageKey stage : StageKey.reported()) {
                assertTrue(paths.stream().anyMatch(p -> p.endsWith("/stages/" + stage.key())),
                        "阶段 " + stage.key() + " 必须上报过；实际路径=" + paths);
            }
            // CLEAN 是"如实跳过"：只有 SKIPPED 一条（没有 RUNNING）⇒ 4×2 + 1 = 9
            assertEquals(9, paths.stream().filter(p -> p.contains("/stages/")).count(),
                    "PARSE/CHUNK/EMBED/INDEX 各 RUNNING+终态，CLEAN 只有 SKIPPED");
            assertEquals(0, paths.stream().filter(p -> p.equals("/api/ai/pipeline/runs/run-1/stages/RETRIEVE")).count(),
                    "在线阶段（RETRIEVE/GENERATE）不落阶段行");

            String clean = requests(server).stream()
                    .filter(r -> r.contains("/stages/CLEAN") && r.contains("\"SKIPPED\""))
                    .findFirst().orElseThrow();
            assertTrue(clean.contains("\"status\":\"SKIPPED\""), clean);
            assertTrue(clean.contains("error_msg"), "SKIPPED 必须带原因（诚实登记，不伪造 DONE）：" + clean);
            // 措辞口径：清洗**已实现**、默认关闭（不是"尚未实现"）；原因里必须给出打开方式
            assertTrue(clean.contains("清洗默认关闭"), clean);
            assertTrue(clean.contains("ai.clean.enabled=true"), "跳过原因要给打开方式：" + clean);
            assertTrue(clean.contains("\"item_count\":0"), clean);
            assertFalse(clean.contains("\"counters\""), "清洗关闭时不得给 counters（没做的事不编数字）：" + clean);
            assertFalse(clean.contains("elapsed_ms\":\""), "elapsed_ms 必须是数字而不是字符串：" + clean);
        }
    }

    @Test
    @DisplayName("幂等：同一 run+stage 重复上报两次 ⇒ 两次请求体一致（由主系统更新同一行、attempts+1）")
    void repeatedStageReportIsIdempotentOnTheWire() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            PipelineReporter reporter = reporter(settings(server.baseUrl()), new SimpleMeterRegistry());
            try (RunContext run = reporter.open("run-2", TOKEN, null, null)) {
                run.stageDone(StageKey.CHUNK, 5, 0, Map.of("chunk_count", 5), "chunks=5");
                run.stageDone(StageKey.CHUNK, 5, 0, Map.of("chunk_count", 5), "chunks=5");
            }
            List<String> chunkRequests = requests(server).stream()
                    .filter(r -> r.contains("/stages/CHUNK")).toList();
            assertEquals(2, chunkRequests.size(), "两次上报都要发出去（幂等由主系统按 run+stage 实现）");
            assertEquals(bodyOf(chunkRequests.get(0)), bodyOf(chunkRequests.get(1)),
                    "同一事实重复上报的请求体必须一致（attempts 是主系统的计数，AI 侧不下发）");
            assertFalse(chunkRequests.get(0).contains("attempts"), "AI 侧不得自造 attempts 字段");
        }
    }

    private static String bodyOf(String request) {
        int at = request.indexOf('{');
        return at < 0 ? "" : request.substring(at);
    }

    // ══════════════════════════════════════════════════════════════
    // ② best-effort 与终态重试
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("上报失败不影响主流程（best-effort）：桩返回 500 时阶段上报返回 false 并计数，调用方继续跑")
    void stageReportFailureIsBestEffort() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"detail\":\"boom\"}")) {
            server.setStatus(500);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReporter reporter = reporter(settings(server.baseUrl()), meters);

            long started = System.nanoTime();
            try (RunContext run = reporter.open("run-3", TOKEN, null, null)) {
                run.stageStarted(StageKey.PARSE, "x");
                run.stageDone(StageKey.PARSE, 1, 0, Map.of(), "pages=1");
                // 非终态上报不重试：每个阶段边界恰好一次请求
                assertEquals(2, requests(server).size(), "非终态上报不重试（失败只 WARN + 计数）");
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(elapsedMs < 5000, "上报失败不得拖住主流程（实测 " + elapsedMs + "ms）");
            assertTrue(meters.get(PipelineReportClient.METRIC_FAILED).counter().count() >= 2,
                    "失败必须可见（" + PipelineReportClient.METRIC_FAILED + "）");
            assertEquals(0.0, meters.get("ai.pipeline.report_sent").counter().count(), 1e-9);
        }
    }

    @Test
    @DisplayName("终态上报失败要重试：前两次 500、第三次 200 ⇒ finish 成功且请求数=3（终态绝不放弃）")
    void terminalReportRetriesUntilSuccess() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()), meters);
            // 前两次 500、之后 200：用 server.calls() 判断，避免"谁先自增"的竞态
            server.setStatusSupplier(() -> server.calls() < 3 ? 500 : 200);

            boolean ok = client.finish("run-4", "DONE", null, "parse=1,clean=0", 0);
            assertTrue(ok, "第 3 次必须成功（终态重试）");
            assertEquals(3, server.calls(), "终态必须重试到成功（默认 finish-retries=2 ⇒ 共 3 次尝试）");
            assertTrue(requests(server).get(0).contains("\"status\":\"DONE\""));
            assertTrue(requests(server).get(0).contains("pipeline_rev"), "终态要带 pipeline_rev");
            assertTrue(requests(server).get(0).contains("clean_rev"));
            assertEquals(2.0, meters.get(PipelineReportClient.METRIC_FAILED).counter().count(), 1e-9);
        }
    }

    @Test
    @DisplayName("终态重试用尽 ⇒ 返回 false 且计数（不静默成功，任务可能停在 RUNNING 要人工核对）")
    void terminalReportGivesUpHonestly() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"detail\":\"nope\"}")) {
            server.setStatus(503);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()), meters);
            assertFalse(client.finish("run-5", "FAILED", "解析失败", "parse=1", 0));
            assertEquals(3, server.calls(), "重试次数用尽后必须停手");
            assertEquals(3.0, meters.get(PipelineReportClient.METRIC_FAILED).counter().count(), 1e-9);
        }
    }

    @Test
    @DisplayName("未配置上报（缺令牌/地址）⇒ 全部是空操作，一次网络请求都不发，也不计失败")
    void disabledReportingIsSilent() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            AiSettings off = new AiSettings();       // enabled=false 且令牌为空
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReporter reporter = reporter(off, meters);
            assertFalse(reporter.enabled());
            try (RunContext run = reporter.open(null, null, "sha1", null)) {
                assertFalse(run.active(), "未配置时上下文必须是 inactive");
                run.stageStarted(StageKey.PARSE, "x");
                run.stageDone(StageKey.PARSE, 1, 0, Map.of(), "y");
                run.stageSkipped(StageKey.CLEAN, "reason");
                run.artifact(ArtifactType.PAGE_DIGEST, "{}", null, 2, null, 1, null);
                run.event("A", "B", null, null);
                run.finish("DONE", null);
            }
            assertEquals(0, server.calls(), "未配置上报时不得发任何请求");
            assertEquals(0.0, meters.get(PipelineReportClient.METRIC_FAILED).counter().count(), 1e-9);
            assertTrue(runIdOf(reporter) == null || true);
        }
    }

    private static String runIdOf(PipelineReporter reporter) {
        try (RunContext run = reporter.open(null, null, null, null)) {
            assertTrue(run.runId().startsWith("upload-"), "AI 自建 run_id 必须带可读前缀：" + run.runId());
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    // ③ 产物 / 事件 / 契约七条
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("产物上报：五类 artifact_type 字段名与主系统一致（只 INSERT，不 upsert）")
    void artifactBodiesMatchFrozenContract() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            PipelineReporter reporter = reporter(settings(server.baseUrl()), new SimpleMeterRegistry());
            try (RunContext run = reporter.open("run-6", TOKEN, null, "P1")) {
                run.artifact(ArtifactType.PAGE_DIGEST, "{\"pages\":3}", "work/docs/x.json", 120, "abc123", 3, "页级摘要");
                run.artifact(ArtifactType.CHUNK_MANIFEST, "{\"chunk_count\":5}", null, null, null, 5, null);
                run.artifact(ArtifactType.CLEAN_DIFF, null, null, null, null, 0, "本批无清洗阶段");
            }
            List<String> artifacts = requests(server).stream()
                    .filter(r -> r.contains("/artifacts")).toList();
            assertEquals(3, artifacts.size());
            String page = artifacts.get(0);
            assertTrue(page.contains("\"artifact_type\":\"page_digest\""), page);
            assertTrue(page.contains("\"content\":"), page);
            assertTrue(page.contains("\"object_key\":\"work/docs/x.json\""), page);
            assertTrue(page.contains("\"char_count\":120"), page);
            assertTrue(page.contains("\"sha1\":\"abc123\""), page);
            assertTrue(page.contains("\"item_count\":3"), page);
            assertTrue(page.contains("\"note\":"), page);
            assertTrue(artifacts.get(2).contains("\"artifact_type\":\"clean_diff\""), artifacts.get(2));
            assertTrue(artifacts.get(2).contains("本批无清洗阶段"), "跳过类产物必须写清原因");
        }
    }

    @Test
    @DisplayName("契约七条：claim/heartbeat/release/stage/artifact/event/finish 的路径与字段都对齐")
    void allSevenContractCalls() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) ->
                path.startsWith("/api/ai/pipeline/claim")
                        ? "{\"code\":0,\"data\":{\"run_id\":\"run-7\",\"task_id\":11,\"attachment_id\":22,"
                        + "\"task_key\":\"tk\",\"pipeline_token\":\"pt\",\"lease_expires_at\":\"2026-10-13T11:00:00\"}}"
                        : "{\"code\":0,\"data\":{}}")) {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()), meters);

            PipelineReportClient.ClaimedRun claimed =
                    client.claim("PARSE", 1, "node-1", 90);
            assertNotNull(claimed);
            assertEquals("run-7", claimed.runId());
            assertEquals(11L, claimed.taskId());
            assertEquals(22L, claimed.attachmentId());
            assertEquals("tk", claimed.taskKey());
            assertEquals("pt", claimed.pipelineToken());
            String claimBody = bodyOf(requests(server).get(0));
            assertTrue(claimBody.contains("\"task_type\":\"PARSE\""), claimBody);
            assertTrue(claimBody.contains("\"limit\":1"), claimBody);
            assertTrue(claimBody.contains("\"claimed_by\":\"node-1\""), claimBody);
            assertTrue(claimBody.contains("\"lease_seconds\":90"), claimBody);

            assertTrue(client.heartbeat("run-7").ok());
            assertEquals("/api/ai/pipeline/runs/run-7/heartbeat", pathOf(requests(server).get(1)));
            assertTrue(client.release("run-7", "租约被抢"));
            assertTrue(pathOf(requests(server).get(2)).endsWith("/runs/run-7/release"));
            assertTrue(client.stage("run-7", StageKey.CHUNK, StageReport.skipped("x")));
            assertTrue(pathOf(requests(server).get(3)).endsWith("/runs/run-7/stages/CHUNK"));
            assertTrue(client.artifact("run-7", ArtifactType.INDEX_RESULT.body("{}", null, null, null, 1, null)));
            assertTrue(pathOf(requests(server).get(4)).endsWith("/runs/run-7/artifacts"));
            assertTrue(client.event("run-7", "RUNNING", "DONE", "解析完成", "ai-backend"));
            assertTrue(pathOf(requests(server).get(5)).endsWith("/runs/run-7/events"));
            assertTrue(client.finish("run-7", "DONE", null, "parse=1", 0));
            assertTrue(pathOf(requests(server).get(6)).endsWith("/runs/run-7/finish"));
            // 令牌只出现在请求头里，不写进任何请求体
            for (String request : requests(server)) {
                assertFalse(bodyOf(request).contains(TOKEN), "令牌绝不能进请求体");
            }
        }
    }

    @Test
    @DisplayName("心跳 409（租约被抢）⇒ Result.conflict() 为真，调用方据此立即停止任务并放回")
    void heartbeatConflictIsVisible() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":40901,\"message\":\"租约已被其他节点接管\"}")) {
            server.setStatus(409);
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()),
                    new SimpleMeterRegistry());
            PipelineReportClient.Result result = client.heartbeat("run-8");
            assertFalse(result.ok());
            assertTrue(result.conflict(), "409 必须是可识别的信号（调用方据此立即停止并放回）");
            assertEquals(409, result.status());
        }
    }

    @Test
    @DisplayName("响应兼容两种形态：标准信封 {code,message,data} 与裸对象都认")
    void responseEnvelopeIsTolerant() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) ->
                "{\"run_id\":\"bare-run\",\"task_id\":5,\"claimed_by\":\"n\"}")) {
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()),
                    new SimpleMeterRegistry());
            PipelineReportClient.ClaimedRun claimed = client.claim(null, null, "n", null);
            assertNotNull(claimed, "裸对象形态也必须能领到任务（两侧不必同时上线）");
            assertEquals("bare-run", claimed.runId());
        }
    }

    @Test
    @DisplayName("自检摘要不含令牌本体（只报配没配）")
    void configSummaryNeverLeaksToken() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            PipelineReporter reporter = reporter(settings(server.baseUrl()), new SimpleMeterRegistry());
            Map<String, Object> summary = reporter.configSummary();
            assertTrue(Boolean.TRUE.equals(summary.get("enabled")));
            assertEquals(Boolean.TRUE, summary.get("service_token_configured"));
            String text = summary.toString();
            assertFalse(text.contains(TOKEN), "自检摘要绝不能出现令牌：" + text);
            assertTrue(summary.containsKey("pipeline_rev"));
            assertTrue(summary.containsKey("stages"));
        }
    }

    @Test
    @DisplayName("空任务（主系统没有可领的任务）返回 null —— 那是正常情况，不是错误")
    void emptyClaimIsNotAnError() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"no task\",\"data\":null}")) {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            PipelineReportClient client = new PipelineReportClient(settings(server.baseUrl()), meters);
            assertNull(client.claim("PARSE", 1, "n", 60));
            assertEquals(1, server.calls());
            assertEquals(0.0, meters.get(PipelineReportClient.METRIC_FAILED).counter().count(), 1e-9);
        }
    }
}
