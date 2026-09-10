package com.pmgt.common.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 本地磁盘附件存储（默认，app.storage.type=local 或未配置时启用）。
 * 根目录由 app.upload-dir 指定（默认 ./uploads），与 v3.0 前行为一致。
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalAttachmentStorage implements AttachmentStorage {

    private final Path root;

    public LocalAttachmentStorage(@Value("${app.upload-dir:./uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建附件目录: " + root, e);
        }
    }

    @Override
    public void save(String relKey, InputStream in, long size) {
        Path target = resolve(relKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void save(String relKey, InputStream in, long size, ProgressCallback callback) {
        Path target = resolve(relKey);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[8192];
                long transferred = 0L;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    transferred += n;
                    if (callback != null) {
                        callback.onProgress(transferred, size);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String relKey) throws FileNotFoundException {
        Path target = resolve(relKey);
        if (!Files.exists(target)) {
            throw new FileNotFoundException(relKey);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new RuntimeException("读取附件失败: " + e.getMessage(), e);
        }
    }

    private Path resolve(String relKey) {
        Path target = root.resolve(relKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法附件路径: " + relKey);
        }
        return target;
    }
}
