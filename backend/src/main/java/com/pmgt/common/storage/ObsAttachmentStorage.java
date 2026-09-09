package com.pmgt.common.storage;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
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
        conf.setSocketTimeout(60_000);
        conf.setConnectionTimeout(15_000);
        this.client = new ObsClient(cfg.getAk(), cfg.getSk(), conf);
        this.bucket = cfg.getBucket();
    }

    @Override
    public void save(String relKey, InputStream in, long size) {
        try {
            ObjectMetadata md = new ObjectMetadata();
            md.setContentLength(size);
            client.putObject(bucket, relKey, in, md);
        } catch (Exception e) {
            throw new RuntimeException("OBS 保存附件失败[" + relKey + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String relKey) throws FileNotFoundException {
        boolean exists;
        try {
            exists = client.doesObjectExist(bucket, relKey);
        } catch (Exception e) {
            throw new RuntimeException("OBS 检查附件失败[" + relKey + "]: " + e.getMessage(), e);
        }
        if (!exists) {
            throw new FileNotFoundException(relKey);
        }
        try {
            ObsObject obj = client.getObject(bucket, relKey);
            return obj.getObjectContent();
        } catch (Exception e) {
            throw new RuntimeException("OBS 读取附件失败[" + relKey + "]: " + e.getMessage(), e);
        }
    }
}
