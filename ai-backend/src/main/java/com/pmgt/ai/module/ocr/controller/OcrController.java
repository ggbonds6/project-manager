package com.pmgt.ai.module.ocr.controller;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.common.web.TempUploads;
import com.pmgt.ai.module.doc.DocumentReader;
import com.pmgt.ai.module.doc.DocumentText;
import com.pmgt.ai.module.ocr.PdfInfo;
import com.pmgt.ai.module.ocr.PdfReader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只做识别、不调模型的两个接口（摸底 / 对照用）。
 *
 * <p>与 `/documents`、`/upload-tasks` 的区别：这里**不落库**，只把识别结果返回——
 * 排查"是识别问题还是模型问题"时用它最快。
 */
@RestController
@RequestMapping("/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final AiSettings settings;
    private final PdfReader pdfReader;
    private final DocumentReader documentReader;
    private final TempUploads tempUploads;

    /** 快速判断 PDF 类型与规模（文本型 / 扫描件、页数、字符数）。 */
    @PostMapping("/pdf-info")
    public Map<String, Object> pdfInfo(@RequestParam("file") MultipartFile file) {
        Path path = tempUploads.save(file);
        try {
            if (!path.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                throw ApiException.badRequest("该接口仅接受 PDF");
            }
            PdfInfo info = pdfReader.read(path);
            Map<String, Object> data = new LinkedHashMap<>();
            // 返回**原始文件名**，不要把内部临时名（带 uuid 前缀）暴露给调用方
            data.put("file", tempUploads.originalName(file));
            data.put("pages", info.pageCount());
            data.put("text_chars", info.textChars());
            data.put("chars_per_page", Math.round(info.charsPerPage() * 10) / 10.0);
            data.put("kind", info.kind(settings.getOcr().getScannedCharThreshold()));
            data.put("error", info.error());
            return ApiResponse.ok(data);
        } finally {
            tempUploads.deleteQuietly(path);
        }
    }

    /**
     * 识别单份文件（文本型 PDF 直接取文本层；扫描件/图片走平台 OCR）。
     *
     * <p>返回里带 `failed_pages` 与 `notes`：**没有本地兜底引擎了**，失败页会静默变空白，
     * 所以必须让调用方看得见。
     */
    @PostMapping("/file")
    public Map<String, Object> ocrFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi,
            @RequestParam(name = "force_ocr", defaultValue = "false") boolean forceOcr) {
        Path path = tempUploads.save(file);
        try {
            DocumentText doc = documentReader.read(path, dpi > 0 ? dpi : null, forceOcr, ProgressFn.NONE);
            if (doc.getError() != null && !doc.getError().isBlank()) {
                throw ApiException.badRequest(doc.getError());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", doc.getKind());
            data.put("pages", doc.pageCount());
            data.put("text", doc.text());
            data.put("engine", doc.getEngine());
            data.put("dpi", doc.getDpi());
            data.put("image_format", doc.getImageFormat());
            data.put("elapsed", Math.round(doc.getElapsed() * 100) / 100.0);
            data.put("failed_pages", doc.failedPages());
            data.put("notes", doc.getNotes());
            return ApiResponse.ok(data);
        } finally {
            tempUploads.deleteQuietly(path);
        }
    }
}
