package com.pmgt.ai.module.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.module.store.DocStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列满的<b>诚实失败</b>（P0-4）：标 FAILED + 清临时文件 + 429 + 明确原因。
 *
 * <p>改造前的症状（评估 §2.4）：{@code execute()} 抛 {@code TaskRejectedException} 直接冒成通用 500，
 * 而任务记录<b>永远停在 QUEUED</b>、临时文件残留在 {@code work/tmp/}——用户看到"上传失败"却查不出原因，
 * 任务列表里还挂着一个永不结束的任务。
 *
 * <p>测试手法：把唯一的线程先占住（1 线程 + 0 容量的队列），再提交 → 必定被拒。
 * 这样"拒绝路径"是确定的，不依赖时序竞争。
 */
class UploadTaskQueueFullTest {

    @TempDir
    Path tmp;

    private static ThreadPoolTaskExecutor tinyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("pm-ai-task-test-");
        executor.initialize();
        return executor;
    }

    private UploadTaskService service(ThreadPoolTaskExecutor executor, SimpleMeterRegistry registry) {
        AiSettings settings = new AiSettings();
        settings.setWorkDir(tmp);
        return new UploadTaskService(settings, new ObjectMapper(), null, null,
                new DocStore(tmp.resolve("docs")), null, executor, registry);
    }

    private Path tempUpload(String name) throws Exception {
        Path path = Files.createTempFile(tmp, name + "-", ".pdf");
        Files.writeString(path, "fake-pdf", StandardCharsets.UTF_8);
        return path;
    }

    @Test
    @DisplayName("队列满：429 + 任务标 FAILED + 临时文件被清掉 + 计数可见；列表里没有永久 QUEUED")
    void queueFullFailsHonestly() throws Exception {
        ThreadPoolTaskExecutor executor = tinyExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UploadTaskService service = service(executor, registry);

        CountDownLatch hold = new CountDownLatch(1);
        try {
            // 占住唯一的线程（模拟"在途解析还没跑完，队列又满了"）
            executor.execute(() -> {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(50);

            Path rejected = tempUpload("rejected");
            assertTrue(Files.exists(rejected));

            ApiException error = assertThrows(ApiException.class,
                    () -> service.submit(rejected, "大扫描件.pdf", 1024L, null));

            assertEquals(429, error.getStatus(), "队列满是 429（可重试），不是 500");
            assertTrue(error.getMessage().contains("解析队列已满"), error.getMessage());
            assertFalse(Files.exists(rejected), "被拒绝的任务必须清掉临时文件（不能留 work/tmp 残留）");

            List<Map<String, Object>> tasks = service.list();
            Map<String, Object> rejectedTask = tasks.stream()
                    .filter(task -> "大扫描件.pdf".equals(task.get("filename")))
                    .findFirst().orElse(null);
            assertNotNull(rejectedTask, "被拒绝的任务也要有记录（否则用户查不到为什么失败）");
            assertEquals("FAILED", rejectedTask.get("status"), "绝不能停在 QUEUED");
            assertTrue(String.valueOf(rejectedTask.get("error")).contains("队列已满"), rejectedTask.toString());

            assertEquals(0, tasks.stream().filter(t -> "QUEUED".equals(t.get("status"))).count(),
                    "不允许任何任务永久停在 QUEUED");
            assertEquals(1.0, registry.get("ai.upload.rejected").counter().count(), 1e-9);
            assertTrue(service.queueCapacity() >= 0);
        } finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("队列没满时正常登记：任务拿到 id，状态先是 QUEUED（随后由后台线程推进）")
    void healthySubmitReturnsTask() throws Exception {
        ThreadPoolTaskExecutor executor = tinyExecutor();
        UploadTaskService service = service(executor, new SimpleMeterRegistry());
        try {
            UploadTask task = service.submit(tempUpload("normal"), "普通.pdf", 512L, 150);
            assertNotNull(task.getTaskId());
            assertFalse(task.getTaskId().isBlank());
            // 后台线程会因为 pdfReader 为 null 而失败（本用例只关心登记语义），但状态必须落到终态。
            // 注意：UploadTask 的字段不是 volatile，跨线程读要等到"状态与原因都写好"（否则会读到半更新）。
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && (!"FAILED".equals(task.getStatus()) || task.getError().isBlank())) {
                Thread.sleep(20);
            }
            assertEquals("FAILED", task.getStatus(), "解析器缺失时任务要如实失败，不能停在 PARSING");
            assertFalse(task.getError() == null || task.getError().isBlank(),
                    "失败要有原因，实际 error=[" + task.getError() + "]");
        } finally {
            executor.shutdown();
        }
    }
}
