package com.pmgt.ai.module.doc.controller;

import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.common.web.TempUploads;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.retrieval.DocMeta;
import com.pmgt.ai.module.retrieval.DocMetaStore;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档库：上传解析入库 / 列表 / 详情 / 删除。
 *
 * <p>与 `/ocr/file` 的区别：这里会把解析结果**持久化**，作为问答检索的基础。
 * 同步接口，扫描件可能耗时数十秒到数分钟——前端要上传大文件请用 `/upload-tasks`。
 */
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentReader documentReader;
    private final DocStore docStore;
    private final DocMetaStore docMetaStore;
    private final TempUploads tempUploads;

    @PostMapping
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi) {
        Path path = tempUploads.save(file);
        try {
            DocumentText doc = documentReader.read(path, dpi > 0 ? dpi : null, false, ProgressFn.NONE);
            if (doc.getError() != null && !doc.getError().isBlank()) {
                throw ApiException.badRequest(doc.getError());
            }
            if (doc.text().isBlank()) {
                throw ApiException.badRequest(
                        "未从文件中提取到任何文本（扫描件请确认清晰度；本地 OCR 兜底已移除，平台识别失败会如实报错）");
            }
            StoredDoc stored = docStore.save(tempUploads.originalName(file), doc, sizeOf(path));
            return ApiResponse.ok(stored.meta());
        } finally {
            tempUploads.deleteQuietly(path);
        }
    }

    @GetMapping
    public Map<String, Object> list() {
        return ApiResponse.ok(docStore.list());
    }

    @GetMapping("/{docId}")
    public Map<String, Object> get(@PathVariable String docId) {
        StoredDoc doc = docStore.get(docId);
        if (doc == null) {
            throw ApiException.notFound("文档不存在");
        }
        return ApiResponse.ok(doc.toDict());
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        if (!docStore.delete(docId)) {
            throw ApiException.notFound("文档不存在");
        }
        return ApiResponse.ok(Map.of("deleted", docId));
    }

    /**
     * 补录/覆盖一份文档的<b>过滤元数据</b>（{@code attachment_id} / {@code project_id} /
     * {@code phase_code} / {@code security_level} / {@code doc_type} / {@code source}）。
     *
     * <p>为什么需要这个入口：这些字段是检索侧"先过滤再检索"（{@code doc_ids} / {@code attachmentIds} /
     * {@code projectId} 硬作用域）的<b>前提</b>，而切片本身没有项目/密级信息
     * （事实源在主系统的附件表：{@code attachment_id → project_id/密级}）。
     * AI 侧提供这条补录通道之后，主系统可以：① 解析入库后回填一次；
     * ② 元数据变更时<b>只回填不重解析</b>（重解析要重跑 OCR，代价高得多）。
     * 回填完调 {@code POST /admin/reindex?docIds=<id>} 即可把新元数据写进索引。
     *
     * <p>请求体是 snake_case 的键值对象（缺的字段按空处理），例如
     * {@code {"attachment_id":"8842","project_id":"55","security_level":"内部"}}。
     */
    @PutMapping("/{docId}/meta")
    public Map<String, Object> putMeta(@PathVariable String docId, @RequestBody(required = false)
                                       Map<String, Object> body) {
        if (docStore.get(docId) == null) {
            throw ApiException.notFound("文档不存在：" + docId);
        }
        DocMeta meta = DocMeta.fromMap(body);
        docMetaStore.put(docId, meta);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_id", docId);
        out.put("meta", meta.toMap());
        out.put("hint", "元数据已落 work/doc-meta.json；要让索引里的切片带上它，"
                + "请再调 POST /admin/reindex?docIds=" + docId);
        return ApiResponse.ok(out);
    }

    /** 读取已补录的元数据（没有就是空值，不编造）。 */
    @GetMapping("/{docId}/meta")
    public Map<String, Object> getMeta(@PathVariable String docId) {
        if (docStore.get(docId) == null) {
            throw ApiException.notFound("文档不存在：" + docId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_id", docId);
        out.put("meta", docMetaStore.get(docId).toMap());
        return ApiResponse.ok(out);
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }
}
