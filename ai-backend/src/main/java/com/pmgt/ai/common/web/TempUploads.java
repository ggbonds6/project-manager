package com.pmgt.ai.common.web;

import com.pmgt.ai.common.config.AiSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传文件的落盘与清理。
 *
 * <p>为什么先落临时文件而不是直接读进内存：附件里常有几十上百 MB 的扫描件 PDF，
 * 全量进内存既浪费又容易 OOM；解析器（PDFBox / 平台 OCR）本来就需要文件或字节流。
 *
 * <p>上限 300MB 与 Python 版 `api.py` 的 `MAX_UPLOAD_MB` 一致：超过就 413，
 * 让使用者知道是"文件太大"而不是"系统繁忙"。
 */
@Component
@RequiredArgsConstructor
public class TempUploads {

    public static final long MAX_UPLOAD_BYTES = 300L * 1024 * 1024;

    private final AiSettings settings;

    /** 保存到 {@code workDir/tmp/}，返回临时路径。文件名带 uuid 前缀，避免同名互相覆盖。 */
    public Path save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("没有收到文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.tooLarge("文件超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB");
        }
        Path dir = settings.getWorkDir().resolve("tmp");
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    + "-" + safeName(file.getOriginalFilename()));
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new ApiException(500, "保存上传文件失败：" + e.getMessage());
        }
    }

    /** 只取文件名部分并去掉路径分隔符，防止 path traversal。 */
    public String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[\\p{Cntrl}]", "_");
    }

    /** 返回给调用方的原始文件名（不带临时前缀）。 */
    public String originalName(MultipartFile file) {
        return safeName(file.getOriginalFilename());
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响业务，下次启动/覆盖时会清；但绝不能因此让请求失败
        }
    }
}
