package com.pmgt.common.storage;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * 华为 OBS 附件存储（app.storage.type=obs 时启用）。
 * 适配本环境要求：通用 S3 协议 + path-style + **忽略证书校验**（自签证书，
 * ObsConfiguration.setValidateCertificate(false)，SDK 默认即不校验）。
 * ObsClient 线程安全，作为单例复用。
 */
@Slf4j
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
            // 打出来是为了「自证找了哪个 key」：Controller 会把 FileNotFoundException 换成
            // 用户可见的 404「附件文件缺失」，计算出的完整 key 就此消失，线上无法判断
            // 到底是文件真的没上传，还是 prefix/bucket 与当初上传时不一致。
            // ⚠️ 只打 bucket / key / file_path（不含 AK/SK）。
            log.warn("OBS 附件读取失败：bucket={}，object key={}（原始 file_path={}）"
                            + "——对象不存在：请核对 APP_STORAGE_OBS_PREFIX / APP_STORAGE_OBS_BUCKET 是否与当初上传时一致"
                            + "（对象 key = prefix + '/' + file_path）",
                    bucket, fullKey, relKey);
            throw new FileNotFoundException(fullKey);
        }
        try {
            ObsObject obj = client.getObject(bucket, fullKey);
            return obj.getObjectContent();
        } catch (Exception e) {
            throw new RuntimeException("OBS 读取附件失败[" + fullKey + "]: " + e.getMessage(), e);
        }
    }

    /**
     * OBS 对象元信息：对象自带的 {@code ETag}（内容哈希）+ 大小（+ 最后修改时间）。
     *
     * <p>优先用 ETag 而不是"大小 + 时间"：对象存储的 ETag 就是内容标识，
     * 同一 key 被覆盖上传后一定会变，而时间戳精度/时区在各 SDK 上并不一致。
     *
     * <p>任何失败（对象不存在、网络不通、无 GetObjectMetadata 权限）都返回 null：
     * ETag 只是缓存优化，绝不能让它把一次正常的预览变成 500。
     */
    @Override
    public ObjectStat stat(String relKey) {
        String fullKey = key(relKey);
        try {
            ObjectMetadata md = client.getObjectMetadata(bucket, fullKey);
            if (md == null) {
                return null;
            }
            return new ObjectStat(md.getEtag(), md.getContentLength(),
                    md.getLastModified() == null ? null : md.getLastModified().toInstant());
        } catch (Exception e) {
            // ⚠️ 只打 bucket / key（不含 AK/SK），与 open() 的诊断口径一致
            log.debug("OBS 读取对象元信息失败（ETag 将回落到 file_path+size）：bucket={} key={} - {}",
                    bucket, fullKey, e.getMessage());
            return null;
        }
    }
}
