package com.pmgt.module.ai.client;

import com.pmgt.module.ai.config.AiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期 classpath 缺 {@code org.reactivestreams:reactive-streams} 的回归测试（v3.6.5 生产故障）。
 *
 * <h2>这条测试为什么必须「真发 HTTP」</h2>
 * 生产的 500 是：
 * <pre>
 * jakarta.servlet.ServletException: Handler dispatch failed:
 *   java.lang.NoClassDefFoundError: org/reactivestreams/Publisher
 *   Caused by: java.lang.ClassNotFoundException: org.reactivestreams.Publisher
 * </pre>
 * 触发点是<b>上传附件走 multipart POST</b> 这条路径。Spring 6.1 的
 * {@link org.springframework.http.client.MultipartBodyBuilder} 自身就引用了
 * {@code org/reactivestreams/Publisher}（它带 {@code asyncPart}/{@code PublisherPartBuilder}
 * 之类的 Flux/Mono 分支），JVM 校验/链接该类时就要把这个类型解析出来；而本项目只有
 * {@code spring-boot-starter-web}（servlet 栈、带 {@code MockRestServiceServer} 的 spring-test），
 * <b>没有任何依赖把 reactive-streams 带进运行期 classpath</b>，于是类加载失败、请求 500。
 *
 * <p>旧测试全绿是因为<b>它们根本不走这条路</b>：{@link AiServiceClientTest} 用
 * {@code MockRestServiceServer} 只覆盖了 health/documents/chat/错误码这些分支，
 * 「真发 HTTP + 上传文件」的 {@code submitParse} 一条用例都没有；
 * mock 用假的 request factory 顶掉了真实发送，更容易让这种「只有真发请求才会被链接到」的
 * 缺失依赖一直躲过测试（实测：补上依赖前，连 mock 版的 submitParse 用例也会抛
 * {@code NoClassDefFoundError}——问题不在 mock 本身，而在这条路径从来没被测过）。
 *
 * <p>所以这里用 JDK 自带的 {@link HttpServer} 在 {@code 127.0.0.1:0}（随机端口）起桩，
 * 用<b>与生产同一套构建方式</b>的 {@code RestClient}（{@code RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory())}）
 * 真连一次：① multipart POST（崩溃路径）；② GET /health 与 ③ JSON POST /chat（对照路径，
 * 这两条本来就正常，用来证明「不是所有请求都炸」，避免把问题误判成网络/服务不可用）。
 * 断言不只是「没抛异常」，还要桩侧能<b>读到 multipart 边界与文件内容</b>——
 * 否则「没抛异常」可能只是请求根本没发出去。
 *
 * <p>★关于触发点的一句话澄清（保持诚实）：最初的判断是「Spring 会触碰
 * {@code ReactiveAdapterRegistry}，它直接引用 {@code org.reactivestreams.Publisher}」。
 * 本地在 Spring Framework 6.1.14 上实测，更靠前的触发点是
 * {@code MultipartBodyBuilder} 这个类<b>自身</b>的常量池就含 {@code org/reactivestreams/Publisher}
 * （内嵌 {@code PublisherPartBuilder}/{@code PublisherEntity} 等 Flux 分支），
 * 连 {@code new MultipartBodyBuilder()} 都会抛 {@code NoClassDefFoundError}（已实测）。
 * 两条链最终都指向<b>同一个缺失的类</b>，结论一致：运行期 classpath 必须补上 reactive-streams。
 */
class RestClientReactiveStreamsTest {

    /** 桩要收到并能原样读回的文件内容（放在断言里证明请求体真的到达了对端）。 */
    private static final String FILE_CONTENT = "reactive-streams-regression-body-2026";
    private static final String FILENAME = "zhaobiaotongzhishu.pdf";

    /** 桩收到的每一个请求（真 socket 那侧的事实，而不是客户端自说自话）。 */
    private record Captured(String method, String path, String query, String contentType, String body) {
    }

    private final List<Captured> captured = new ArrayList<>();

    private HttpServer server;
    /** 与生产同一套构建方式、但直接暴露状态码的客户端（用于把 200 显式钉死）。 */
    private RestClient restClient;
    private AiServiceClient client;

    @BeforeEach
    void setUp() throws IOException {
        // 端口 0 = 让内核分配空闲端口：不占用固定端口，测试可并行、可重复
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        AiProperties props = new AiProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        // ★与 AiHttpClientConfig#requestFactory 一致：真实 socket + SimpleClientHttpRequestFactory。
        //   不要换成 MockRestServiceServer——那正是本条回归要防的盲区。
        restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new AiServiceClient(props, restClient, restClient);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void multipart上传真发HTTP不抛NoClassDefFoundError且桩能读到边界与内容() {
        Throwable failure = capture(() -> client.submitParse(FILENAME, "application/pdf",
                new ByteArrayInputStream(FILE_CONTENT.getBytes(StandardCharsets.UTF_8))));

        // 先专门断言「不是缺类的症状」：缺 reactive-streams 时这里就是 NoClassDefFoundError，
        // 报错信息直接点名要加哪个依赖，避免排查时又被 500「系统繁忙」带偏
        assertNoMissingReactiveStreams(failure);
        assertNull(failure, "multipart POST 应正常返回，实际失败：" + failure);

        assertEquals(1, captured.size(), "桩应恰好收到 1 个请求");
        Captured request = captured.get(0);
        assertEquals("POST", request.method());
        assertEquals("/upload-tasks", request.path());

        String contentType = request.contentType();
        assertNotNull(contentType, "multipart 请求必须带 Content-Type（边界在这里）");
        assertTrue(contentType.startsWith("multipart/form-data"),
                "Content-Type 应是 multipart/form-data，实际：" + contentType);
        String boundary = boundaryOf(contentType);
        assertNotNull(boundary, "multipart 请求必须带 boundary，实际：" + contentType);

        // 桩读回来的原始请求体：能读到边界、字段名、文件名与文件内容，才算「请求真的发出去了」
        assertTrue(request.body().contains("--" + boundary),
                "请求体里应出现 multipart 边界 --" + boundary + "，实际：" + preview(request.body()));
        assertTrue(request.body().contains("name=\"file\""),
                "请求体里应出现 name=\"file\"，实际：" + preview(request.body()));
        assertTrue(request.body().contains("filename=\"" + FILENAME + "\""),
                "请求体里应出现 filename，实际：" + preview(request.body()));
        assertTrue(request.body().contains(FILE_CONTENT),
                "请求体里应能读到文件内容，实际：" + preview(request.body()));
    }

    @Test
    void 健康探活GET真发HTTP正常() {
        Throwable failure = capture(() -> {
            Map<String, Object> body = client.health(true, false, false);
            assertEquals("pm-ai-service-stub", AiJson.text(body, "service"));
        });

        assertNoMissingReactiveStreams(failure);
        assertNull(failure, "GET /health 应正常返回，实际失败：" + failure);

        assertEquals(1, captured.size());
        assertEquals("GET", captured.get(0).method());
        assertEquals("/health", captured.get(0).path());
        assertEquals("with_ocr=true&with_llm=false&with_vec=false", captured.get(0).query());
    }

    @Test
    void 问答JSON_POST真发HTTP正常() {
        Throwable failure = capture(() -> {
            Map<String, Object> data = client.chat("中标金额是多少？", List.of("doc-1"), null, 5);
            assertEquals("ok", AiJson.text(data, "answer"));
        });

        assertNoMissingReactiveStreams(failure);
        assertNull(failure, "POST /chat 应正常返回，实际失败：" + failure);

        assertEquals(1, captured.size());
        assertEquals("POST", captured.get(0).method());
        assertEquals("/chat", captured.get(0).path());
        assertTrue(captured.get(0).body().contains("\"doc_ids\""),
                "问答请求体应是 snake_case 的 doc_ids，实际：" + preview(captured.get(0).body()));
    }

    /**
     * 依赖本身的守卫：缺依赖时 {@code MultipartBodyBuilder} 会在类链接阶段就炸，
     * 所以「运行期 classpath 必须有 reactive-streams」这条约束值得单独钉一条用例——
     * 谁再把 pom 里那行删掉，这里会先于业务用例报出准确的类名。
     */
    @Test
    void 运行期classpath必须能加载reactive_streams的Publisher() {
        Throwable failure = capture(() -> Class.forName("org.reactivestreams.Publisher"));
        assertNoMissingReactiveStreams(failure);
        assertNull(failure, "运行期 classpath 应包含 org.reactivestreams.Publisher，实际：" + failure);
    }

    /**
     * 显式把 HTTP 状态码钉死。上面几条只断言「客户端没抛异常、响应体解析正确」，
     * 这里直接用 {@link org.springframework.http.ResponseEntity} 读原始状态码，
     * 免得将来桩改坏了返回码、用例还绿着。
     */
    @Test
    void 真socket下multipart与GET的状态码都是200() {
        ResponseEntity<String> multipart = restClient.post()
                .uri("/upload-tasks")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipartBody())
                .retrieve()
                .toEntity(String.class);
        assertEquals(200, multipart.getStatusCode().value(),
                "multipart POST 的状态码应是 200，实际：" + multipart.getStatusCode());

        ResponseEntity<String> health = restClient.get()
                .uri(uri -> uri.path("/health").queryParam("with_ocr", false).build())
                .retrieve()
                .toEntity(String.class);
        assertEquals(200, health.getStatusCode().value(),
                "GET /health 的状态码应是 200，实际：" + health.getStatusCode());
    }

    /** 与 {@code AiServiceClient#submitParse} 同款的 multipart 请求体（这里只为断言状态码）。 */
    private static MultiValueMap<String, HttpEntity<?>> multipartBody() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new InputStreamResource(
                        new ByteArrayInputStream(FILE_CONTENT.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public String getFilename() {
                return FILENAME;
            }

            @Override
            public long contentLength() {
                return -1;
            }
        }).contentType(MediaType.APPLICATION_PDF);
        return builder.build();
    }

    // ── 桩与断言辅助 ────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            captured.add(new Captured(exchange.getRequestMethod(), path,
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));

            String response = switch (path) {
                case "/upload-tasks" -> "{\"code\":0,\"data\":{\"task_id\":\"ai-task-1\",\"status\":\"PARSING\"}}";
                case "/health" -> "{\"code\":0,\"service\":\"pm-ai-service-stub\",\"ocr\":{\"ok\":true}}";
                case "/chat" -> "{\"code\":0,\"data\":{\"answer\":\"ok\"}}";
                default -> "{\"code\":404,\"message\":\"未打桩的路径\"}";
            };
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (Exception e) {
            // 桩里出问题必须显式收口：否则连接悬着，客户端会一直等到超时，看起来像业务超时
            exchange.close();
            throw new IOException("桩处理失败: " + e, e);
        }
    }

    /** 从 {@code multipart/form-data; boundary=xxx} 里取出 boundary（没有则 null）。 */
    private static String boundaryOf(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String value = trimmed.substring("boundary=".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * 断言失败的不是「缺类」。{@link NoClassDefFoundError} 是 {@link Error} 而不是
     * {@code Exception}，业务层的 catch(Exception) 拦不住、也不会被翻译成「AI 服务不可用」，
     * 会一路冒到 DispatcherServlet 变成 500「系统繁忙」——这正是线上现象。
     */
    private static void assertNoMissingReactiveStreams(Throwable failure) {
        boolean missingClass = failure instanceof NoClassDefFoundError
                || failure instanceof ClassNotFoundException
                || (failure != null && failure.getCause() instanceof ClassNotFoundException);
        assertFalse(missingClass,
                "运行期 classpath 少了 org.reactivestreams:reactive-streams：Spring 6.1 的 "
                        + "MultipartBodyBuilder 引用了 org/reactivestreams/Publisher，缺它时 multipart "
                        + "请求会在类链接阶段抛 NoClassDefFoundError（线上表现为 500「系统繁忙」）。"
                        + "请在 backend/pom.xml 的 <dependencies> 里补上该依赖（版本交给 Spring Boot BOM 管）。"
                        + "实际异常：" + failure);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 捕获 {@link Throwable}（含 {@link Error}）：NoClassDefFoundError 必须能被断言看到。 */
    private static Throwable capture(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String preview(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
