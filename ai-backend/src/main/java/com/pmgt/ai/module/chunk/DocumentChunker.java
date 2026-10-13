package com.pmgt.ai.module.chunk;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.text.TextStructure;
import com.pmgt.ai.module.clean.CleanContext;
import com.pmgt.ai.module.clean.CleanResult;
import com.pmgt.ai.module.clean.RuleBasedTextCleaner;
import com.pmgt.ai.module.clean.TextCleaner;
import com.pmgt.ai.module.retrieval.RetrievalService;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切分（设计 §3.4）：<b>结构感知 + 表格整块 + 重叠窗口 + 超长段落二次切 + 长度硬上限 + 父子块接缝</b>。
 *
 * <h2>为什么要有这个类（原来藏在 {@code DocStore.iterChunks} 里）</h2>
 * 接入前的切法是"段落累积到 500 字就断"，三个后果都是实测过的缺陷：
 * ① 800 字宽的表格<b>必被切断</b>（G11，有表头没表体）；② <b>没有重叠</b>，跨片的问题在缝上丢证据；
 * ③ 没有长度上限，一页一整段的扫描件会产出几千字的切片（向量语义被稀释）。
 * 所以切分被提为独立阶段（CHUNK 阶段行），算法也按 §3.4 重写。原算法<b>逐字保留</b>在
 * {@link #legacy(StoredDoc, int)}，{@code ai.chunk.improved=false} 时回退到它（对照与口味回归用）。
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li><b>不跨页</b>：切片带页码（来源标注的前提），重叠也只发生在同一页内；</li>
 *   <li><b>不超上限</b>：{@code max_char_len}（默认 2000）是硬上限，超长段落按句末标点二次切；</li>
 *   <li><b>表格不截断</b>：表格块整块成片；超过上限时按行切，<b>每片重复表头行 + 分隔行</b>，
 *       并前置 {@code 【表格：<表头摘要>】}（设计 §3.4.2）。</li>
 * </ol>
 *
 * <h2>打包目标 vs 硬上限（本轮的关键取舍，写在明处）</h2>
 * 段落只要 ≤ 硬上限就<b>整段保留</b>（哪怕超过 {@code chunk-chars}）：
 * {@code chunk-chars} 是打包目标，{@code max-char-len} 才是硬上限。理由是"段落是语义单元，
 * 硬切只会让两片都不完整"，而且它与接入前的行为一致（600 字的段落今天也是 600 字的切片）——
 * 回归用例 {@code CleanRevRegressionTest} 就靠这一条钉住"清洗关闭时行为不变"。
 *
 * <h2>父子块（small-to-big）本轮做到哪一步</h2>
 * <b>只做"同页邻块"的轻量形态</b>（设计 §3.4.4 明确允许先做最简版）：
 * <ul>
 *   <li><b>父块 = 一页</b>：{@code parent_key = <docId>:p=<pageNo>}，每个切片都带该键；
 *       页本身有页码语义（可溯源），不需要先建章节树；</li>
 *   <li><b>子块 = 切片</b>：检索单元不变；命中后由 {@code RetrievalService} 用
 *       {@link #parentSummary(List, int)} 现算父块摘要（同页全部子块拼接的前 N 字）一并给模型，
 *       省掉一次 {@code read_page}（设计 §3.4.4 的收益口径）；</li>
 *   <li><b>章节路径</b>：{@code section_path}（如"第三章 &gt; 3.2 付款方式"）已经逐片算出来并落元数据，
 *       它是后续升级到"章节树父块"的<b>接缝</b>——升级时只需把 {@code parent_key} 从"页"换成"章节"，
 *       子块、重叠、上限三件事都不用动；</li>
 *   <li><b>父块索引</b>：{@code work/chunks/<docId>.json} 里带 {@code parents[]}（父键 + 子键列表 + 摘要），
 *       是可重建物、不进主系统；主系统侧只落 {@code chunk_manifest} 的 {@code parent_count}。</li>
 * </ul>
 */
public final class DocumentChunker {

    /** 表格片前缀里表头摘要的最大字数。 */
    static final int TABLE_HEADER_CHARS = 60;

    private DocumentChunker() {
    }

    // ══════════════════════════════════════════════════════════════
    // 入口
    // ══════════════════════════════════════════════════════════════

    /**
     * <b>查询/重建侧的唯一入口</b>：清洗 + 切分一次做完。
     *
     * <p>为什么检索时也要重跑清洗：清洗是纯函数，而切片键是"清洗后文本的指纹"——
     * 检索侧若拿原始文本算键，键就对不上索引里的键（表现为"索引明明有、却一条都召回不到"）。
     * 清洗是纯本地正则/行操作，代价远小于一次向量化。
     */
    public static List<DocStore.Chunk> chunksOf(StoredDoc doc, AiSettings settings) {
        TextCleaner cleaner = new RuleBasedTextCleaner(settings);
        CleanResult cleaned = cleaner.cleanDocument(CleanContext.of(doc, settings.getPipeline().getCleanRev()));
        return chunk(cleaned, doc, ChunkOptions.of(settings));
    }

    /** 用<b>已经算好的清洗结果</b>切分（解析链路走这条：CLEAN 阶段已经跑过一次，不重复劳动）。 */
    public static List<DocStore.Chunk> chunk(CleanResult cleaned, StoredDoc doc, ChunkOptions options) {
        if (options == null || !options.improved()) {
            return legacy(cleaned, doc, options == null ? DocStore.DEFAULT_CHUNK_CHARS : options.chunkChars());
        }
        Improved chunker = new Improved(doc, options);
        chunker.run(cleaned);
        return chunker.chunks();
    }

    /** 父子块接缝：父键 = 文档 + 页（本轮口径；升级成章节树时只改这里）。 */
    public static String parentKey(String docId, int pageNo) {
        return (docId == null ? "" : docId) + ":p=" + pageNo;
    }

    /** 父块数量（{@code chunk_manifest} 的 {@code parent_count}）。 */
    public static int parentCount(List<DocStore.Chunk> chunks) {
        Set<String> keys = new LinkedHashSet<>();
        for (DocStore.Chunk chunk : chunks) {
            if (chunk.parentKey() != null && !chunk.parentKey().isBlank()) {
                keys.add(chunk.parentKey());
            }
        }
        return keys.size();
    }

    /**
     * 父块摘要（small-to-big 的"上卷"）：同页全部子块按顺序拼接的前 {@code maxChars} 字。
     *
     * <p>为什么是"现算"而不是落库：父块是可重建物（设计 I2），落库只会带来两份真相；
     * 现算的输入就是本次检索已经装载的切片列表，零额外 IO。
     */
    public static String parentSummary(List<DocStore.Chunk> samePageChunks, int maxChars) {
        if (samePageChunks == null || samePageChunks.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DocStore.Chunk chunk : samePageChunks) {
            if (sb.length() >= maxChars) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(chunk.text() == null ? "" : chunk.text());
        }
        String text = sb.toString().strip();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /** 父块索引（{@code work/chunks/<docId>.json} 的 {@code parents[]}）：父键 + 页 + 章节路径 + 子键 + 摘要。 */
    public static List<Map<String, Object>> parentsOf(List<DocStore.Chunk> chunks, int summaryChars) {
        Map<String, List<DocStore.Chunk>> byParent = new LinkedHashMap<>();
        for (DocStore.Chunk chunk : chunks) {
            String key = chunk.parentKey() == null || chunk.parentKey().isBlank()
                    ? parentKey(chunk.docId(), chunk.pageNo()) : chunk.parentKey();
            byParent.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<DocStore.Chunk>> entry : byParent.entrySet()) {
            List<DocStore.Chunk> children = entry.getValue();
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("parent_key", entry.getKey());
            parent.put("page_no", children.get(0).pageNo());
            parent.put("section_path", children.get(0).sectionPath() == null ? "" : children.get(0).sectionPath());
            parent.put("child_count", children.size());
            List<String> keys = new ArrayList<>();
            for (DocStore.Chunk child : children) {
                keys.add(RetrievalService.chunkKey(child));
            }
            parent.put("child_keys", keys);
            parent.put("summary", parentSummary(children, summaryChars));
            out.add(parent);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // 回退口径：接入前的算法（逐字保留，改它等于改"今天的行为"）
    // ══════════════════════════════════════════════════════════════

    /**
     * 接入前的切法（{@code DocStore.iterChunks} 的原实现，逐字搬过来）。
     *
     * <p>它现在有两个用途：① {@code DocStore.iterChunks} 的兼容实现；
     * ② {@code ai.chunk.improved=false} 时的回退路径。所以<b>这段代码是"今天的行为"的定义</b>，
     * 不要"顺手优化"——要改行为就改 {@link #chunk(CleanResult, StoredDoc, ChunkOptions)}。
     */
    public static List<DocStore.Chunk> legacy(StoredDoc doc, int chunkChars) {
        int limit = chunkChars > 0 ? chunkChars : DocStore.DEFAULT_CHUNK_CHARS;
        List<DocStore.Chunk> out = new ArrayList<>();
        for (StoredDoc.PageInfo page : doc.getPages()) {
            out.addAll(legacyPage(doc, page.text().strip(), page.pageNo(), limit));
        }
        return out;
    }

    /** 回退口径（吃清洗后的文本，但切法逐字照旧）——清洗关闭时它 == 接入前的结果。 */
    static List<DocStore.Chunk> legacy(CleanResult cleaned, StoredDoc doc, int chunkChars) {
        int limit = chunkChars > 0 ? chunkChars : DocStore.DEFAULT_CHUNK_CHARS;
        List<DocStore.Chunk> out = new ArrayList<>();
        for (CleanResult.CleanPage page : cleaned.pages()) {
            out.addAll(legacyPage(doc, page.safeAfter().strip(), page.pageNo(), limit));
        }
        return out;
    }

    private static List<DocStore.Chunk> legacyPage(StoredDoc doc, String text, int pageNo, int limit) {
        List<DocStore.Chunk> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        List<String> paras = new ArrayList<>();
        for (String part : text.split("\n\\s*\n")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                paras.add(p);
            }
        }
        if (paras.size() <= 1) {
            paras.clear();
            for (String line : text.split("\n")) {
                String p = line.strip();
                if (!p.isEmpty()) {
                    paras.add(p);
                }
            }
        }
        StringBuilder buf = new StringBuilder();
        int order = 0;
        for (String para : paras) {
            if (buf.length() > 0 && buf.length() + para.length() > limit) {
                out.add(raw(doc, pageNo, buf.toString(), order++));
                buf.setLength(0);
                buf.append(para);
            } else {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(para);
            }
        }
        if (buf.length() > 0) {
            out.add(raw(doc, pageNo, buf.toString(), order));
        }
        return out;
    }

    /** 回退口径的切片：元数据按最小可用填（父键仍然给上，便于两种口径的产物形状一致）。 */
    private static DocStore.Chunk raw(StoredDoc doc, int pageNo, String text, int order) {
        return new DocStore.Chunk(doc.getDocId(), doc.getFilename(), pageNo, text, order,
                parentKey(doc.getDocId(), pageNo), false, 0, "", "");
    }

    // ══════════════════════════════════════════════════════════════
    // 改进切分
    // ══════════════════════════════════════════════════════════════

    /** 改进切分的执行体（每个文档一个实例：章节栈、页内序号、上一片文本都是文档级状态）。 */
    private static final class Improved {

        private final StoredDoc doc;
        private final ChunkOptions options;
        private final List<DocStore.Chunk> out = new ArrayList<>();
        /** 章节栈（H1→H2→H3…）：设计 §3.4.5 的 {@code section_path}。 */
        private final List<String> sections = new ArrayList<>();

        private Improved(StoredDoc doc, ChunkOptions options) {
            this.doc = doc;
            this.options = options;
        }

        private List<DocStore.Chunk> chunks() {
            return out;
        }

        private void run(CleanResult cleaned) {
            for (CleanResult.CleanPage page : cleaned.pages()) {
                chunkPage(page);
            }
        }

        private void chunkPage(CleanResult.CleanPage page) {
            String text = page.safeAfter();
            if (text.isBlank()) {
                return;
            }
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            PageState state = new PageState();
            for (Block block : blocksOf(page, lines)) {
                if (block.table() && options.tablesWhole()) {
                    emitTable(page, lines, block, state);
                } else {
                    emitText(page, lines, block, state);
                }
            }
        }

        /**
         * 行 → 块：表格区间成块，其余是文本块（顺序保持，保证"页内顺序 = 阅读顺序"）。
         *
         * <p>表格区间取<b>两个来源的并集</b>：① 清洗阶段 C5 标记的区间（走 CleanResult 传下来）；
         * ② 这里在<b>清洗后文本</b>上再跑一次竖线结构识别（{@code TextStructure.pipeTableRanges}）。
         * 为什么要②：清洗关闭是默认口径，那时根本没有 C5 标记——而"表格整块不截断"是切分自己的
         * 验收项（设计 §3.4.2 也把 markdown 结构正则列为判据之一），不能依赖"用户先打开清洗"。
         */
        private List<Block> blocksOf(CleanResult.CleanPage page, List<String> lines) {
            Set<Integer> tableLines = new LinkedHashSet<>();
            if (options.tablesWhole()) {
                for (CleanResult.TableRange range : page.tables() == null ? List.<CleanResult.TableRange>of()
                        : page.tables()) {
                    for (int i = range.startLine(); i <= range.endLine(); i++) {
                        tableLines.add(i);
                    }
                }
                for (int[] range : TextStructure.pipeTableRanges(lines)) {
                    for (int i = range[0]; i <= range[1]; i++) {
                        tableLines.add(i);
                    }
                }
            }
            List<Block> blocks = new ArrayList<>();
            int index = 0;
            while (index < lines.size()) {
                boolean table = tableLines.contains(index);
                int start = index;
                while (index < lines.size() && tableLines.contains(index) == table) {
                    index++;
                }
                blocks.add(new Block(table, start, index - 1));
            }
            return blocks;
        }

        /** 文本块：切单元 → 按边界/目标长度打包 → 每片带上章节路径与父键。 */
        private void emitText(CleanResult.CleanPage page, List<String> lines, Block block, PageState state) {
            StringBuilder buf = new StringBuilder();
            String bufSection = "";
            for (Unit unit : unitsOf(lines, block)) {
                boolean boundary = options.structureAware() && unit.boundary();
                if (unit.text().length() > options.maxCharLen()) {
                    flush(page, buf, bufSection, state);
                    buf.setLength(0);
                    for (String piece : splitLong(unit.text())) {
                        emit(page, piece, false, 0, unit.sectionPath(), state);
                    }
                    continue;
                }
                if (buf.length() > 0 && (boundary
                        || buf.length() + unit.text().length() + 1 > options.chunkChars())) {
                    flush(page, buf, bufSection, state);
                    buf.setLength(0);
                }
                if (buf.length() == 0) {
                    bufSection = unit.sectionPath();
                    // 结构边界处**不加重叠前缀**：标题/条款本身就是一个天然的开头，
                    // 把上一片的尾巴贴到它前面会让"标题是片首"这条不变量失效
                    // （结果就是"在标题与正文之间切"——正是设计 §3.4.1 要避免的）。
                    String overlap = boundary ? "" : overlapFor(state);
                    if (!overlap.isEmpty()) {
                        buf.append(overlap).append('\n');
                    }
                } else {
                    buf.append('\n');
                }
                buf.append(unit.text());
            }
            flush(page, buf, bufSection, state);
        }

        /** 表格块：整块成片；超上限时按行切并重复表头，每片前置 {@code 【表格：…】}。 */
        private void emitTable(CleanResult.CleanPage page, List<String> lines, Block block, PageState state) {
            List<String> rows = new ArrayList<>();
            for (int i = block.start(); i <= block.end(); i++) {
                rows.add(lines.get(i));
            }
            String whole = String.join("\n", rows).strip();
            if (whole.isEmpty()) {
                return;
            }
            String sectionPath = currentSectionPath();
            if (whole.length() <= options.maxCharLen()) {
                emit(page, whole, true, rows.size(), sectionPath, state);
                return;
            }
            // 超长表格：表头 = 首行（+ 紧跟的分隔行），其余按行切片，每片重复表头
            int headerRows = rows.size() > 1 && TextStructure.isTableSeparator(rows.get(1)) ? 2 : 1;
            headerRows = Math.min(headerRows, rows.size());
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < headerRows; i++) {
                if (header.length() > 0) {
                    header.append('\n');
                }
                header.append(rows.get(i));
            }
            String prefix = "【表格：" + TextStructure.tableHeader(rows.get(0), TABLE_HEADER_CHARS) + "】";
            StringBuilder buf = new StringBuilder();
            int bodyRows = 0;
            for (int i = headerRows; i < rows.size(); i++) {
                String candidate = buf.length() == 0 ? rows.get(i) : buf + "\n" + rows.get(i);
                int length = prefix.length() + 1 + header.length() + 1 + candidate.length();
                if (length > options.maxCharLen() && buf.length() > 0) {
                    emit(page, prefix + "\n" + header + "\n" + buf, true, bodyRows + headerRows, sectionPath, state);
                    buf.setLength(0);
                    bodyRows = 0;
                }
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(rows.get(i));
                bodyRows++;
            }
            if (buf.length() > 0) {
                emit(page, prefix + "\n" + header + "\n" + buf, true, bodyRows + headerRows, sectionPath, state);
            }
        }

        /**
         * 单元化：{@code 有空行 ⇒ 按段落}（段落内的行用 {@code \n} 保留原结构）；
         * {@code 整块没有空行 ⇒ 按行成单元}——与接入前同一个口径，
         * 这样"段落 ≤ 硬上限"的页面两种切法逐字一致（回归用例钉住）。
         */
        private List<Unit> unitsOf(List<String> lines, Block block) {
            boolean hasBlank = false;
            for (int i = block.start(); i <= block.end(); i++) {
                if (lines.get(i).isBlank()) {
                    hasBlank = true;
                    break;
                }
            }
            List<Unit> out = new ArrayList<>();
            if (hasBlank) {
                List<Integer> paragraph = new ArrayList<>();
                String section = "";
                for (int i = block.start(); i <= block.end(); i++) {
                    if (lines.get(i).isBlank()) {
                        if (!paragraph.isEmpty()) {
                            out.add(unitOf(lines, paragraph, section));
                            paragraph.clear();
                        }
                        continue;
                    }
                    updateSections(lines.get(i));
                    if (paragraph.isEmpty()) {
                        section = currentSectionPath();
                    }
                    paragraph.add(i);
                }
                if (!paragraph.isEmpty()) {
                    out.add(unitOf(lines, paragraph, section));
                }
                return out;
            }
            for (int i = block.start(); i <= block.end(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                updateSections(line);
                out.add(new Unit(line.strip(), currentSectionPath(), TextStructure.isBoundary(line)));
            }
            return out;
        }

        private Unit unitOf(List<String> lines, List<Integer> indexes, String sectionPath) {
            StringBuilder sb = new StringBuilder();
            for (int index : indexes) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(index));
            }
            String text = sb.toString().strip();
            boolean boundary = !indexes.isEmpty() && TextStructure.isBoundary(lines.get(indexes.get(0)));
            return new Unit(text, sectionPath, boundary);
        }

        /** 超长段落二次切：按句末标点切，单片仍超上限时按字符硬切（极端兜底，不静默超限）。 */
        private List<String> splitLong(String text) {
            List<String> pieces = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            for (String sentence : TextStructure.splitSentences(text)) {
                if (sentence.length() > options.maxCharLen()) {
                    if (buf.length() > 0) {
                        pieces.add(buf.toString());
                        buf.setLength(0);
                    }
                    for (int start = 0; start < sentence.length(); start += options.maxCharLen()) {
                        pieces.add(sentence.substring(start,
                                Math.min(sentence.length(), start + options.maxCharLen())));
                    }
                    continue;
                }
                if (buf.length() > 0 && buf.length() + sentence.length() > options.maxCharLen()) {
                    pieces.add(buf.toString());
                    buf.setLength(0);
                }
                buf.append(sentence);
            }
            if (buf.length() > 0) {
                pieces.add(buf.toString());
            }
            return pieces;
        }

        /** 重叠前缀：上一片的尾部，按句回退（最多 {@code overlap-chars}，且不把本片顶过上限）。 */
        private String overlapFor(PageState state) {
            int overlapChars = options.overlapChars();
            if (overlapChars <= 0 || state.lastEmitted == null || state.lastWasTable) {
                return "";
            }
            String tail = tailOf(state.lastEmitted, overlapChars);
            int budget = Math.max(0, options.maxCharLen() - 1);
            if (tail.length() > budget) {
                tail = tail.substring(tail.length() - budget);
            }
            return tail.strip();
        }

        private void flush(CleanResult.CleanPage page, StringBuilder buf, String sectionPath, PageState state) {
            if (buf.length() == 0) {
                return;
            }
            String text = buf.toString().strip();
            buf.setLength(0);
            if (!text.isEmpty()) {
                emit(page, text, false, 0, sectionPath, state);
            }
        }

        private void emit(CleanResult.CleanPage page, String text, boolean table, int tableRows,
                          String sectionPath, PageState state) {
            String body = text.strip();
            if (body.isEmpty()) {
                return;
            }
            if (body.length() > options.maxCharLen()) {
                // 兜底：任何路径都不许产出超过硬上限的切片（验收口径）
                for (String piece : splitLong(body)) {
                    emit(page, piece, table, tableRows, sectionPath, state);
                }
                return;
            }
            out.add(new DocStore.Chunk(doc.getDocId(), doc.getFilename(), page.pageNo(), body,
                    state.order++, parentKey(doc.getDocId(), page.pageNo()), table, tableRows,
                    sectionPath == null ? "" : sectionPath, page.source() == null ? "" : page.source()));
            state.lastEmitted = body;
            state.lastWasTable = table;
        }

        private void updateSections(String line) {
            int level = TextStructure.headingLevel(line);
            if (level <= 0 || level == 6) {
                return;   // 最细一级标题只做切分边界，不进章节路径（避免把普通短行写进路径）
            }
            while (sections.size() >= level) {
                sections.remove(sections.size() - 1);
            }
            sections.add(line.strip());
        }

        private String currentSectionPath() {
            return String.join(" > ", sections);
        }

        /** 页内状态：片序号、上一片文本与类型（重叠用）——每页一个，重叠因此天然不跨页。 */
        private static final class PageState {
            private int order;
            private String lastEmitted;
            private boolean lastWasTable;
        }
    }

    /** 行块：表格块或文本块（{@code start}/{@code end} 为 0-based 行号，含两端）。 */
    private record Block(boolean table, int start, int end) {
    }

    /** 切分单元（段落，或"整块无空行"时的单行）：文本 + 章节路径 + 是否边界。 */
    private record Unit(String text, String sectionPath, boolean boundary) {
    }

    /** 取尾部窗口（按句末标点回退，与设计 §3.4.3 的口径一致）。 */
    static String tailOf(String text, int window) {
        if (text == null || text.isEmpty() || window <= 0) {
            return "";
        }
        if (text.length() <= window) {
            return text;
        }
        String tail = text.substring(text.length() - window);
        // 按句回退：在窗口内找最后一个句末标点，从它之后开始重叠（找不到就用窗口本身）
        int cut = -1;
        for (int i = 0; i < tail.length(); i++) {
            if ("。！？；!?;".indexOf(tail.charAt(i)) >= 0) {
                cut = i;
            }
        }
        return cut >= 0 && cut + 1 < tail.length() ? tail.substring(cut + 1) : tail;
    }
}
