package com.pmgt.common.storage;

import java.time.Instant;

/**
 * 存储对象的元信息（生成 ETag 用）。
 *
 * <p>为什么要有它：附件预览/下载此前<b>不发任何缓存头</b>，前端每打开一次预览、
 * 每切一次全屏都要整包重下（几十 MB 的扫描件尤其明显）。要发 {@code ETag} 就必须能
 * 稳定地描述"这份文件是哪一个版本"，本地盘看 {@code size + lastModified}，
 * 对象存储（OBS）还能拿到对象自身的 {@code ETag}（内容哈希，最可靠）。
 *
 * <p>取不到（对象不存在、OBS 不可达）时返回 {@code null}：调用方回落到
 * {@code file_path + size}，宁可 ETag 精度差一点，也不能让预览因为"算不出 ETag"而失败。
 *
 * @param etag             对象存储给出的内容标识（本地存储为 null）
 * @param size             对象字节数（取到就用它，取不到用 attachment.file_size）
 * @param lastModified     最后修改时间（本地为文件 mtime）
 */
public record ObjectStat(String etag, Long size, Instant lastModified) {
}
