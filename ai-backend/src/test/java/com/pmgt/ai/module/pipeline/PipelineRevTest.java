package com.pmgt.ai.module.pipeline;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.retrieval.DocIndexer;
import com.pmgt.ai.module.retrieval.DocMetaStore;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pipeline_rev} / {@code clean_rev} 进<b>切片键</b>与<b>向量缓存校验</b>（设计 §3.3.3 的 L1/L2 防护，T7）。
 *
 * <p>要钉住的三条：
 * <ol>
 *   <li>版本变化 ⇒ 切片键变化（{@code chunk-key-format=rev} 时）；</li>
 *   <li>版本变化 ⇒ 向量缓存整体失效并重算（即使切片键没变）；</li>
 *   <li>默认 {@code legacy} 形态与接入前逐字节一致（升级不使既有索引/缓存失效）。</li>
 * </ol>
 */
class PipelineRevTest {

    @TempDir
    Path tmp;

    // ══════════════════════════════════════════════════════════════
    // 切片键形态
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认 legacy：切片键仍是三段 docId:pageNo:sha1[0:12]（升级不使既有索引/缓存失效）")
    void legacyFormatKeepsOldKeyShape() {
        AiSettings settings = new AiSettings();
        PipelineRev.activate(settings);
        try {
            assertEquals(PipelineRev.FORMAT_LEGACY, PipelineRev.format());
            assertFalse(PipelineRev.revKeyEnabled());
            DocStore.Chunk chunk = new DocStore.Chunk("abc123", "a.pdf", 2, "付款条款：验收后 30 日支付。");
            String expected = "abc123:2:" + PipelineRev.sha1Hex(chunk.text()).substring(0, 12);
            assertEquals(expected, RetrievalService.chunkKey(chunk), "legacy 形态必须与接入前逐字节一致");

            PipelineRev.ParsedKey parsed = PipelineRev.parseChunkKey(expected);
            assertNotNull(parsed);
            assertTrue(parsed.legacy());
            assertEquals("abc123", parsed.docId());
            assertEquals(2, parsed.pageNo());
            assertEquals(-1, parsed.cleanRev(), "旧键没有版本段 ⇒ 如实报 -1（未知）");
        } finally {
            PipelineRev.reset();
        }
    }

    @Test
    @DisplayName("rev 形态：chunkKey 带 clean_rev/chunk_rev；解析器对两种形态都认")
    void revFormatCarriesRevisions() {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setChunkKeyFormat("rev");
        settings.getPipeline().setCleanRev(3);
        settings.getPipeline().setChunkRev(7);
        settings.getPipeline().setParseRev(2);
        PipelineRev.activate(settings);
        try {
            assertEquals(PipelineRev.FORMAT_REV, PipelineRev.format());
            DocStore.Chunk chunk = new DocStore.Chunk("abc123", "a.pdf", 2, "付款条款：验收后 30 日支付。");
            String hash = PipelineRev.sha1Hex(chunk.text()).substring(0, 12);
            String key = RetrievalService.chunkKey(chunk);
            assertEquals("d=abc123:p=2:h=" + hash + ":c=3:k=7", key);

            PipelineRev.ParsedKey parsed = PipelineRev.parseChunkKey(key);
            assertNotNull(parsed);
            assertFalse(parsed.legacy());
            assertEquals("abc123", parsed.docId());
            assertEquals(2, parsed.pageNo());
            assertEquals(3, parsed.cleanRev());
            assertEquals(7, parsed.chunkRev());

            // 兼容读：旧三段键在 rev 形态下依然能解析（切换期新旧键共存）
            assertNotNull(PipelineRev.parseChunkKey("abc123:2:" + hash));
            assertNull(PipelineRev.parseChunkKey("不是键"), "不识别的键返回 null（不抛错）");
            assertNull(PipelineRev.parseChunkKey(null));
            assertNull(PipelineRev.parseChunkKey(""));
        } finally {
            PipelineRev.reset();
        }
    }

    @Test
    @DisplayName("clean_rev 变化 ⇒ 同一份切片的键变化（旧索引不会被误当命中）")
    void cleanRevChangeChangesChunkKey() {
        DocStore.Chunk chunk = new DocStore.Chunk("abc123", "a.pdf", 1, "同样的文本");
        String before;
        String after;
        AiSettings settings = new AiSettings();
        settings.getPipeline().setChunkKeyFormat("rev");
        settings.getPipeline().setCleanRev(1);
        PipelineRev.activate(settings);
        try {
            before = RetrievalService.chunkKey(chunk);
        } finally {
            PipelineRev.reset();
        }
        settings.getPipeline().setCleanRev(2);
        PipelineRev.activate(settings);
        try {
            after = RetrievalService.chunkKey(chunk);
        } finally {
            PipelineRev.reset();
        }
        assertNotEquals(before, after, "清洗规则版本变了，切片键必须跟着变");
        assertTrue(before.endsWith(":c=1:k=0"), before);
        assertTrue(after.endsWith(":c=2:k=0"), after);
    }

    @Test
    @DisplayName("pipeline_rev 组合口径：parse/clean/chunk/embed 四段，换模型或维度都会变")
    void pipelineRevComposition() {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setParseRev(1);
        settings.getPipeline().setCleanRev(0);
        settings.getPipeline().setChunkRev(0);
        PipelineRev.activate(settings);
        try {
            String rev = PipelineRev.pipelineRev();
            assertTrue(rev.startsWith("parse=1,clean=0,chunk=0,embed="), rev);
            assertEquals(4, rev.split(",").length, rev);
        } finally {
            PipelineRev.reset();
        }
        String model1 = PipelineRev.Revisions.embedRevOf("Qwen3-VL-Embedding-8B", 0);
        String model2 = PipelineRev.Revisions.embedRevOf("Qwen3-VL-Embedding-8B", 2048);
        String model3 = PipelineRev.Revisions.embedRevOf("other-model", 0);
        assertNotEquals(model1, model2, "维度变了 ⇒ 向量必须重建");
        assertNotEquals(model1, model3, "模型变了 ⇒ 向量必须重建");
        assertEquals(8, model1.length());
    }

    @Test
    @DisplayName("任务级幂等键 task_key = sha1(内容 sha1 + pipeline_rev)：内容或版本任一变化即不同")
    void taskKeyCombinesContentAndRevision() {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(0);
        PipelineRev.activate(settings);
        String a;
        try {
            a = PipelineRev.taskKey("content-sha1");
            assertNotEquals(a, PipelineRev.taskKey("content-sha2"), "内容变了 ⇒ 键必须变");
            assertEquals(a, PipelineRev.taskKey("content-sha1"), "同内容同版本 ⇒ 同键（幂等）");
        } finally {
            PipelineRev.reset();
        }
        settings.getPipeline().setCleanRev(1);
        PipelineRev.activate(settings);
        try {
            assertNotEquals(a, PipelineRev.taskKey("content-sha1"), "清洗版本变了 ⇒ 键必须变");
        } finally {
            PipelineRev.reset();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CHUNK 阶段的上报与产物（阶段边界与产物的落点）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CHUNK 阶段上报与产物：prepare 上报切片数/长度计数，并写 chunk_manifest 摘要（只存摘要）")
    void prepareReportsChunkStageAndArtifact() throws Exception {
        try (StubHttpServer server = new StubHttpServer(
                (method, path, body) -> "{\"code\":0,\"data\":{}}")) {
            Fixture fixture = fixture(0);
            AiSettings settings = fixture.settings;
            settings.getPipeline().setEnabled(true);
            settings.getPipeline().setBaseUrl(server.baseUrl());
            settings.getPipeline().setServiceToken("stub-token");
            settings.getPipeline().setMdc(false);
            PipelineReporter reporter = new PipelineReporter(settings,
                    new PipelineReportClient(settings, new SimpleMeterRegistry()));

            String docId = saveDoc(fixture.store);
            try (RunContext run = reporter.open("run-chunk", "stub-token", null, "P1")) {
                DocIndexer.Prepared prepared = fixture.indexer.prepare(fixture.store.get(docId), run);
                assertEquals(1, prepared.chunks().size());
                assertTrue(prepared.elapsedMs() >= 0);
                run.finish("DONE", null);
            }

            List<String> requests = server.requests();
            // 同一阶段有 RUNNING 与 DONE 两条（幂等：主系统按 run+stage 更新同一行）
            String chunkReport = requests.stream()
                    .filter(r -> r.contains("/stages/CHUNK") && r.contains("\"DONE\""))
                    .findFirst().orElseThrow();
            assertTrue(chunkReport.contains("\"status\":\"DONE\""), chunkReport);
            assertTrue(chunkReport.contains("\"item_count\":1"), chunkReport);
            assertTrue(chunkReport.contains("chunk_count"), chunkReport);
            assertTrue(chunkReport.contains("avg_char_len"), chunkReport);
            assertTrue(chunkReport.contains("max_char_len"), chunkReport);
            assertTrue(chunkReport.contains("chunk_key_format"), chunkReport);

            String artifact = requests.stream()
                    .filter(r -> r.contains("/artifacts")).findFirst().orElseThrow();
            assertTrue(artifact.contains("\"artifact_type\":\"chunk_manifest\""), artifact);
            assertTrue(artifact.contains("\"sha1\":"), artifact);
            assertTrue(artifact.contains("\"item_count\":1"), artifact);
            assertTrue(artifact.contains("chunk_key"), "摘要里要带切片键样本（Q5 与溯源都要它）");
            assertTrue(artifact.length() < 6000, "产物只存摘要（content ≤4000 字符的纪律）");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    private record Fixture(AiSettings settings, DocStore store, DocIndexer indexer) {
    }

    /** 一份 1 页文档（1 个切片）。 */
    private String saveDoc(DocStore store) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        List<PageText> pages = new ArrayList<>();
        pages.add(new PageText(1, "甲".repeat(600)));
        doc.setPages(pages);
        return store.save("a.pdf", doc, 0L).getDocId();
    }

    private Fixture fixture(int cleanRev) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getPipeline().setCleanRev(cleanRev);
        settings.getPipeline().setChunkKeyFormat("legacy");
        DocStore store = new DocStore(tmp.resolve("docs"));
        CountingVec vec = new CountingVec();
        DocMetaStore metaStore = new DocMetaStore(tmp);
        RetrievalService retrieval = new RetrievalService(settings, vec, store, null,
                TstRegistry.l2(settings), new SimpleMeterRegistry(), metaStore);
        DocIndexer indexer = new DocIndexer(settings, store, retrieval, null, new SimpleMeterRegistry(), metaStore);
        return new Fixture(settings, store, indexer);
    }

    /** 本用例不真正向量化，只要一个能满足装配的替身。 */
    static final class CountingVec implements VecApi {

        final AtomicInteger embedCalls = new AtomicInteger();

        @Override
        public List<float[]> embedTexts(List<String> texts, String instruction) {
            embedCalls.incrementAndGet();
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
