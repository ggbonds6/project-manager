package com.pmgt.common.storage;

import org.springframework.http.CacheControl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 附件预览/下载的缓存头（{@code Cache-Control} + {@code ETag} + {@code If-None-Match}）工具。
 *
 * <h2>为什么必须是 {@code private}</h2>
 * <p>附件下载的 URL 上带 {@code token} 查询参数（{@code AuthFilter} 为
 * {@code <a href>} / {@code <img>} 这类不能带 Header 的场景留的兜底），也就是说
 * <b>URL 本身就是带凭据的</b>。放进共享缓存（CDN、企业代理）等于把某个人的下载凭据
 * 与私有附件一起缓存给其他人。所以只允许 {@code private}（仅浏览器私有缓存）。
 *
 * <h2>为什么 ETag 由三样东西算</h2>
 * <ul>
 *   <li>本地盘：{@code file_path + size + lastModified}；</li>
 *   <li>OBS：{@code file_path + size + 对象 ETag}（对象 ETag 是内容哈希，最可靠）；</li>
 *   <li>取不到元信息：退回 {@code file_path + size}——精度差一点，但预览不能因此不可用。</li>
 * </ul>
 * 三样都参与哈希（而不是直接拼进 ETag 头）：{@code file_path} 可能含中文/空格，
 * 直接做 ETag 值需要额外转义；哈希成 64 位十六进制最省心，且不会泄漏服务器路径。
 *
 * <p>只依赖字符串/数值（不依赖 {@code Attachment} 实体）：本工具属于存储层的通用能力，
 * 由调用方（附件接口）把 {@code file_path} 与 {@code file_size} 传进来即可。
 */
public final class AttachmentCacheSupport {

    /** 私有缓存时长：1 小时（用户切全屏、来回翻页期间不必重下）。 */
    public static final long MAX_AGE_SECONDS = 3600;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AttachmentCacheSupport() {
    }

    /**
     * 读取对象元信息；拿不到返回 {@code null}（<b>不抛异常</b>——缓存优化不该让预览失败）。
     */
    public static ObjectStat statOf(String filePath, AttachmentStorage storage) {
        if (filePath == null || storage == null) {
            return null;
        }
        try {
            return storage.stat(filePath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 生成带双引号的强 ETag（Spring 的 {@code eTag()} 要求值的形状合法，否则抛异常）。
     *
     * @param stat 可为 null（回落到 {@code file_path + file_size}）
     */
    public static String etagOf(String filePath, Long fileSize, ObjectStat stat) {
        StringBuilder raw = new StringBuilder();
        raw.append(filePath == null ? "" : filePath);
        Long size = stat != null && stat.size() != null ? stat.size() : fileSize;
        raw.append('|').append(size == null ? "" : size);
        if (stat != null && stat.etag() != null && !stat.etag().isBlank()) {
            raw.append('|').append(stat.etag().trim());
        } else if (stat != null && stat.lastModified() != null) {
            raw.append('|').append(stat.lastModified().toEpochMilli());
        }
        return "\"" + sha256Hex(raw.toString()) + "\"";
    }

    /**
     * {@code If-None-Match} 是否命中（命中就该回 304）。
     *
     * <p>按 RFC 9110 的弱比较：{@code *} 命中任意；列表逐项比较；
     * 每项允许带 {@code W/} 弱前缀（浏览器的实现不统一，两种都要认）。
     */
    public static boolean isNotModified(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null || etag.isBlank()) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        String target = opaque(etag);
        for (String part : ifNoneMatch.split(",")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if ("*".equals(candidate) || opaque(candidate).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 缓存策略：{@code private, max-age=3600}（不能进共享缓存，见类注释）。 */
    public static CacheControl cacheControl() {
        return CacheControl.maxAge(Duration.ofSeconds(MAX_AGE_SECONDS)).cachePrivate();
    }

    /** 去掉可选的 {@code W/} 前缀后比较（引号保留在值里，两侧形状一致）。 */
    private static String opaque(String tag) {
        String t = tag.trim();
        if (t.regionMatches(true, 0, "W/", 0, 2)) {
            t = t.substring(2).trim();
        }
        return t;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                out[i * 2] = HEX[b >>> 4];
                out[i * 2 + 1] = HEX[b & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JRE 被动过；此时退回内容摘要，
            // 仍能保证"同一文件同一次部署内一致"（ETag 只需自洽，不需跨进程稳定）
            return Integer.toHexString(value.hashCode());
        }
    }
}
