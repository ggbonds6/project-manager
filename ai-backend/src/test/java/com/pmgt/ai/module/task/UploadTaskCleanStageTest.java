package com.pmgt.ai.module.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.gate.PipelineGate;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLEAN 阶段的<b>端到端上报</b>（设计 §3.3 + §3.3.4）：真 HTTP 桩 + 真清洗器 + 真切分。
 *
 * <p>两条对称的验收：
 * <ol>
 *   <li><b>启用</b>：{@code CLEAN} 上报 {@code DONE}（或 {@code PARTIAL}）并带 counters
 *       （{@code removed_chars_ratio}/{@code removed_lines}/{@code rules_fired} 等），
 *       {@code clean_diff} 产物有内容，全文落在 {@code work/clean/<docId>.json}；</li>
 *   <li><b>关闭（默认）</b>：{@code CLEAN} 上报 {@code SKIPPED} 且 <b>counters 一个都不给</b>
 *       ——"没做的事不编数字"。</li>
 * </ol>
 */
class UploadTaskCleanStageTest {

    @TempDir
    Path tmp;

    private static final String TOKEN = "unit-test-token";

    private record Env(AiSettings settings, UploadTaskService service, ThreadPoolTaskExecutor executor,
                       DocStore docs) {
    }

    private Env env(StubHttpServer server, boolean cleanEnabled) throws Exception {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getRetrieval().setBackend("local");
        settings.getLimits().setBatchAcquireMs(3000);
        settings.getPipeline().setEnabled(true);
        settings.getPipeline().setBaseUrl(server.baseUrl());
        settings.getPipeline().setServiceToken(TOKEN);
        settings.getPipeline().setMdc(false);
        settings.getPipeline().setTimeoutSeconds(2);
        settings.getPipeline().setCleanRev(cleanEnabled ? 1 : 0);
        settings.getClean().setEnabled(cleanEnabled);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = new PipelineGate(settings, meters);
        PipelineReporter reporter = new PipelineReporter(settings, new PipelineReportClient(settings, meters));
        DocStore docs = new DocStore(tmp.resolve("docs"));
        DocMetaStore metaStore = new DocMetaStore(tmp);
        RetrievalService retrieval = new RetrievalService(settings, new StubVec(), docs, null,
                TstRegistry.l2(settings), meters, metaStore);
        DocIndexer indexer = new DocIndexer(settings, docs, retrieval, null, meters, metaStore);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("pm-ai-clean-test-");
        executor.initialize();

        UploadTaskService service = new UploadTaskService(settings, new ObjectMapper(),
                new StubReader(), new StubPdfReader(), docs, indexer, executor, meters, gate, reporter);
        return new Env(settings, service, executor, docs);
    }

    /**
     * 造一份"有页眉、有页码、有重复段"的文件：清洗必须真的删东西，否则 counters 会全是 0。
     *
     * <p>⚠️ 每页 6 行：页眉/页码在页首，页末是本节独有的两行——重复段落在<b>页中间</b>，
     * 这样 C1（只管页首页末 2 行）不会把它当页脚删掉，C6（段级去重）才有机会标记它。
     */
    private Path upload() throws Exception {
        Path file = Files.createTempFile(tmp, "clean-", ".pdf");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            sb.append("某某项目招标文件 第 ").append(i).append(" 页\n")
                    .append("第 ").append(i).append(" 页\n")
                    .append("第 ").append(i).append(" 节 通用条款\n")
                    .append("本合同一式肆份，甲乙双方各执贰份，具有同等法律效力。\n")
                    .append("第 ").append(i).append(" 章正文：本项目按合同约定分期支付，")
                    .append("每期金额以验收结论为准。\n")
                    .append("本节附注 ").append(i).append("：以上条款自签署之日起生效。\n");
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

    private static String requestOf(List<String> requests, String marker) {
        return requests.stream().filter(r -> r.contains(marker)).findFirst().orElseThrow(
                () -> new AssertionError("没有找到请求：" + marker + "；实际=" + requests));
    }

    @Test
    @DisplayName("清洗启用：CLEAN 上报 DONE + counters（removed_chars_ratio/removed_lines/rules_fired）+ clean_diff 全文落 work/clean")
    void cleanStageReportsDoneWithCounters() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"ok\",\"data\":{}}")) {
            Env env = env(server, true);
            try {
                UploadTask task = env.service.submit(upload(), "招标文件.pdf", 4096L, 150, "P1", null);
                awaitFinished(env.service, task);
                assertEquals("DONE", task.getStatus(), "任务应成功：" + task.getError());
                assertNotNull(task.getDocId());
            } finally {
                env.executor.shutdown();
            }

            List<String> requests = new ArrayList<>(server.requests());
            String cleanDone = requests.stream()
                    .filter(r -> r.contains("/stages/CLEAN") && r.contains("\"DONE\""))
                    .findFirst().orElseThrow(() -> new AssertionError("CLEAN 必须是 DONE：" + requests));
            assertTrue(cleanDone.contains("\"counters\""), "启用时必须带 counters：" + cleanDone);
            assertTrue(cleanDone.contains("removed_chars_ratio"), cleanDone);
            assertTrue(cleanDone.contains("removed_lines"), cleanDone);
            assertTrue(cleanDone.contains("rules_fired"), cleanDone);
            assertTrue(cleanDone.contains("\"clean_rev\":1"), cleanDone);
            assertTrue(cleanDone.contains("pages"), cleanDone);
            assertTrue(cleanDone.contains("\"rules_hit\""), cleanDone);
            // 页眉/页码必须真的被删（否则 counters 是假的）
            assertFalse(cleanDone.contains("\"removed_lines\":0"), "这份文档有页眉+页码+重复段，必须删到东西");

            String artifact = requestOf(requests, "\"artifact_type\":\"clean_diff\"");
            assertTrue(artifact.contains("\"content\""), "clean_diff 必须有摘要内容：" + artifact);
            assertTrue(artifact.contains("work/clean/"), "要给出全文路径（object_key）：" + artifact);
            assertTrue(artifact.contains("removed_samples") || artifact.contains("rules_fired"), artifact);

            // 全文落在 work/clean/<docId>.json，且带 before/after 对照
            Path trace = tmp.resolve("clean").resolve(env.docs.allDocs().iterator().next().getDocId() + ".json");
            assertTrue(Files.isRegularFile(trace), "清洗前后对照全文必须落盘：" + trace);
            String full = Files.readString(trace);
            assertTrue(full.contains("\"before\"") && full.contains("\"after\""), full.substring(0, 200));
            assertTrue(full.contains("某某项目招标文件"), "原文必须在（对照的另一半）");
        }
    }

    @Test
    @DisplayName("清洗关闭（默认）：CLEAN 上报 SKIPPED + 原因，counters 一个都不给，且不落 work/clean")
    void cleanStageSkippedWhenDisabled() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"ok\",\"data\":{}}")) {
            Env env = env(server, false);
            try {
                UploadTask task = env.service.submit(upload(), "招标文件.pdf", 4096L, 150, "P1", null);
                awaitFinished(env.service, task);
                assertEquals("DONE", task.getStatus(), task.getError());
            } finally {
                env.executor.shutdown();
            }

            List<String> requests = new ArrayList<>(server.requests());
            String clean = requestOf(requests, "/stages/CLEAN");
            assertTrue(clean.contains("\"status\":\"SKIPPED\""), clean);
            assertTrue(clean.contains("ai.clean.enabled=false"), "必须写明原因与打开方式：" + clean);
            assertFalse(clean.contains("\"counters\""), "关闭时不得给 counters（没做的事不编数字）：" + clean);
            assertTrue(clean.contains("\"item_count\":0"), clean);
            assertFalse(clean.contains("removed_chars_ratio"), clean);

            String artifact = requestOf(requests, "\"artifact_type\":\"clean_diff\"");
            assertFalse(artifact.contains("\"content\""), "关闭时 clean_diff 如实留空：" + artifact);
            assertTrue(artifact.contains("清洗关闭"), artifact);

            Path cleanDir = tmp.resolve("clean");
            assertFalse(Files.exists(cleanDir), "关闭时不该落任何清洗产物（行为与接入前一致）");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 替身（都离线）
    // ══════════════════════════════════════════════════════════════

    /** 解析器替身：按"页眉"分行聚成真页（不读真 PDF），保留页眉/页码/重复段这些清洗素材。 */
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
                List<String> current = new ArrayList<>();
                int pageNo = 0;
                for (String line : all.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.startsWith("某某项目招标文件")) {
                        pageNo = flush(pages, current, pageNo);
                    }
                    current.add(line);
                }
                flush(pages, current, pageNo);
                doc.setPages(pages);
            } catch (Exception e) {
                PageText page = new PageText(1, "甲".repeat(600));
                page.setSource("text-layer");
                doc.setPages(List.of(page));
            }
            if (onProgress != null) {
                onProgress.on("read", 1, 1);
            }
            return doc;
        }

        private static int flush(List<PageText> pages, List<String> current, int pageNo) {
            if (current.isEmpty()) {
                return pageNo;
            }
            PageText page = new PageText(pageNo + 1, String.join("\n", current));
            page.setSource("text-layer");
            pages.add(page);
            current.clear();
            return pageNo + 1;
        }
    }

    static final class StubPdfReader extends PdfReader {
        @Override
        public PdfInfo read(Path path) {
            return new PdfInfo(path, 3, 1200, null);
        }
    }

    static final class StubVec implements VecApi {
        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
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
