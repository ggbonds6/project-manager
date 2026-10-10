package com.pmgt.ai.module.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.common.util.ProgressFn;
import com.pmgt.ai.testutil.StubHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OCR 并发语义（P0-4）：<b>队列化提交 + 全进程在飞上限</b>，以及"批页按并发上限动态取"。
 *
 * <p>改造前的写法是 {@code workers = min(OCR_CONCURRENCY, ceil(页数/批页))} 且<b>每个上传任务自建线程池</b>：
 * 8 页文档只有 1 个批次在飞，{@code OCR_CONCURRENCY=12} 完全是死配置。
 * 这里用一个真 HTTP 桩（本机随机端口，<b>不连平台</b>）数"同时在飞的请求数"，
 * 并用闸门把"必须有 N 个在飞"钉成可判定事实——旧实现会在这里等超时。
 */
class OcrQueueConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    /** 造 N 张"图片"（内容不重要，桩只数页数）。 */
    private List<Path> images(int count) throws Exception {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path file = tmp.resolve("page-" + i + ".jpg");
            Files.writeString(file, "fake-image-" + i, StandardCharsets.UTF_8);
            out.add(file);
        }
        return out;
    }

    /** 平台 OCR 的响应体：按请求页数给出等量的 results。 */
    private static String responseFor(String body) throws Exception {
        JsonNode request = MAPPER.readTree(body);
        int pages = request.path("images").size();
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            results.append(i > 0 ? "," : "").append("{\"markdown\":\"第").append(i + 1).append("页\"}");
        }
        return "{\"results\":[" + results + "],\"total_ms\":" + (pages * 100) + "}";
    }

    private static AiSettings settings(String baseUrl, int concurrency, int batchPages) {
        AiSettings settings = new AiSettings();
        settings.getGateway().setBaseUrl(baseUrl);
        settings.getOcr().setConcurrency(concurrency);
        settings.getOcr().setBatchPages(batchPages);
        settings.getLimits().setRetryBackoffMs(1);
        settings.getLimits().setRetryJitterMs(0);
        settings.getLimits().setBatchAcquireMs(10000);
        return settings;
    }

    @Test
    @DisplayName("8 页文档 + 在飞上限 4：切成 4 个请求真的并发在飞（旧实现只有 1 个在飞）")
    void smallDocumentNowUsesTheConcurrency() throws Exception {
        int concurrency = 4;
        CountDownLatch gate = new CountDownLatch(concurrency);
        AtomicInteger arrived = new AtomicInteger();
        AtomicInteger gateTimeouts = new AtomicInteger();

        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            if (path.startsWith("/ocr")) {
                if (arrived.incrementAndGet() <= concurrency) {
                    gate.countDown();
                    if (!gate.await(3, TimeUnit.SECONDS)) {
                        gateTimeouts.incrementAndGet();
                    }
                }
                return responseFor(body);
            }
            return "{\"status\":\"ok\",\"workers\":12,\"idle\":12}";
        })) {
            AiSettings settings = settings(server.baseUrl(), concurrency, 8);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));

            List<PlatformPage> pages = client.recognize(images(8), ProgressFn.NONE);

            assertEquals(8, pages.size());
            assertEquals(0, gateTimeouts.get(),
                    "并发必须真的达到 " + concurrency + "（旧实现 8 页只有 1 个批次在飞，这里会超时）");
            assertEquals(concurrency, server.maxInFlight());
            assertEquals(4, server.calls(), "8 页 / 每请求 2 页 = 4 个请求");
            assertTrue(pages.stream().allMatch(p -> p.error() == null), "这一轮不该有失败页");
        }
    }

    @Test
    @DisplayName("40 页文档 + 在飞上限 4：批页取满 8（少往返），在飞数不超过上限")
    void largeDocumentKeepsBatchSizeButCapsInflight() throws Exception {
        int concurrency = 4;
        CountDownLatch gate = new CountDownLatch(concurrency);
        AtomicInteger arrived = new AtomicInteger();
        AtomicInteger gateTimeouts = new AtomicInteger();

        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            if (path.startsWith("/ocr")) {
                if (arrived.incrementAndGet() <= concurrency) {
                    gate.countDown();
                    if (!gate.await(3, TimeUnit.SECONDS)) {
                        gateTimeouts.incrementAndGet();
                    }
                }
                return responseFor(body);
            }
            return "{}";
        })) {
            AiSettings settings = settings(server.baseUrl(), concurrency, 8);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));

            List<PlatformPage> pages = client.recognize(images(40));

            assertEquals(40, pages.size());
            assertEquals(0, gateTimeouts.get());
            assertTrue(server.maxInFlight() <= concurrency, "在飞数不能超过上限：" + server.maxInFlight());
            assertEquals(concurrency, server.maxInFlight());
            assertEquals(5, server.calls(), "40 页 / 每请求 8 页 = 5 个请求");
        }
    }

    @Test
    @DisplayName("幂等重试：前两次 503、第三次成功 ⇒ 该批不判失败（页里没有 error）")
    void ocrRetriesOnServerErrors() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            attempts.incrementAndGet();
            return path.startsWith("/ocr") ? responseFor(body) : "{}";
        })) {
            server.setStatusSupplier(() -> attempts.get() <= 2 ? 503 : 200);
            AiSettings settings = settings(server.baseUrl(), 2, 8);
            settings.getLimits().setRetryMax(2);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));

            // 单页 = 单批：于是"尝试次数"就是这一批的尝试次数（多批会各自重试，计数会叠加）
            List<PlatformPage> pages = client.recognize(images(1));

            assertTrue(pages.stream().allMatch(p -> p.error() == null),
                    "重试成功后不该有失败页：" + pages.stream().map(PlatformPage::error).toList());
            assertEquals(3, attempts.get(), "该批必须尝试 3 次（1 原始 + 2 重试）");
            assertTrue(pages.get(0).markdown().contains("第1页"), pages.get(0).markdown());
        }
    }

    @Test
    @DisplayName("重试关闭（retryMax=0）：503 该批逐页占位并带错误（契约不变，不把整份文档判失败）")
    void withRetriesDisabledBatchFailsPerPage() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> "{\"error\":\"upstream busy\"}")) {
            server.setStatus(503);
            AiSettings settings = settings(server.baseUrl(), 2, 8);
            settings.getLimits().setRetryMax(0);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));

            List<PlatformPage> pages = client.recognize(images(3));

            assertEquals(3, pages.size(), "返回顺序与输入一致、失败页占位");
            for (PlatformPage page : pages) {
                assertTrue(page.error() != null && page.error().contains("503"), "失败页要带原因：" + page.error());
            }
            assertEquals(2, server.calls(), "retryMax=0 时每批只发一次（3 页 / 每请求 2 页 = 2 批）");
        }
    }

    @Test
    @DisplayName("进度回调：按批回调 (已完成页数, 总页数)，最终等于总页数")
    void progressReportsCompletedPages() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> responseFor(body))) {
            AiSettings settings = settings(server.baseUrl(), 2, 4);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));

            List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
            List<PlatformPage> pages = client.recognize(images(6),
                    (stage, done, total) -> calls.add(stage + ":" + done + "/" + total));

            assertEquals(6, pages.size());
            assertTrue(calls.stream().allMatch(call -> call.startsWith("ocr:")), calls.toString());
            assertTrue(calls.contains("ocr:6/6"), "最终进度必须到 6/6：" + calls);
        }
    }

    @Test
    @DisplayName("空输入不碰网络（桩的调用数必须为 0）")
    void emptyInputDoesNotTouchThePlatform() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> "{}")) {
            AiSettings settings = settings(server.baseUrl(), 4, 8);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));
            assertTrue(client.recognize(List.of()).isEmpty());
            assertEquals(0, server.calls());
        }
    }

    @Test
    @DisplayName("健康探测：把 workers/idle/options 解析出来（用它自己的短超时，不受 OCR 600s 影响）")
    void healthUsesItsOwnShortTimeout() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) ->
                "{\"status\":\"ok\",\"workers\":80,\"idle\":80,\"options\":[\"seal\"]}")) {
            AiSettings settings = settings(server.baseUrl(), 4, 8);
            PlatformOcrClient client = new PlatformOcrClient(settings, ModelRegistry.offline(settings));
            OcrHealth health = client.health(true);
            assertTrue(health.ok());
            assertEquals(80, health.workers());
            assertEquals(List.of("seal"), health.options());
        }
    }
}
