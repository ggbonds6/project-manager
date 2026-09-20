package com.pmgt.ai.module.doc.controller;

import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.common.web.TempUploads;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.store.DocStore;
import com.pmgt.ai.module.store.StoredDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }
}
