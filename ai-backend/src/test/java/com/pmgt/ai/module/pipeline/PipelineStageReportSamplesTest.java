package com.pmgt.ai.module.pipeline;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.testutil.StubHttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

/**
 * 人工核对用的样例导出（<b>默认跳过</b>，只在显式给系统属性时运行）：
 * {@code mvn -o test -Dtest=PipelineStageReportSamplesTest -Dai.samples=true}。
 *
 * <p>它把五阶段边界 + 终态的<b>真实请求体</b>打到 stdout——文档与联调时直接引用它，
 * 而不是手写"我以为发的是这样"。默认跳过是为了不让日常 {@code mvn test} 输出噪音。
 */
@EnabledIfSystemProperty(named = "ai.samples", matches = "true")
class PipelineStageReportSamplesTest {

    @Test
    void dumpStageReportSamples() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"message\":\"ok\",\"data\":{}}")) {
            AiSettings settings = new AiSettings();
            settings.getPipeline().setEnabled(true);
            settings.getPipeline().setBaseUrl(server.baseUrl());
            settings.getPipeline().setServiceToken("SAMPLE-TOKEN");
            settings.getPipeline().setMdc(false);
            PipelineReporter reporter = new PipelineReporter(settings,
                    new PipelineReportClient(settings, new SimpleMeterRegistry()));

            try (RunContext run = reporter.open("run-1234567890", "SAMPLE-TOKEN", "b1946ac92492d2347c6235b4d2611184", "12")) {
                run.stageStarted(StageKey.PARSE, "file=中标通知书.pdf dpi=150 sha1=b1946ac92492d2347c6235b4d2611184 pipeline_rev="
                        + run.pipelineRev());
                run.stageDone(StageKey.PARSE, 3, 0,
                        Map.of("pages", 3, "text_chars", 4210, "empty_pages", 0, "failed_pages", 0,
                                "provider", "text-layer"), "pages=3; text_chars=4210; provider=text-layer");
                run.artifact(ArtifactType.PAGE_DIGEST, "{\"pages\":3,\"failed_pages\":0,\"per_page\":[...]}",
                        null, 4210, "9f2c...", 3, "页级摘要；全文留在 work/docs/<docId>.json");
                run.stageSkipped(StageKey.CLEAN, PipelineReporter.cleanSkippedReason());
                run.artifact(ArtifactType.CLEAN_DIFF, null, null, null, null, 0,
                        "本批无清洗阶段（clean_rev=0）：无前后对照可写，如实留空而不是伪造数据");
                run.stageStarted(StageKey.CHUNK, "pages=3");
                run.stageDone(StageKey.CHUNK, 3, 0,
                        Map.of("pages", 3, "chunk_count", 3, "avg_char_len", 1403, "max_char_len", 1420,
                                "chunk_chars", 500, "clean_rev", 0, "chunk_rev", 0, "chunk_key_format", "legacy"),
                        "chunks=3");
                run.artifact(ArtifactType.CHUNK_MANIFEST, "{\"chunk_count\":3,\"samples\":[...]}", null, null,
                        "3a1b...", 3, "切片清单（DB 只存摘要）");
                run.stageStarted(StageKey.EMBED, "chunks=3");
                run.stageDone(StageKey.EMBED, 3, 0,
                        Map.of("chunks", 3, "embedded", 3, "reused", 0, "batches", 1, "batch_size", 32,
                                "concurrency", 8, "model", "Qwen3-VL-Embedding-8B"), "embedded=3; reused=0");
                run.artifact(ArtifactType.EMBED_BATCH, "{\"embedded\":3,\"reused\":0,\"batches\":1}", null,
                        null, null, 3, "向量化批次统计");
                run.stageStarted(StageKey.INDEX, "targets=3");
                run.stageDone(StageKey.INDEX, 3, 0,
                        Map.of("backend", "local", "written", 0, "cache_entries", 3), "local 后端：向量落 work/vectors");
                run.event("RUNNING", "RUNNING", "doc_id=9f2c1a4b7d3e5f60 staged=PARSE", "ai-backend");
                run.finish("DONE", null);
            }

            for (String request : server.requests()) {
                System.out.println(request);
            }
        }
    }
}
