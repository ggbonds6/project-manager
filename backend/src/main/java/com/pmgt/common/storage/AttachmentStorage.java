package com.pmgt.common.storage;

import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * 附件存储抽象：上传/读取与后端实现解耦。
 * 现有实现：{@link LocalAttachmentStorage}（本地磁盘）、{@link ObsAttachmentStorage}（华为 OBS，S3 兼容）。
 * relKey 即 attachment.file_path（YYYY/MM/{uuid}.ext），本地为相对 upload 根的子路径，OBS 为对象 key。
 */
public interface AttachmentStorage {

    /**
     * 保存对象（存在则覆盖）。
     * @param relKey 相对 key / 路径
     * @param in     内容流（由实现负责读完/关闭）
     * @param size   字节数（对象存储需要 Content-Length）
     */
    void save(String relKey, InputStream in, long size);

    /**
     * 保存对象并上报进度（后台上传任务据此回写进度条）。
     * 默认忽略进度、直接委派 {@link #save(String, InputStream, long)}；
     * 支持的实现应覆写以提供真实进度。
     *
     * @param callback 进度回调，可为 null
     */
    default void save(String relKey, InputStream in, long size, ProgressCallback callback) {
        save(relKey, in, size);
    }

    /**
     * 打开对象输入流，调用方负责 close。
     * @throws FileNotFoundException 对象不存在
     */
    InputStream open(String relKey) throws FileNotFoundException;
}
