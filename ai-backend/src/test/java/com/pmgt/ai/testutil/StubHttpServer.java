package com.pmgt.ai.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线 HTTP 桩（测试专用）：给"必须验证真实 HTTP 行为"的用例提供可控的对端。
 *
 * <p>为什么允许起真 HTTP 而不只是 mock：这几条要钉的恰恰是**线协议与并发行为**——
 * ① OpenSearch 的请求体形状（3.x 的 {@code knn.<字段名>} 与 {@code filter}）只有在真 HTTP 上才能断言；
 * ② 每模型信号量"到底限没限住并发"必须在**真并发**下数在飞数；
 * ③ 超时/重试只有在真连接上才触发。
 * 全部绑 {@code 127.0.0.1} 的随机端口，<b>不连任何真实集群</b>（评测环境也没有集群）。
 *
 * <p>在飞计数：进入处理前 +1、处理完 -1，并记录峰值——这就是"最大在途数"。
 */
public final class StubHttpServer implements AutoCloseable {

    /** 处理器：拿到方法/路径/请求体，返回响应体（{@code null} 视为空体）。 */
    public interface Handler {
        String handle(String method, String path, String body) throws Exception;
    }

    private final HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Handler handler;
    private volatile int status = 200;
    /** 需要"第 N 次请求才成功"这类动态状态码时用它（在 handler 返回后求值）。 */
    private volatile java.util.function.IntSupplier statusSupplier;

    public StubHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(32, runnable -> {
            Thread thread = new Thread(runnable, "stub-http");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.createContext("/", this::dispatch);
        this.server.start();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        calls.incrementAndGet();
        String body;
        try (InputStream input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestURI().getQuery() != null) {
            path = path + "?" + exchange.getRequestURI().getQuery();
        }
        requests.add(exchange.getRequestMethod() + " " + path + " " + body);
        try {
            String response = handler.handle(exchange.getRequestMethod(), path, body);
            byte[] payload = (response == null ? "" : response).getBytes(StandardCharsets.UTF_8);
            int code = statusSupplier == null ? status : statusSupplier.getAsInt();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        } catch (Exception e) {
            byte[] payload = ("{\"error\":\"" + e.getClass().getSimpleName() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int calls() {
        return calls.get();
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    public String lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? "" : requests.get(requests.size() - 1);
        }
    }

    /** 后置改响应状态（例如把后续请求都变成 503）。 */
    public void setStatus(int status) {
        this.status = status;
    }

    /** 动态状态码（例如"前两次 503、之后 200"）。 */
    public void setStatusSupplier(java.util.function.IntSupplier supplier) {
        this.statusSupplier = supplier;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
