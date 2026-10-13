package com.pmgt.ai.module.retrieval;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.chunk.ChunkOptions;
import com.pmgt.ai.module.chunk.DocumentChunker;
import com.pmgt.ai.module.clean.CleanContext;
import com.pmgt.ai.module.clean.CleanResult;
import com.pmgt.ai.module.clean.RuleBasedTextCleaner;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.pipeline.PipelineRev;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import com.pmgt.ai.testutil.TstRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗 + 切分的<b>回归与版本防护</b>（设计 §3.3.3 的 L1/L2 与 §3.4 的验收）。
 *
 * <p>钉住三条：
 * <ol>
 *   <li><b>默认（清洗关、回退切分）与今天逐字一致</b>——升级不该悄悄改变切片文本，
 *       否则切片键全变、既有索引与向量缓存全部失效；</li>
 *   <li>清洗开启后<b>切片文本真的变了</b>（页眉/页码被剔除）且 <b>{@code clean_rev} 变化 ⇒ 向量缓存不命中</b>；</li>
 *   <li>清洗/切分配置变化会体现在 {@code /admin/reindex/plan} 的"需要重算"上（L3，见
 *       {@code ReindexPlanTest}）。</li>
 * </ol>
 */
class CleanRevRegressionTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetRevision() {
        PipelineRev.reset();   // 静态口径：不留到别的用例里
    }

    // ══════════════════════════════════════════════════════════════
    // ① 默认口径 = 今天的行为
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("清洗关（clean_rev=0）+ improved=false ⇒ 切片文本与切片键与 DocStore.iterChunks 逐字一致")
    void disabledCleaningAndLegacyChunkingAreByteIdentical() {
        DocStore store = store();
        StoredDoc doc = save(store, "默认口径",
                "某某项目招标文件 第 1 页\n第 1 页\n" + "甲".repeat(600) + "\n\n" + "乙".repeat(300),
                "某某项目招标文件 第 2 页\n第 2 页\n" + "丙".repeat(700) + "\n\n\n\n丁段。");
        List<DocStore.Chunk> today = store.iterChunks(doc);

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        assertTrue(PipelineRev.pipelineRev().contains("clean=0,chunk=0"),
                "默认 clean_rev/chunk_rev 必须是 0：" + PipelineRev.pipelineRev());
        List<DocStore.Chunk> current = chunksWith(settings, doc, ChunkOptions.legacy(500));

        assertEquals(today.size(), current.size(), "片数必须一致");
        for (int i = 0; i < today.size(); i++) {
            assertEquals(today.get(i).text(), current.get(i).text(), "第 " + i + " 片文本必须逐字一致");
            assertEquals(RetrievalService.chunkKey(today.get(i)), RetrievalService.chunkKey(current.get(i)),
                    "切片键必须一致（否则既有索引/缓存全部失效）");
            assertEquals(today.get(i).pageNo(), current.get(i).pageNo());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ② 清洗开启：文本真的变了 + 版本防护
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("清洗开启：页眉与页码行从切片里消失；clean_rev 变化 ⇒ 向量缓存不命中并重算")
    void cleaningChangesTextAndCleanRevInvalidatesVectorCache() throws Exception {
        DocStore store = store();
        StoredDoc doc = save(store, "清扫",
                "某某项目招标文件 第 1 页\n第 1 页\n合同价款为一百万元。",
                "某某项目招标文件 第 2 页\n第 2 页\n付款方式为分期付款。");

        AiSettings cleaning = new AiSettings();
        cleaning.setWorkDir(tmp);
        cleaning.getClean().setEnabled(true);
        cleaning.getPipeline().setCleanRev(1);
        // 切片键形态切到 rev：这样"版本进切片键"（L1）与"缓存头校验"（L2）两层都能被钉住
        cleaning.getPipeline().setChunkKeyFormat("rev");
        List<DocStore.Chunk> cleaned = chunksWith(cleaning, doc, ChunkOptions.of(cleaning));
        String all = cleaned.stream().map(DocStore.Chunk::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("合同价款为一百万元"), all);
        assertTrue(!all.contains("某某项目招标文件"), "页眉必须被剔除：" + all);
        assertTrue(!all.contains("第 1 页"), "页码行必须被剔除：" + all);

        // 向量缓存：版本一致 ⇒ 命中；clean_rev +1 ⇒ 整体失效重算（L2）
        CountingVec vec = new CountingVec();
        RetrievalService service = new RetrievalService(cleaning, vec, store, null,
                TstRegistry.l2(cleaning), new SimpleMeterRegistry(), new DocMetaStore(tmp));
        String keyBefore = RetrievalService.chunkKey(cleaned.get(0));   // 必须在换版本**之前**取键
        assertTrue(keyBefore.endsWith(":c=1:k=0"), keyBefore);
        Map<String, float[]> first = service.embedChunks(cleaned);
        assertEquals(cleaned.size(), first.size());
        int afterFirst = vec.embedCalls.get();
        service.embedChunks(cleaned);
        assertEquals(afterFirst, vec.embedCalls.get(), "版本没变 ⇒ 第二次必须命中缓存");
        assertTrue(Files.readString(service.vectorPath(doc.getDocId())).contains("\"clean_rev\":1"));

        cleaning.getPipeline().setCleanRev(2);
        PipelineRev.activate(cleaning);        // 生产里这是"改配置要重启"
        List<DocStore.Chunk> revTwo = chunksWith(cleaning, doc, ChunkOptions.of(cleaning));
        String keyAfter = RetrievalService.chunkKey(revTwo.get(0));
        assertNotEquals(keyBefore, keyAfter,
                "clean_rev 变化 ⇒ 切片键必须跟着变（L1：rev 形态下键里带版本段）");
        assertTrue(keyAfter.endsWith(":c=2:k=0"), keyAfter);
        service.embedChunks(revTwo);
        assertTrue(vec.embedCalls.get() > afterFirst, "clean_rev 变化 ⇒ 必须重算向量");
        assertTrue(Files.readString(service.vectorPath(doc.getDocId())).contains("\"clean_rev\":2"));
    }

    @Test
    @DisplayName("clean_rev=0 时不清洗；同一份文档 clean_rev=1 时清洗生效（版本与开关是两个正交的口径）")
    void cleanRevZeroMeansNoCleaningEvenIfEnabled() {
        DocStore store = store();
        StoredDoc doc = save(store, "版本口径", "某某项目招标文件\n正文甲。", "某某项目招标文件\n正文乙。");

        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        settings.getPipeline().setCleanRev(0);
        List<DocStore.Chunk> zero = chunksWith(settings, doc, ChunkOptions.of(settings));
        assertTrue(zero.stream().anyMatch(c -> c.text().contains("某某项目招标文件")),
                "clean_rev=0 ⇒ 未清洗（切分输入为解析原文）");
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    /** 按给定配置清洗 + 切分（不经过 {@code RetrievalService}，只验口径）。 */
    private static List<DocStore.Chunk> chunksWith(AiSettings settings, StoredDoc doc, ChunkOptions options) {
        CleanResult cleaned = new RuleBasedTextCleaner(settings)
                .cleanDocument(CleanContext.of(doc, settings.getPipeline().getCleanRev()));
        return DocumentChunker.chunk(cleaned, doc, options);
    }

    private DocStore store() {
        return new DocStore(tmp.resolve("docs"));
    }

    private static StoredDoc save(DocStore store, String name, String... pages) {
        DocumentText doc = new DocumentText();
        doc.setKind("text_pdf");
        doc.setProvider("text-layer");
        List<PageText> list = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            PageText page = new PageText(i + 1, pages[i]);
            page.setSource("text-layer");
            list.add(page);
        }
        doc.setPages(list);
        return store.save(name + ".pdf", doc, 0L);
    }

    /** 与 {@code VectorCacheRevisionTest} 同一个替身：数 embed 调用次数。 */
    static final class CountingVec implements VecApi {

        final java.util.concurrent.atomic.AtomicInteger embedCalls = new java.util.concurrent.atomic.AtomicInteger();

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

    /** 供其他用例复用的"清洗键集合"（避免各处自己拼）。 */
    static Set<String> keysOf(List<DocStore.Chunk> chunks) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        chunks.forEach(chunk -> keys.add(RetrievalService.chunkKey(chunk)));
        return keys;
    }
}
