package com.pmgt.ai.module.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.gate.GateChannel;
import com.pmgt.ai.common.gate.GateResult;
import com.pmgt.ai.common.gate.PipelineGate;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.ocr.PdfInfo;
import com.pmgt.ai.module.ocr.PdfReader;
import com.pmgt.ai.module.pipeline.PipelineReportClient;
import com.pmgt.ai.module.pipeline.PipelineReporter;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.retrieval.DocMetaStore;
import com.pmgt.ai.module.retrieval.RerankHit;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.retrieval.VecApi;
import com.pmgt.ai.module.retrieval.VecHealth;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.testutil.StubHttpServer;
import com.pmgt.ai.testutil.TstRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析链路的<b>五阶段边界上报</b>与 <b>L1 准入</b>端到端（设计 §4 + §5 的接合处）。
 *
 * <p>用真 HTTP 桩当主系统、真 L1 闸门、真线程池、真切片/向量缓存（local 后端），
 * 所以这一条覆盖的是"从 {@code /upload-tasks} 提交到终态上报"的完整路径。
 * <b>不连任何真实集群或平台</b>。
 */
class UploadTaskStageReportTest {

    @TempDir
    Path tmp;

    private static final String TOKEN = "unit-test-token";

    /** 一次上传链路的装配（桩主系统 + 真闸门 + 真索引器）。 */
    private record Env(AiSettings settings, UploadTaskService service, ThreadPoolTaskExecutor executor,
                       PipelineGate gate, SimpleMeterRegistry meters, DocStore docs) {
    }

    private Env env(StubHttpServer server, java.util.function.Consumer<AiSettings.Gate> gateCustomizer)
            throws Exception {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("local");
        settings.getLimits().setBatchAcquireMs(3000);
        settings.getPipeline().setEnabled(true);
        settings.getPipeline().setBaseUrl(server.baseUrl());
        settings.getPipeline().setServiceToken(TOKEN);
        settings.getPipeline().setMdc(false);
        settings.getPipeline().setTimeoutSeconds(2);
        gateCustomizer.accept(settings.getGate());

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = new PipelineGate(settings, meters);
        PipelineReporter reporter = new PipelineReporter(settings,
                new PipelineReportClient(settings, meters));
        DocStore docs = new DocStore(tmp.resolve("docs"));
        DocMetaStore metaStore = new DocMetaStore(tmp);
        RetrievalService retrieval = new RetrievalService(settings, new StubVec(), docs, null,
                TstRegistry.l2(settings), meters, metaStore);
        DocIndexer indexer = new DocIndexer(settings, docs, retrieval, null, meters, metaStore);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("pm-ai-stage-test-");
        executor.initialize();

        UploadTaskService service = new UploadTaskService(settings, new ObjectMapper(),
                new StubReader(), new StubPdfReader(), docs, indexer, executor, meters, gate, reporter);
        return new Env(settings, service, executor, gate, meters, docs);
    }

    private Path upload(int pages) throws Exception {
        Path file = Files.createTempFile(tmp, "sample-", ".pdf");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            sb.append("第").append(i + 1).append("页：").append("甲".repeat(600)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static void awaitFinished(UploadTaskService service, UploadTask task) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = task.getStatus();
            if ("DONE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                    || "PARTIAL".equals(status)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("任务未在 15s 内结束，当前状态=" + task.getStatus() + " err=" + task.getError());
    }

    @Test
    @DisplayName("端到端：PARSE/CLEAN(SKIPPED)/CHUNK/EMBED/INDEX 五阶段都上报，终态 DONE 且 clean_rev/pipeline_rev 随体")
    void reportsFiveStagesEndToEnd() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"ok\",\"data\":{}}")) {
            Env env = env(server, g -> { });
            try {
                UploadTask task = env.service.submit(upload(2), "合同.pdf", 2048L, 150, "PROJECT_7", null);
                awaitFinished(env.service, task);
                assertEquals("DONE", task.getStatus(), "任务应成功：" + task.getError());
                assertNotNull(task.getDocId());
                assertTrue(server.calls() > 0, "配了上报就必须真的发过请求");
            } finally {
                env.executor.shutdown();
            }

            List<String> paths = server.requests().stream()
                    .map(r -> r.split(" ")[1]).toList();
            for (String stage : List.of("PARSE", "CLEAN", "CHUNK", "EMBED", "INDEX")) {
                assertTrue(paths.stream().anyMatch(p -> p.endsWith("/stages/" + stage)),
                        "阶段 " + stage + " 必须上报过；实际=" + paths);
            }
            assertTrue(paths.stream().anyMatch(p -> p.endsWith("/finish")), "必须有终态上报");

            String finish = server.requests().stream()
                    .filter(r -> r.contains("/finish")).findFirst().orElseThrow();
            assertTrue(finish.contains("\"status\":\"DONE\""), finish);
            assertTrue(finish.contains("pipeline_rev"), finish);
            assertTrue(finish.contains("\"clean_rev\":0"), finish);

            String clean = server.requests().stream()
                    .filter(r -> r.contains("/stages/CLEAN")).findFirst().orElseThrow();
            assertTrue(clean.contains("\"status\":\"SKIPPED\""), clean);
            assertTrue(clean.contains("清洗默认关闭"), "CLEAN=SKIPPED 必须写明原因：" + clean);
            assertFalse(clean.contains("\"counters\""),
                    "清洗关闭时 counters 必须为空（行为与接入前一致）：" + clean);

            String parse = server.requests().stream()
                    .filter(r -> r.contains("/stages/PARSE") && r.contains("\"DONE\"")).findFirst().orElseThrow();
            assertTrue(parse.contains("\"item_count\":2"), parse);
            assertTrue(parse.contains("empty_pages"), parse);

            List<String> artifactTypes = server.requests().stream()
                    .filter(r -> r.contains("/artifacts"))
                    .map(r -> {
                        int at = r.indexOf("\"artifact_type\":\"");
                        return r.substring(at + 17, r.indexOf('"', at + 17));
                    }).toList();
            assertTrue(artifactTypes.contains("page_digest"), artifactTypes.toString());
            assertTrue(artifactTypes.contains("clean_diff"), artifactTypes.toString());
            assertTrue(artifactTypes.contains("chunk_manifest"), artifactTypes.toString());
            assertTrue(artifactTypes.contains("embed_batch"), artifactTypes.toString());

            // 令牌只出现在请求头（不落请求体），且全程 run_id 一致
            for (String request : server.requests()) {
                assertFalse(request.substring(request.indexOf('{')).contains(TOKEN),
                        "令牌绝不能进请求体");
            }
        }
    }

    @Test
    @DisplayName("阶段上报全失败也不影响解析结果（best-effort）：任务照样 DONE，ai.pipeline.report_failed > 0")
    void reportingFailureDoesNotBreakParsing() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"detail\":\"主系统炸了\"}")) {
            server.setStatus(500);
            Env env = env(server, g -> { });
            try {
                UploadTask task = env.service.submit(upload(1), "合同.pdf", 1024L, 150, null, null);
                awaitFinished(env.service, task);
                assertEquals("DONE", task.getStatus(), "上报失败绝不能把解析判成失败：" + task.getError());
                assertNotNull(task.getDocId());
                assertTrue(env.docs.allDocs().iterator().hasNext(), "文档必须真的入库了");
                assertTrue(env.meters.get(PipelineReportClient.METRIC_FAILED).counter().count() > 0,
                        "上报失败要可见（" + PipelineReportClient.METRIC_FAILED + "）");
            } finally {
                env.executor.shutdown();
            }
        }
    }

    @Test
    @DisplayName("L1 队列满 ⇒ 提交即 429 诚实失败：带深度/上限/原因，任务标 FAILED，临时文件清掉，无永久 QUEUED")
    void l1QueueFullRejectsAtSubmit() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            // 闸门：并发 1、队列 0 ⇒ 第二个任务提交时必被拒
            Env env = env(server, g -> {
                g.getBatchParse().setMax(1);
                g.getBatchParse().setQueue(0);
                g.getBatchParse().setWaitMs(0);
            });
            // 手工占住唯一许可（模拟"已经有一个解析在跑"）
            GateResult busy = env.gate.tryAdmit(GateChannel.BATCH_PARSE, "P", 0);
            assertTrue(busy.admitted());
            try {
                Path rejectedFile = upload(1);
                assertTrue(Files.exists(rejectedFile));
                ApiException error = assertThrows(ApiException.class,
                        () -> env.service.submit(rejectedFile, "第二份.pdf", 1024L, 150, "P", null));

                assertEquals(429, error.getStatus(), "队列满必须 429（可重试），不是 500");
                assertTrue(error.getMessage().contains("队列已满"), error.getMessage());
                assertTrue(error.getMessage().contains("排队"), error.getMessage());
                assertTrue(error.getMessage().contains("在飞"), error.getMessage());
                assertFalse(Files.exists(rejectedFile), "被拒的任务必须清掉临时文件（0 个 work/tmp 残留）");

                Map<String, Object> rejected = env.service.list().stream()
                        .filter(t -> "第二份.pdf".equals(t.get("filename"))).findFirst().orElseThrow();
                assertEquals("FAILED", rejected.get("status"), "绝不能停在 QUEUED");
                assertEquals(0, env.service.list().stream()
                        .filter(t -> "QUEUED".equals(t.get("status"))).count(), "不允许永久 QUEUED");
                assertTrue(env.meters.get("ai.upload.rejected").counter().count() >= 1);
            } finally {
                busy.close();
                env.executor.shutdown();
            }
        }
    }

    @Test
    @DisplayName("取消：协作式生效（阶段边界），但 L1 permit 与指标立即回落（V8）")
    void cancelReleasesPermitImmediately() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            Env env = env(server, g -> {
                g.getBatchParse().setMax(1);
                g.getBatchParse().setQueue(2);
            });
            try {
                UploadTask task = env.service.submit(upload(2), "合同.pdf", 1024L, 150, null, null);
                // 等它进入终态（本桩解析很快），取消必须在"未结束"时才有意义
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && !env.service.holdsPermit(task.getTaskId())
                        && !"DONE".equals(task.getStatus())) {
                    Thread.sleep(5);
                }
                boolean cancelled = env.service.cancel(task.getTaskId());
                awaitFinished(env.service, task);
                if (cancelled) {
                    assertEquals("CANCELLED", task.getStatus());
                }
                // 不论取消还是已跑完，都不能留下 permit（这条是 V8 的实质）
                assertFalse(env.service.holdsPermit(task.getTaskId()), "任务结束后不得再持有 L1 permit");
                assertEquals(0, env.gate.snapshotQueueInflight("batch.parse"),
                        "ai.gate.inflight 必须立即回落");
                assertEquals(0.0, env.meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(),
                        1e-9);
            } finally {
                env.executor.shutdown();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 替身（都离线：不发网络请求）
    // ══════════════════════════════════════════════════════════════

    /** 解析器替身：返回固定文本（不读真 PDF）。父类构造要 PdfReader/AiSettings，这里只给最小可用实例。 */
    static final class StubReader extends DocumentReader {

        StubReader() {
            super(new PdfReader(), null, new AiSettings());
        }

        @Override
        public DocumentText read(Path path, Integer dpi, boolean forceOcr,
                                 com.pmgt.ai.common.util.ProgressFn onProgress) {
            DocumentText doc = new DocumentText();
            doc.setKind("text_pdf");
            doc.setProvider("text-layer");
            try {
                String all = Files.readString(path, StandardCharsets.UTF_8);
                List<PageText> pages = new ArrayList<>();
                String[] lines = all.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (!lines[i].isBlank()) {
                        pages.add(new PageText(i + 1, lines[i]));
                    }
                }
                doc.setPages(pages);
            } catch (Exception e) {
                doc.setPages(List.of(new PageText(1, "甲".repeat(600))));
            }
            if (onProgress != null) {
                onProgress.on("read", 1, 1);
            }
            return doc;
        }
    }

    /** PDF 信息替身：永远"文本型、无错"。 */
    static final class StubPdfReader extends PdfReader {

        StubPdfReader() {
            super();
        }
        @Override
        public PdfInfo read(Path path) {
            return new PdfInfo(path, 2, 1200, null);
        }
    }

    /** 向量化替身：本地造向量，不联网。 */
    static final class StubVec implements VecApi {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            calls.incrementAndGet();
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                out.add(new float[] {text.length(), 1f});
            }
            return out;
        }

        @Override
        public List<RerankHit> rerank(String query, List<String> documents, Integer topK) {
            return List.of();
        }

        @Override
        public VecHealth health(double timeoutSeconds) {
            return new VecHealth(true, "stub", List.of());
        }

        @Override
        public double cosine(float[] a, float[] b) {
            return 0;
        }
    }
}
