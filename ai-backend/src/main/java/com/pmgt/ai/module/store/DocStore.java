package com.pmgt.ai.module.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.module.doc.DocumentText;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 文档库：一个文档一个 JSON（{@code workDir/docs/<doc_id>.json}），把解析过的文档持久化。
 *
 * <p>为什么需要这一层（照搬 Python 版的理由）：
 * <ol>
 *   <li><b>不重复解析</b>：OCR 一份 25 页扫描件要上百秒，解析一次就该存下来；</li>
 *   <li><b>问答的检索基础</b>：模型不可能把整篇文档塞进上下文，必须能"按需检索段落"；</li>
 *   <li><b>为向量化预留</b>：切片由 {@link #iterChunks} 现算，将来换成 OpenSearch 只替换检索实现，
 *       问答编排与提示词都不用动。</li>
 * </ol>
 *
 * <p>为什么用文件而不是数据库：当前阶段是"验证能力"，引入数据库会多一层运维；每个文档一个 JSON
 * 也便于人工查看与排错。将来量大或需要并发写时换实现即可（对外接口保持 save/get/list/delete）。
 */
public class DocStore {

    /** 单个检索单元的目标字数。太小则语义不完整，太大则检索不精准。 */
    public static final int DEFAULT_CHUNK_CHARS = 500;

    /** doc_id 只允许十六进制字符——防路径穿越，与 Python 版 {@code _path} 一致。 */
    private static final Pattern DOC_ID_OK = Pattern.compile("[0-9a-fA-F]+");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final DateTimeFormatter UPLOADED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path root;

    /** 默认落 {@code settings.workDir/docs}；测试用 {@code @TempDir} 显式传 root。 */
    public DocStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("文档库目录创建失败：" + root, e);
        }
    }

    /** 允许从 {@code ai.work-dir} 直接构造，省掉每个调用方都拼一次 "docs"。 */
    public static DocStore underWorkDir(Path workDir) {
        return new DocStore(workDir.resolve("docs"));
    }

    private Path path(String docId) {
        return root.resolve(sanitize(docId) + ".json");
    }

    /** 消毒：只保留十六进制字符（{@code ../..} 之类会被抹平），再校验剩余非空。 */
    private static String sanitize(String docId) {
        String safe = docId == null ? "" : docId.replaceAll("[^0-9a-fA-F]", "");
        if (!DOC_ID_OK.matcher(safe).matches()) {
            throw new IllegalArgumentException("非法 doc_id（只允许十六进制字符）：" + docId);
        }
        return safe;
    }

    // ── 写 ───────────────────────────────────────────────────────

    /**
     * 把一个已解析的文档入库，返回入库结果。
     *
     * <p>文档 ID 用 16 位 hex（对齐 Python：{@code uuid4().hex[:16]}）——短、可读、不撞车。
     */
    public StoredDoc save(String filename, DocumentText doc, long sizeBytes) {
        String docId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        StoredDoc stored = StoredDoc.of(
                docId,
                filename,
                doc,
                sizeBytes,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(UPLOADED_AT));
        write(stored);
        return stored;
    }

    /** 覆盖写（同一 doc_id 再写一次是更新，例如人工修正元信息）。 */
    public void write(StoredDoc doc) {
        Path target = path(doc.getDocId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, MAPPER.writeValueAsString(doc), StandardCharsets.UTF_8);
            // 原子替换：避免"写一半被读到"——列表接口与检索都会并发读这些文件
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("文档写入失败：" + target, e);
        }
    }

    // ── 读 ───────────────────────────────────────────────────────

    /** 单文档；不存在或文件损坏都返回 {@code null}（单个文件损坏不该影响整体）。 */
    public StoredDoc get(String docId) {
        Path path = path(docId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), StoredDoc.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出全部文档的<b>元信息</b>（不带全文），最近上传在前。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredDoc doc : allDocs()) {
            items.add(doc.meta());
        }
        items.sort(Comparator.comparing(
                (Map<String, Object> m) -> String.valueOf(m.getOrDefault("uploaded_at", "")),
                Comparator.naturalOrder()).reversed());
        return items;
    }

    /** 删除：真删掉文件返回 true，本来就不存在返回 false。 */
    public boolean delete(String docId) {
        Path path = path(docId);
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("文档删除失败：" + path, e);
        }
    }

    /** 遍历全部文档（损坏的文件静默跳过——排错时不该被一个坏文件卡死）。 */
    public Iterable<StoredDoc> allDocs() {
        List<StoredDoc> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                StoredDoc doc = readQuietly(file);
                if (doc != null) {
                    docs.add(doc);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("文档库遍历失败：" + root, e);
        }
        return docs;
    }

    private StoredDoc readQuietly(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), StoredDoc.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Path root() {
        return root;
    }

    // ── 切片（检索的最小单元）─────────────────────────────────────

    /**
     * 切片单元：{@code docId / filename / pageNo / text} + <b>检索与溯源需要的元数据</b>。
     *
     * <p>元数据是 P1-α 补的（设计 §3.4.5），它们解决四件事：
     * <ul>
     *   <li>{@code orderInPage}：页内顺序（重叠去重、结果排序、人工复核都要它）；</li>
     *   <li>{@code parentKey}：<b>父子块接缝</b>（small-to-big 上卷到父块摘要）；</li>
     *   <li>{@code isTable / tableRows}：表格检索策略（整块保留，不与正文混排）；</li>
     *   <li>{@code sectionPath / source}：章节路径（升级章节树父块的接缝）与来源（文本层 / OCR）。</li>
     * </ul>
     *
     * <p>{@link #Chunk(String, String, int, String)} 这个四参构造是<b>兼容入口</b>：
     * 老调用点与测试不需要改，元数据按"无"填（{@code parentKey} 由切分阶段补）。
     */
    public record Chunk(String docId, String filename, int pageNo, String text, int orderInPage,
                        String parentKey, boolean isTable, int tableRows, String sectionPath, String source) {

        /** 兼容构造（无元数据的切片）：老调用点与测试用。 */
        public Chunk(String docId, String filename, int pageNo, String text) {
            this(docId, filename, pageNo, text, 0, null, false, 0, "", "");
        }

        public int charLen() {
            return text == null ? 0 : text.length();
        }
    }

    public List<Chunk> iterChunks(StoredDoc doc) {
        return iterChunks(doc, DEFAULT_CHUNK_CHARS);
    }

    /**
     * 把文档切成检索单元，<b>不跨页</b>，页号必须带着走。
     *
     * <p>为什么页号一定不能丢：检索结果要能标注来源（{@code [P2]}），否则提示词里"必须标来源"
     * 的约束无从落地——答案看起来对但没有出处，等于没法复核。
     *
     * <p>⚠️ <b>这是"接入前的切法"，且已被冻结为回退口径</b>（P1-α 起）：
     * 真正的切分入口是 {@code DocumentChunker.chunksOf(doc, settings)}（结构感知 + 表格整块 +
     * 重叠 + 硬上限）。本方法保留有两个不可替代的用途：
     * ① {@code ai.chunk.improved=false} 时的回退；② 测试里的"今天的行为"对照基准。
     * 实现逐字搬到 {@code DocumentChunker.legacy}（单一实现，不复制两份）。
     */
    public List<Chunk> iterChunks(StoredDoc doc, int chunkChars) {
        return com.pmgt.ai.module.chunk.DocumentChunker.legacy(doc, chunkChars);
    }

    /**
     * 全库（或单文档）切片，<b>回退口径</b>（= {@link #iterChunks(StoredDoc)} 的批量版）。
     *
     * <p>⚠️ P1-α 起检索链路<b>不再走这里</b>：它用的是 {@code DocumentChunker.chunksOf(doc, settings)}
     * （清洗 + 结构感知 + 表格整块 + 重叠 + 硬上限），与入库/重建同一口径。
     * 本方法保留是因为它是"接入前口径"的公开入口（历史文档与对照实验引用它，见
     * {@code docs/RAG流程完整性评估.md} §2 的证据行）——<b>新代码不要用它</b>。
     */
    public List<Chunk> loadChunks(String docId) {
        List<StoredDoc> docs = new ArrayList<>();
        if (docId != null && !docId.isBlank()) {
            StoredDoc one = get(docId);
            if (one != null) {
                docs.add(one);
            }
        } else {
            allDocs().forEach(docs::add);
        }
        List<Chunk> chunks = new ArrayList<>();
        for (StoredDoc doc : docs) {
            chunks.addAll(iterChunks(doc));
        }
        return chunks;
    }
}
