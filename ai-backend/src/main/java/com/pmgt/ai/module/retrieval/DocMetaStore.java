package com.pmgt.ai.module.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档过滤元数据的 side-car 存储：{@code workDir/doc-meta.json}（{@code docId → DocMeta}）。
 *
 * <p>为什么用文件而不是数据库：① 它和 {@code work/docs}、{@code work/vectors} 一样属于
 * "可重建的检索附属数据"，丢了重录即可，不该进主系统库；② 部署形态是单实例 AI 服务 + 挂卷，
 * 文件足够；③ 契约上"元数据的<b>事实源</b>在主系统的附件表"，这份文件是<b>检索副本</b>
 * （写入入口见 {@code PUT /documents/{docId}/meta}）。
 *
 * <p>并发：读写在 {@code synchronized} 里 + 临时文件原子替换（与 {@code DocStore} 同一套纪律），
 * 避免检索线程读到写了一半的 JSON。
 */
public class DocMetaStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** 默认落 {@code workDir/doc-meta.json}；测试用 {@code @TempDir} 显式传路径。 */
    public DocMetaStore(Path workDir) {
        this.file = workDir.resolve("doc-meta.json");
    }

    public Path path() {
        return file;
    }

    /** 单文档元数据；没有就返回 {@link DocMeta#EMPTY}（不编造，也不抛错）。 */
    public DocMeta get(String docId) {
        return all().getOrDefault(docId == null ? "" : docId, DocMeta.EMPTY);
    }

    /** 全部元数据（每次调用重新读盘：元数据量小、变更来自运维接口，缓存反而会引入陈旧读）。 */
    public synchronized Map<String, DocMeta> all() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, DocMeta> out = new LinkedHashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    entry.getValue().fields().forEachRemaining(field ->
                            values.put(field.getKey(), field.getValue().asText("")));
                    out.put(entry.getKey(), DocMeta.fromMap(values));
                });
            }
            return out;
        } catch (Exception e) {
            // 文件坏了当作"没有元数据"：检索会因"元数据缺失"把带约束的文档排除（宁可少召回，不可越界）
            return Map.of();
        }
    }

    /** 写入/覆盖一条元数据；返回是否新增（用于日志）。 */
    public synchronized boolean put(String docId, DocMeta meta) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        Map<String, DocMeta> current = new LinkedHashMap<>(all());
        boolean created = current.put(docId, meta == null ? DocMeta.EMPTY : meta) == null;
        write(current);
        return created;
    }

    /** 删除一条元数据；返回是否真的删掉了。 */
    public synchronized boolean remove(String docId) {
        Map<String, DocMeta> current = new LinkedHashMap<>(all());
        if (current.remove(docId) == null) {
            return false;
        }
        write(current);
        return true;
    }

    public synchronized int size() {
        return all().size();
    }

    private void write(Map<String, DocMeta> items) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            for (Map.Entry<String, DocMeta> entry : items.entrySet()) {
                ObjectNode node = root.putObject(entry.getKey());
                entry.getValue().toMap().forEach((key, value) -> node.put(key, String.valueOf(value)));
            }
            Path tmp = Files.createTempFile(file.getParent(), "doc-meta-", ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("文档元数据写入失败：" + file, e);
        }
    }
}
