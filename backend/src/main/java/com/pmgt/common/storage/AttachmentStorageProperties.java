package com.pmgt.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 附件存储配置（application.yml app.storage.*，均可用环境变量注入）：
 *   app.storage.type = local（默认，本地磁盘 backend/uploads） | obs（华为 OBS / S3 兼容）
 *   app.storage.obs.endpoint/bucket/ak/sk
 */
@Data
@ConfigurationProperties(prefix = "app.storage")
public class AttachmentStorageProperties {

    /** local | obs */
    private String type = "local";

    private Obs obs = new Obs();

    @Data
    public static class Obs {
        /** OBS 服务地址，含协议，如 https://obs.lhim.com */
        private String endpoint = "";
        /** 桶名，如 pdmsbucket */
        private String bucket = "";
        private String ak = "";
        private String sk = "";
        /**
         * 对象 key 前缀（桶内目录）。本项目附件对象统一存于桶内 uploads/ 下
         * （与本地 uploads 目录相对结构一致：file_path=2026/09/x 对应 key=uploads/2026/09/x）；
         * 如需桶根直存可置空。
         */
        private String prefix = "uploads";
    }
}
