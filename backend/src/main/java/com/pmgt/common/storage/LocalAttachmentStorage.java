package com.pmgt.common.storage;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
            // 打绝对路径是为了「自证找了哪个文件」：Controller 会把 FileNotFoundException 换成
            // 用户可见的 404「附件文件缺失」，路径就此消失，线上无法判断是文件真的没上传，
            // 还是挂载卷/上传目录与当初写盘时不是同一个。
            log.warn("附件文件不存在：绝对路径={}（原始 file_path={}，存储根目录={}）；"
                            + "请核对 UPLOAD_DIR / UPLOAD_VOLUME",
                    target, relKey, root);
            throw new FileNotFoundException(relKey);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new RuntimeException("读取附件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 本地文件的元信息：大小 + mtime（本地盘没有内容哈希，故 {@code etag} 为 null）。
     *
     * <p>取不到就返回 null 并落一条 debug 日志：调用方会回落到
     * {@code file_path + file_size}，此时"文件被替换但大小相同"仍可能命中旧缓存——
     * 这是可接受的取舍（预览优先可用），但值得留痕以便排查。
     */
    @Override
    public ObjectStat stat(String relKey) {
        Path target = resolve(relKey);
        try {
            if (!Files.exists(target)) {
                return null;
            }
            return new ObjectStat(null, Files.size(target), Files.getLastModifiedTime(target).toInstant());
        } catch (IOException e) {
            log.debug("读取附件元信息失败（ETag 将回落到 file_path+size）：{} - {}", relKey, e.getMessage());
            return null;
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
