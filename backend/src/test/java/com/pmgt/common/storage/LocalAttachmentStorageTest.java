package com.pmgt.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LocalAttachmentStorage}「附件文件不存在」时的行为。
 *
 * <p>本次改动（可诊断性）的红线是<b>用户可见行为不变</b>：仍然抛 {@link FileNotFoundException}
 * （Controller 据此返回 404「附件文件缺失」），只是额外打一条带<b>绝对路径</b>与
 * UPLOAD_DIR / UPLOAD_VOLUME 提示的 WARN。这里把这条契约钉住——
 * 顺带让构建输出里能看到真实打出来的日志行。
 */
class LocalAttachmentStorageTest {

    @Test
    void 文件不存在时仍抛FileNotFoundException且异常消息仍是file_path(@TempDir Path dir) {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(dir.toString());

        FileNotFoundException e = assertThrows(FileNotFoundException.class,
                () -> storage.open("2026/09/不存在.pdf"));

        // 异常消息仍是 file_path：控制器只把异常类型换成 404 文案，用户可见行为没变
        assertEquals("2026/09/不存在.pdf", e.getMessage());
    }
}
