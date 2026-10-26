package com.pmgt.ai.module.chunk;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.clean.CleanContext;
import com.pmgt.ai.module.clean.CleanResult;
import com.pmgt.ai.module.clean.RuleBasedTextCleaner;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.doc.PageText;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分改进（设计 §3.4）的验收口径：
 * <b>表格整块不截断</b>、<b>重叠比例正确</b>、<b>不存在 &gt; max_char_len 的切片</b>、
 * <b>结构边界优先</b>、<b>父子块接缝与元数据</b>，以及"回退口径与接入前逐字一致"。
 */
class DocumentChunkerTest {

    @TempDir
    Path tmp;

    // ══════════════════════════════════════════════════════════════
    // ① 长度硬上限
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("硬上限：一页一整段约 6800 字 ⇒ 按句二次切，不存在 > max_char_len（2000）的切片，且不丢内容")
    void noChunkExceedsMaxCharLen() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("第").append(i).append("句：本项目付款方式为分期付款，验收合格后支付。");
        }
        StoredDoc doc = doc("一段超长正文", sb.toString());
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.size() > 2, "超长段落必须被切开：" + chunks.size());
        for (DocStore.Chunk chunk : chunks) {
            assertTrue(chunk.charLen() <= 2000, "切片超上限（" + chunk.charLen() + "）：" + chunk.text());
        }
        int total = chunks.stream().mapToInt(DocStore.Chunk::charLen).sum();
        assertTrue(total >= sb.length() * 0.95, "切分不能丢内容：total=" + total + " raw=" + sb.length());
    }

    // ══════════════════════════════════════════════════════════════
    // ② 表格整块不截断
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("表格整块：约 780 字的表格不被 500 字目标切断（接入前必被切断——G11）")
    void tableStaysWhole() {
        StringBuilder table = new StringBuilder("| 序号 | 名称 | 金额 |\n| --- | --- | --- |\n");
        for (int i = 0; i < 24; i++) {
            table.append("| ").append(i).append(" | 某某设备采购明细第").append(i).append("项 | ")
                    .append(100000 + i).append(" |\n");
        }
        StoredDoc doc = doc("含表格", table.toString().strip());
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertEquals(1, chunks.size(), "表格必须整块成一个切片（哪怕超过 chunk_chars=500）：" + chunks.size());
        assertTrue(chunks.get(0).isTable(), "要标 is_table=true（检索策略与展示都要它）");
        assertEquals(26, chunks.get(0).tableRows(), "表格行数要落元数据");
        assertTrue(chunks.get(0).charLen() > 500, "它确实超过了打包目标（这正是'整块'的意思）");
        assertTrue(chunks.get(0).charLen() <= 2000, "但仍要受硬上限约束");

        // 接入前的切法：同样内容一定被切成多片（证明这条改进是真的）
        List<DocStore.Chunk> legacy = DocumentChunker.legacy(doc, 500);
        assertTrue(legacy.size() > 1, "回退口径会把这张表切断：" + legacy.size());
    }

    @Test
    @DisplayName("超长表格（> 硬上限）：按行切且每片重复表头行 + 分隔行，并前置【表格：…】")
    void oversizedTableSplitsByRowsRepeatingHeader() {
        StringBuilder table = new StringBuilder("| 序号 | 名称 | 金额 |\n| --- | --- | --- |\n");
        for (int i = 0; i < 100; i++) {
            table.append("| ").append(i).append(" | 某某设备采购明细第").append(i).append("项 | ")
                    .append(100000 + i).append(" |\n");
        }
        StoredDoc doc = doc("超长表格", table.toString().strip());
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.size() >= 2, "超过硬上限的表格必须按行切（否则切片超上限）");
        for (DocStore.Chunk chunk : chunks) {
            assertTrue(chunk.isTable(), "每一片都要标 is_table");
            assertTrue(chunk.charLen() <= 2000, "每片 ≤ 硬上限：" + chunk.charLen());
            assertTrue(chunk.text().startsWith("【表格："), "每片要前置表格说明：" + chunk.text().substring(0, 20));
            assertTrue(chunk.text().contains("| 序号 | 名称 | 金额 |"), "每片必须重复表头行");
            assertTrue(chunk.text().contains("| --- | --- | --- |"), "每片必须重复分隔行");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ③ 重叠窗口
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("重叠：相邻正文片按句回退重叠 10%~15%；表格片不参与重叠")
    void overlapRatioIsWithinTenToFifteenPercent() {
        StoredDoc doc = doc("重叠验证", longPage(12));
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.size() >= 3, "该页应被切成多片才能验重叠：" + chunks.size());
        assertEquals(60, ChunkOptions.of(settings(0, false)).overlapChars(),
                "12% × 500 = 60 字（设计 §3.4.3 的 10%~15% 区间）");
        int withOverlap = 0;
        for (int i = 1; i < chunks.size(); i++) {
            int overlap = overlapLength(chunks.get(i).text(), chunks.get(i - 1).text());
            if (overlap > 0) {
                withOverlap++;
                assertTrue(overlap >= 40 && overlap <= 80,
                        "重叠应在 10%~15% 区间（40~75 字；按句回退会略浮动）：实际 " + overlap);
            }
        }
        assertTrue(withOverlap >= 2, "至少两对相邻片带重叠（否则重叠没生效）");

        // 表格片不参与重叠：带表格的页里，表格片的前缀不能是上一片的尾部
        StoredDoc withTable = doc("表格页", "正文前段：本项目按合同约定分期支付，每期金额以验收结论为准。\n\n"
                + "| a | b |\n| --- | --- |\n| 1 | 2 |");
        for (DocStore.Chunk chunk : chunk(withTable, 0, false)) {
            if (chunk.isTable()) {
                assertTrue(chunk.text().startsWith("|"), "表格片不能被加上重叠前缀：" + chunk.text());
            }
        }
    }

    @Test
    @DisplayName("重叠只发生在同一页内：跨页的相邻片没有重叠（保持页码语义）")
    void overlapNeverCrossesPages() {
        String page = longPage(8);
        StoredDoc doc = doc("两页", page, page);
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.stream().anyMatch(c -> c.pageNo() == 2), "第二页要有切片");
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i).pageNo() != chunks.get(i - 1).pageNo()) {
                assertEquals(0, overlapLength(chunks.get(i).text(), chunks.get(i - 1).text()),
                        "跨页不许重叠（第 " + chunks.get(i - 1).pageNo() + " → " + chunks.get(i).pageNo() + " 页）");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ④ 结构感知
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("结构边界优先：标题/条款起始处开新片；section_path 记录标题路径")
    void structureBoundariesStartNewChunks() {
        String body = "第一章 总则\n" + "甲".repeat(300) + "。\n\n第二章 付款方式\n3.2 付款期限\n"
                + "乙".repeat(300) + "。\n\n（一）首期款\n" + "丙".repeat(300) + "。";
        StoredDoc doc = doc("结构化文档", body);
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.size() >= 3, "标题处应开新片：" + chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> c.text().startsWith("第二章")),
                "第二章必须是某一片的开头（不在标题与其后续正文之间切）："
                        + chunks.stream().map(c -> c.text().substring(0, 6)).toList());
        assertTrue(chunks.stream().anyMatch(c -> c.sectionPath().contains("第二章")),
                "section_path 要带章节路径：" + chunks.stream().map(DocStore.Chunk::sectionPath).toList());

        // 反例：关掉结构感知后标题不再强制开新片 ⇒ 片数不会更多
        List<DocStore.Chunk> plain = chunk(doc, 0, false, false);
        assertTrue(plain.size() <= chunks.size(), "关掉结构感知后片数应当 ≤ 开启时：" + plain.size());
    }

    // ══════════════════════════════════════════════════════════════
    // ⑤ 父子块接缝与元数据
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("父子块：每个子块带 parent_key（同页同父）；parentSummary 是同页子块的拼接；父块索引可列出")
    void parentKeysAndSummary() {
        String page = longPage(12);
        StoredDoc doc = doc("两页", page, page);
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        for (DocStore.Chunk chunk : chunks) {
            assertEquals(doc.getDocId() + ":p=" + chunk.pageNo(), chunk.parentKey(),
                    "父键口径 = <docId>:p=<pageNo>");
        }
        assertEquals(2, DocumentChunker.parentCount(chunks), "两个父块（两页）");

        List<DocStore.Chunk> page1 = chunks.stream().filter(c -> c.pageNo() == 1).toList();
        String summary = DocumentChunker.parentSummary(page1, 200);
        assertTrue(summary.length() <= 201, "父块摘要默认 ≤200 字：实际 " + summary.length());
        assertTrue(summary.startsWith(page1.get(0).text().substring(0, 20)),
                "摘要必须来自同页子块（上卷的语义）");
        assertEquals("", DocumentChunker.parentSummary(List.of(), 200), "没有子块时如实给空");

        List<java.util.Map<String, Object>> parents = DocumentChunker.parentsOf(chunks, 200);
        assertEquals(2, parents.size());
        assertEquals(page1.size(), parents.get(0).get("child_count"));
        assertTrue(String.valueOf(parents.get(0).get("child_keys")).contains(doc.getDocId()));
    }

    @Test
    @DisplayName("元数据补齐：order_in_page 递增、source/页码/文档号都在")
    void chunkMetadataIsComplete() {
        StoredDoc doc = doc("元数据", longPage(12));
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);

        assertTrue(chunks.size() >= 2);
        for (int i = 0; i < chunks.size(); i++) {
            DocStore.Chunk chunk = chunks.get(i);
            assertEquals(i, chunk.orderInPage(), "页内序号必须递增（重叠去重与排序都要它）");
            assertEquals(doc.getDocId(), chunk.docId());
            assertEquals("元数据.pdf", chunk.filename());
            assertEquals(1, chunk.pageNo());
            assertEquals("text-layer", chunk.source(), "来源要带上（文本层 / OCR）");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ⑥ 回退口径（今天的行为）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("回退口径（improved=false）：与 DocStore.iterChunks 逐字一致（含段落 >500 字不切的行为）")
    void legacyModeMatchesTodayExactly() {
        DocStore store = store();
        StoredDoc doc = save(store, "回退", "甲".repeat(600) + "\n\n" + "乙".repeat(400) + "\n\n丙段。",
                "第二页：" + "丁".repeat(520));
        List<DocStore.Chunk> today = store.iterChunks(doc);
        List<DocStore.Chunk> legacy = DocumentChunker.chunk(
                new RuleBasedTextCleaner(new AiSettings()).cleanDocument(CleanContext.of(doc, 0)),
                doc, ChunkOptions.legacy(500));

        assertEquals(today.size(), legacy.size());
        for (int i = 0; i < today.size(); i++) {
            assertEquals(today.get(i).text(), legacy.get(i).text(), "回退口径必须逐字一致（第 " + i + " 片）");
            assertEquals(today.get(i).pageNo(), legacy.get(i).pageNo());
        }

        // 600 字的单段在两种口径下都是一个 600 字的切片（"改进"不改变这一条）
        List<DocStore.Chunk> improved = chunk(doc, 0, false);
        assertTrue(improved.stream().anyMatch(c -> c.text().equals("甲".repeat(600))),
                "≤ 硬上限的段落整段保留（与接入前一致）");
    }

    @Test
    @DisplayName("改进口径与回退口径确实不同（否则'改进'是假的）：多段落页的切片文本不同")
    void improvedDiffersFromLegacyOnMultiParagraphPages() {
        DocStore store = store();
        StoredDoc doc = save(store, "对照", longPage(12));
        List<String> today = store.iterChunks(doc).stream().map(DocStore.Chunk::text).toList();
        List<String> improved = chunk(doc, 0, false).stream().map(DocStore.Chunk::text).toList();

        assertNotEquals(today, improved, "带重叠与结构边界的切分结果必须与接入前不同");
        for (String text : improved) {
            assertTrue(text.length() <= 2000);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    /** 一页若干"长段落"（每段约 125 字，段间空行）——够长才能逼出多片与重叠。 */
    static String longPage(int units) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < units; i++) {
            sb.append("第").append(i).append("段：").append("甲".repeat(115)).append("。\n\n");
        }
        return sb.toString().strip();
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

    private StoredDoc doc(String name, String... pages) {
        return save(store(), name, pages);
    }

    private static AiSettings settings(int cleanRev, boolean cleanEnabled) {
        AiSettings settings = new AiSettings();
        settings.getPipeline().setCleanRev(cleanRev);
        settings.getClean().setEnabled(cleanEnabled);
        // 本类验证的是「改进切分」的口径；产品默认已改为 false（与接入前逐字一致），
        // 所以这里显式打开，而不是依赖默认值——否则本类会静默退化成"回退口径"的用例。
        settings.getChunk().setImproved(true);
        return settings;
    }

    /** 默认口径：清洗关（clean_rev=0）+ 改进切分 + 结构感知 + 表格整块。 */
    private static List<DocStore.Chunk> chunk(StoredDoc doc, int cleanRev, boolean cleanEnabled) {
        return chunk(doc, cleanRev, cleanEnabled, true);
    }

    private static List<DocStore.Chunk> chunk(StoredDoc doc, int cleanRev, boolean cleanEnabled,
                                              boolean structureAware) {
        AiSettings settings = settings(cleanRev, cleanEnabled);
        settings.getChunk().setStructureAware(structureAware);
        CleanResult cleaned = new RuleBasedTextCleaner(settings)
                .cleanDocument(CleanContext.of(doc, cleanRev));
        return DocumentChunker.chunk(cleaned, doc, ChunkOptions.of(settings));
    }

    /** {@code current} 开头与 {@code previous} 结尾的最大重叠长度（度量"重叠了多少字"）。 */
    static int overlapLength(String current, String previous) {
        int max = Math.min(current.length(), previous.length());
        for (int len = max; len > 0; len--) {
            if (previous.endsWith(current.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }

    @Test
    @DisplayName("留空页不产出切片（空页不该生成空切片）")
    void blankPagesProduceNoChunks() {
        StoredDoc doc = doc("空页", "   ", "正文甲。");
        List<DocStore.Chunk> chunks = chunk(doc, 0, false);
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).pageNo());
        assertFalse(chunks.get(0).text().isBlank());
    }
}
