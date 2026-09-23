package com.pmgt.common.storage;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttachmentCacheSupport} 测试：预览/下载的 ETag 与 304 判定。
 *
 * <p>体验缺陷的回归：附件预览原先一个缓存头都不发，前端每次打开预览、每次切全屏都整包重下。
 * 这里钉住三件事：ETag 稳定、内容/时间变了 ETag 就变、{@code If-None-Match} 各种写法都认。
 */
class AttachmentCacheSupportTest {

    private static final String PATH = "2026/09/abc.pdf";

    @Test
    void 同一文件同一版本ETag稳定() {
        ObjectStat stat = new ObjectStat(null, 1024L, Instant.ofEpochMilli(1_700_000_000_000L));

        assertEquals(AttachmentCacheSupport.etagOf(PATH, 1024L, stat),
                AttachmentCacheSupport.etagOf(PATH, 1024L, stat));
        // 形状必须是带双引号的强 ETag（Spring 的 eTag() 不接受无引号的值）
        assertTrue(AttachmentCacheSupport.etagOf(PATH, 1024L, stat).matches("\"[0-9a-f]{64}\""),
                AttachmentCacheSupport.etagOf(PATH, 1024L, stat));
    }

    @Test
    void 大小或修改时间变化时ETag变化() {
        String base = AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat(null, 1024L, Instant.ofEpochMilli(1_700_000_000_000L)));

        assertNotEquals(base, AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat(null, 2048L, Instant.ofEpochMilli(1_700_000_000_000L))));
        assertNotEquals(base, AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat(null, 1024L, Instant.ofEpochMilli(1_700_000_001_000L))));
        assertNotEquals(base, AttachmentCacheSupport.etagOf("2026/09/def.pdf", 1024L,
                new ObjectStat(null, 1024L, Instant.ofEpochMilli(1_700_000_000_000L))));
    }

    @Test
    void 对象存储优先用对象etag() {
        String withEtag = AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat("d41d8cd98f00b204e9800998ecf8427e", 1024L, Instant.ofEpochMilli(1L)));
        String otherMtime = AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat("d41d8cd98f00b204e9800998ecf8427e", 1024L, Instant.ofEpochMilli(999_999L)));

        assertEquals(withEtag, otherMtime, "有对象 etag 时不该再让 mtime 影响 ETag");
        assertNotEquals(withEtag, AttachmentCacheSupport.etagOf(PATH, 1024L,
                new ObjectStat("00000000000000000000000000000000", 1024L, Instant.ofEpochMilli(1L))));
    }

    @Test
    void 取不到元信息时退回路径加大小() {
        String etag = AttachmentCacheSupport.etagOf(PATH, 1024L, null);

        assertTrue(etag.matches("\"[0-9a-f]{64}\""), etag);
        assertEquals(etag, AttachmentCacheSupport.etagOf(PATH, 1024L, null));
        // 连 file_size 都没有（历史数据）也不能抛异常
        assertTrue(AttachmentCacheSupport.etagOf(PATH, null, null).matches("\"[0-9a-f]{64}\""));
        assertTrue(AttachmentCacheSupport.etagOf(null, null, null).matches("\"[0-9a-f]{64}\""));
    }

    @Test
    void storage拿不到元信息时返回null而不抛异常() {
        AttachmentStorage broken = new AttachmentStorage() {
            @Override
            public void save(String relKey, InputStream in, long size) {
                // 不需要
            }

            @Override
            public InputStream open(String relKey) {
                return null;
            }

            @Override
            public ObjectStat stat(String relKey) {
                throw new IllegalStateException("OBS 挂了");
            }
        };

        // ETag 只是缓存优化：算不出来也必须能继续（回落到 file_path+size），不能把预览搞成 500
        assertNull(AttachmentCacheSupport.statOf(PATH, broken));
        assertTrue(AttachmentCacheSupport.etagOf(PATH, 1L, AttachmentCacheSupport.statOf(PATH, broken))
                .matches("\"[0-9a-f]{64}\""));
        assertNull(AttachmentCacheSupport.statOf(PATH, null));
    }

    @Test
    void ifNoneMatch命中才返回304() {
        String etag = "\"abc123\"";

        assertTrue(AttachmentCacheSupport.isNotModified("\"abc123\"", etag));
        assertTrue(AttachmentCacheSupport.isNotModified("W/\"abc123\"", etag), "浏览器的弱 ETag 也要认");
        assertTrue(AttachmentCacheSupport.isNotModified("\"other\", \"abc123\"", etag), "列表里任一命中即可");
        assertTrue(AttachmentCacheSupport.isNotModified("*", etag));
        assertTrue(AttachmentCacheSupport.isNotModified("  \"abc123\"  ", etag));

        assertFalse(AttachmentCacheSupport.isNotModified("\"other\"", etag));
        assertFalse(AttachmentCacheSupport.isNotModified(null, etag));
        assertFalse(AttachmentCacheSupport.isNotModified("", etag));
        assertFalse(AttachmentCacheSupport.isNotModified("\"abc123\"", null));
    }

    @Test
    void 缓存头是private一小时() {
        String value = AttachmentCacheSupport.cacheControl().getHeaderValue();

        // 顺序由 Spring 决定，不做逐字比较；要紧的是这两个指令都在
        assertTrue(value.contains("private"), value);
        assertTrue(value.contains("max-age=3600"), value);
        // 必须 private：下载 URL 上带 token 查询参数，进共享缓存等于把凭据与私有附件缓存给他人
        assertFalse(value.contains("public"), value);
    }
}
