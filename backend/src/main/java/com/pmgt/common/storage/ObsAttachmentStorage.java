package com.pmgt.common.storage;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 华为 OBS 附件存储（app.storage.type=obs 时启用）。
 * 适配本环境要求：通用 S3 协议 + path-style + **忽略证书校验**（自签证书，
 * ObsConfiguration.setValidateCertificate(false)，SDK 默认即不校验）。
 * ObsClient 线程安全，作为单例复用。
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "obs")
public class ObsAttachmentStorage implements AttachmentStorage {

    private final ObsClient client;
    private final String bucket;
    /** 对象 key 前缀（桶内目录，如 uploads），拼接后为完整对象 key */
    private final String keyPrefix;

    public ObsAttachmentStorage(AttachmentStorageProperties props) {
        AttachmentStorageProperties.Obs cfg = props.getObs();
        if (cfg == null || !StringUtils.hasText(cfg.getEndpoint()) || !StringUtils.hasText(cfg.getBucket())
                || !StringUtils.hasText(cfg.getAk()) || !StringUtils.hasText(cfg.getSk())) {
            throw new IllegalStateException(
                    "app.storage.type=obs 但 OBS 配置缺失，请设置 APP_STORAGE_OBS_ENDPOINT/BUCKET/AK/SK");
        }
        ObsConfiguration conf = new ObsConfiguration();
        conf.setEndPoint(cfg.getEndpoint());
        conf.setPathStyle(true);
        conf.setValidateCertificate(false); // 忽略自签证书校验
        // 大文件推送耗时可能远超 SDK 默认 60s（同步上传时代超时失败的主因之一），放宽到 5 分钟；
        // 建连仍保持较短，网络不通时可快速失败而非长时间挂起。
        conf.setSocketTimeout(300_000);
        conf.setConnectionTimeout(15_000);
        this.client = new ObsClient(cfg.getAk(), cfg.getSk(), conf);
        this.bucket = cfg.getBucket();
        String p = cfg.getPrefix();
        this.keyPrefix = (p == null || p.isBlank() || "/".equals(p.trim()))
                ? "" : p.trim().replaceAll("/+$", "");
    }

    /** relKey（file_path，如 2026/09/x.pdf）→ 桶内完整对象 key（prefix + relKey） */
    private String key(String relKey) {
        return keyPrefix.isEmpty() ? relKey : keyPrefix + "/" + relKey;
    }

    @Override
    public void save(String relKey, InputStream in, long size) {
        save(relKey, in, size, null);
    }

    @Override
    public void save(String relKey, InputStream in, long size, ProgressCallback callback) {
        String fullKey = key(relKey);
        try {
            ObjectMetadata md = new ObjectMetadata();
            md.setContentLength(size);
            PutObjectRequest request = new PutObjectRequest(bucket, fullKey, in);
            request.setMetadata(md);
            if (callback != null) {
                // 用带进度监听的重载，后台上传任务据此回写进度条
                request.setProgressListener(
                        status -> callback.onProgress(status.getTransferredBytes(), status.getTotalBytes()));
            }
            client.putObject(request);
        } catch (Exception e) {
            throw new RuntimeException("OBS 保存附件失败[" + fullKey + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String relKey) throws FileNotFoundException {
        String fullKey = key(relKey);
        boolean exists;
        try {
            exists = client.doesObjectExist(bucket, fullKey);
        } catch (Exception e) {
            throw new RuntimeException("OBS 检查附件失败[" + fullKey + "]: " + e.getMessage(), e);
        }
        if (!exists) {
            throw new FileNotFoundException(fullKey);
        }
        try {
            ObsObject obj = client.getObject(bucket, fullKey);
            return obj.getObjectContent();
        } catch (Exception e) {
            throw new RuntimeException("OBS 读取附件失败[" + fullKey + "]: " + e.getMessage(), e);
        }
    }
}
