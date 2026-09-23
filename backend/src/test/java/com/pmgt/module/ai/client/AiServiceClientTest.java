package com.pmgt.module.ai.client;

import com.pmgt.common.exception.BizException;
import com.pmgt.module.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AiServiceClient} 的契约适配测试。
 *
 * <p>全部用 {@link MockRestServiceServer} 打桩，<b>不依赖真实 AI 服务</b>：
 * 这层测的是「AI 服务的 snake_case 契约怎么被读出来、异常怎么翻译」，
 * 真起一个 AI 服务只会让测试变成端到端冒烟且不可重复。
 */
class AiServiceClientTest {

    private static final String BASE = "http://ai.test:8100";

    private MockRestServiceServer server;
    private AiServiceClient client;

    /**
     * 建一套「共享同一个 mock server」的客户端（问答与快速两个 client 都要被拦截）。
     *
     * <p>关键点：两个 client 必须由 {@code bindTo} 返回的**同一个 builder** 派生。
     * 早先的写法是各自 {@code RestClient.builder()} 再共用同一个 requestFactory——
     * 那样 mock server 拦截不到任何请求，测试会真的去连 {@code ai.test} 然后报"连接失败"，
     * 看起来像业务 bug，其实是测试装错了。
     */
    private void setUp(String token) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory());
        // bindTo 把拦截器挂到 builder 上；随后 build() 出来的 client 都带这个拦截器
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder chatBuilder = builder.baseUrl(BASE);
        RestClient.Builder fastBuilder = builder.baseUrl(BASE);
        // ⚠️ 令牌要加在**每个** client 的 builder 上：RestClient.Builder.clone() 不复制
        // defaultHeader（它的 clone 只带 requestFactory/interceptors/messageConverters 等），
        // 所以生产代码里那个 builder.clone() 链上的 defaultHeader 会丢——这里显式补上，
        // 与 AiHttpClientConfig 里对两个 bean 各配一次头部的做法一致
        if (token != null && !token.isBlank()) {
            String value = "Bearer " + token;
            chatBuilder = chatBuilder.defaultHeader("Authorization", value);
            fastBuilder = fastBuilder.defaultHeader("Authorization", value);
        }
        AiProperties props = new AiProperties();
        props.setBaseUrl(BASE);
        props.setToken(token);
        client = new AiServiceClient(props, chatBuilder.build(), fastBuilder.build());
    }

    @Test
    void 健康探活读取字面量响应并映射字段() {
        setUp("");
        server.expect(requestTo(BASE + "/health?with_ocr=true&with_llm=false&with_vec=false"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":0,"service":"pm-ai-service","version":"1.0.0-SNAPSHOT",
                         "config":{"vec_backend":"local","llm_model":"Qwen3.8-27B-W8A8"},
                         "ocr":{"ok":true,"workers":12,"idle":11,"detail":""},
                         "provider":"platform"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> body = client.health(true, false, false);

        assertEquals(0, body.get("code"));
        // /health 不套 {code,data}：字段就在顶层，读取时不能去找 data
        assertEquals("pm-ai-service", body.get("service"));
        assertEquals("local", AiJson.text(AiJson.asMap(body.get("config")), "vec_backend"));
        assertTrue(AiJson.bool(AiJson.asMap(body.get("ocr")), "ok", false));
        server.verify();
    }

    @Test
    void 文档列表解包信封并保留snake_case字段() {
        setUp("");
        server.expect(requestTo(BASE + "/documents"))
                .andRespond(withSuccess("""
                        {"code":0,"data":[
                          {"doc_id":"abc123","filename":"合同.pdf","pages":6,"chars":3640,
                           "size_bytes":20480,"uploaded_at":"2026-09-20T10:11:12","provider":"platform"},
                          {"doc_id":"def456","filename":"发票.pdf","pages":1,"chars":120,
                           "size_bytes":1024,"uploaded_at":"2026-09-21T08:00:00"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> docs = client.listDocuments();

        assertEquals(2, docs.size());
        assertEquals("abc123", AiJson.text(docs.get(0), "doc_id"));
        assertEquals("合同.pdf", AiJson.text(docs.get(0), "filename"));
        assertEquals(6, AiJson.intValue(docs.get(0), "pages", 0));
        assertEquals(20480L, AiJson.longValue(docs.get(0), "size_bytes", 0L));
        assertEquals("2026-09-20T10:11:12", AiJson.text(docs.get(0), "uploaded_at"));
        server.verify();
    }

    @Test
    void 问答发送doc_ids并在响应里透出citations() {
        setUp("secret-token");
        server.expect(requestTo(BASE + "/chat"))
                .andExpect(method(HttpMethod.POST))
                // 服务间令牌：配了就必须带上（AI 服务侧目前不校验，见 AiProperties 注释）
                .andExpect(header("Authorization", "Bearer secret-token"))
                // 字段名必须是 snake_case 的 doc_ids（AI 服务契约演进中，@JsonAlias 两种都收）
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("\"doc_ids\""), "请求体应使用 doc_ids：" + body);
                    assertTrue(body.contains("\"abc123\""), "doc_ids 应带上解析后的范围：" + body);
                    assertTrue(body.contains("\"top_k\""), "topK 应按契约透传：" + body);
                })
                .andRespond(withSuccess("""
                        {"code":0,"data":{
                          "answer":"中标金额为 7,182,700.00 元 [1]",
                          "trace":[{"round":1,"name":"search_documents","brief":"命中 2 段：中标通知书.pdfP1",
                                    "elapsed":0.31,"is_error":false}],
                          "scope":[{"doc_id":"abc123","filename":"中标通知书.pdf"}],
                          "citations":[{"index":1,"doc_id":"abc123","filename":"中标通知书.pdf",
                                        "page_no":1,"snippet":"人民币 7,182,700.00 元","score":0.93}],
                          "llm":{"ok":true,"rounds":1,"elapsed":10.27},
                          "stopped_reason":"done","error":""}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> data = client.chat("中标金额是多少？", List.of("abc123"), null, 5);

        assertEquals("中标金额为 7,182,700.00 元 [1]", AiJson.text(data, "answer"));
        List<Map<String, Object>> citations = AiJson.asList(data.get("citations"));
        assertEquals(1, citations.size());
        assertEquals("abc123", AiJson.text(citations.get(0), "doc_id"));
        assertEquals(1, AiJson.intValue(citations.get(0), "page_no", 0));
        assertEquals(0.93, AiJson.doubleValue(citations.get(0), "score", 0.0), 1e-9);
        server.verify();
    }

    @Test
    void 传入biz_query时请求体里出现该字段且原样带出url与entities() {
        setUp("secret-token");
        server.expect(requestTo(BASE + "/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("\"biz_query\""), "配置了回调地址就必须带 biz_query：" + body);
                    assertTrue(body.contains("\"url\":\"http://host.docker.internal:8080/api/ai/query\""),
                            "url 必须原样下发：" + body);
                    assertTrue(body.contains("\"scope_token\":\"scope-jwt\""), "短时效令牌必须下发：" + body);
                    assertTrue(body.contains("\"entities\":[\"projects\",\"contracts\",\"payments\",\"stats\"]"),
                            "entity 清单必须下发：" + body);
                })
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"answer\":\"ok\"}}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> data = client.chat("这个项目有几个附件？", List.of("abc123"), null, null,
                Map.of("url", "http://host.docker.internal:8080/api/ai/query",
                        "scope_token", "scope-jwt",
                        "entities", List.of("projects", "contracts", "payments", "stats")));

        assertEquals("ok", AiJson.text(data, "answer"));
        server.verify();
    }

    @Test
    void 不传biz_query时请求体里没有该字段() {
        // §11.2：不传 biz_query → AI 侧不注册 query_business_data 工具，行为与本版本前完全一致。
        // 这条是回归保护：多一个字段都可能让旧版 AI 服务的行为发生变化。
        setUp("");
        server.expect(requestTo(BASE + "/chat"))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(!body.contains("biz_query"), "没配置回调地址时不能出现 biz_query：" + body);
                    assertTrue(body.contains("\"doc_ids\""), body);
                })
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"answer\":\"ok\"}}",
                        MediaType.APPLICATION_JSON));

        client.chat("中标金额是多少？", List.of("abc123"), null, 5);

        server.verify();
    }

    @Test
    void 旧的四参重载等价于不带biz_query() {
        // 4 参重载保留是为了不破坏既有调用方；它必须与"显式传 null"完全一致
        setUp("");
        server.expect(requestTo(BASE + "/chat"))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(!body.contains("biz_query"), body);
                })
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"answer\":\"ok\"}}",
                        MediaType.APPLICATION_JSON));

        client.chat("问题", List.of("abc123"), null, null);

        server.verify();
    }

    @Test
    void 服务端5xx抛出AI不可用而不是被当成业务结果() {
        setUp("");
        server.expect(requestTo(BASE + "/chat")).andRespond(withServerError());

        AiUnavailableException e = assertThrows(AiUnavailableException.class,
                () -> client.chat("问题", List.of("abc123"), null, null));

        assertEquals(503, e.getCode());
        assertTrue(e.getMessage().contains("AI 服务不可用"));
        server.verify();
    }

    @Test
    void 连接失败抛出AI不可用且带上原因() {
        setUp("");
        AiProperties props = new AiProperties();
        // 127.0.0.1 上一个确定没有监听的端口：立刻 ECONNREFUSED，
        // 不依赖外网也不依赖本机 8100 是否真的在跑（测试要可重复）
        props.setBaseUrl("http://127.0.0.1:1");
        props.setTimeoutSeconds(1);
        props.setChatTimeoutSeconds(1);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        AiServiceClient broken = new AiServiceClient(props,
                RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(factory).build(),
                RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(factory).build());

        AiUnavailableException e = assertThrows(AiUnavailableException.class, () -> broken.listDocuments());

        assertEquals(503, e.getCode());
        assertTrue(e.getMessage().contains("连接失败"), "实际信息：" + e.getMessage());
    }

    @Test
    void 业务拒绝4xx转成BizException并带出原因() {
        setUp("");
        server.expect(requestTo(BASE + "/documents/abc123"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"文档不存在\"}"));

        // 404 不是"服务不可用"，而是"这东西不在了"——必须能被上层区分为业务错误
        BizException e = assertThrows(BizException.class, () -> client.getDocument("abc123"));
        assertTrue(!(e instanceof AiUnavailableException));
        server.verify();
    }

    @Test
    void 响应code非0转成BizException() {
        setUp("");
        server.expect(requestTo(BASE + "/chat"))
                .andRespond(withSuccess("{\"code\":500,\"message\":\"没有可用文档\"}", MediaType.APPLICATION_JSON));

        BizException e = assertThrows(BizException.class,
                () -> client.chat("问题", List.of("abc123"), null, null));
        assertTrue(e.getMessage().contains("没有可用文档"), "实际信息：" + e.getMessage());
        server.verify();
    }

    @Test
    void 令牌为空时不发送Authorization头() {
        setUp("");
        server.expect(requestTo(BASE + "/documents"))
                .andExpect(request -> assertTrue(
                        request.getHeaders().getFirst("Authorization") == null,
                        "未配置令牌时不该发空 Bearer"))
                .andRespond(withSuccess("{\"code\":0,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(client.listDocuments().isEmpty());
        server.verify();
    }

    @Test
    void 扩展名映射内容类型且中文文件名不影响判定() {
        assertEquals("application/pdf", AiServiceClient.contentTypeOf("中标通知书.pdf"));
        assertEquals("image/jpeg", AiServiceClient.contentTypeOf("扫描件.JPG"));
        assertEquals("text/plain; charset=utf-8", AiServiceClient.contentTypeOf("说明.txt"));
        assertEquals("application/octet-stream", AiServiceClient.contentTypeOf("未知类型"));
        assertEquals("application/octet-stream", AiServiceClient.contentTypeOf(null));
    }
}
