package com.pmgt.ai.module.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code query_business_data} 工具的端到端回归（<b>真桩</b>：用 JDK 自带的
 * {@link HttpServer} 起一个本地 HTTP 服务，不连真主系统、不打真实网络）。
 *
 * <p>钉住 §11 的七件事（每一条都是"AI 侧会不会把话说错"的分界线）：
 * <ol>
 *   <li>请求形状：{@code POST {url}/{entity}}、{@code Authorization: Bearer <scope_token>}、
 *       body 恰好是 {@code {filters, limit}}；</li>
 *   <li>返回整形：{@code rows/unit/caliber/data_time/scope} <b>原样</b>带出（口径与数据时间是审计前提）；</li>
 *   <li>{@code limit} 默认 20、上限 100；</li>
 *   <li>4xx/403 → 结构化 {@code error} 且含<b>对方原话</b>；</li>
 *   <li>5xx/连不上 → 明确是"调用失败"，<b>绝不静默变成"没有数据"</b>；</li>
 *   <li>空 rows → {@code rows: [] + note}，<b>不是</b> error；</li>
 *   <li>非法/越权 entity → 结构化错误并列出合法枚举（不抛异常、不发请求）。</li>
 * </ol>
 */
class BizQueryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 桩的应答。 */
    private record Reply(int status, String body) {
    }

    /** 空检索替身：本类只测业务查询工具，检索不该被碰到。 */
    private static final class NeverSearchPort implements SearchPort {
        @Override
        public SearchResult search(String query, Integer topK, String docId) {
            throw new AssertionError("本测试不应触发文档检索");
        }
    }

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<Reply> reply =
            new AtomicReference<>(new Reply(200, "{\"code\":0,\"data\":{\"rows\":[]}}"));
    private volatile String capturedMethod;
    private volatile String capturedPath;
    private volatile String capturedAuthorization;
    private volatile String capturedBody;

    private Tools tools;
    private BizQuerySpec spec;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedMethod = exchange.getRequestMethod();
            capturedPath = exchange.getRequestURI().getPath();
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Reply current = reply.get();
            byte[] out = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(current.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        AiSettings settings = new AiSettings();
        settings.getBizQuery().setTimeoutSeconds(5);
        BizQueryClient client = new BizQueryClient(settings);
        Tools tools = new Tools(new NeverSearchPort(), new DocStore(tempDir), client);
        this.tools = tools;
        this.spec = new BizQuerySpec(baseUrl(), "tok-abc", Tools.BIZ_ENTITIES);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> call(String entity, Map<String, Object> arguments) {
        return call(entity, arguments, spec);
    }

    private Map<String, Object> call(String entity, Map<String, Object> arguments, BizQuerySpec channel) {
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        args.put("entity", entity);
        return tools.execute(Tools.BIZ_QUERY_TOOL_NAME, args, null, channel);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String raw) throws Exception {
        return MAPPER.readValue(raw, Map.class);
    }

    private static String okRows(String rowsJson) {
        return "{\"code\":0,\"data\":{\"rows\":" + rowsJson + ",\"unit\":\"个\","
                + "\"caliber\":\"按立项年度、含子项目\",\"data_time\":\"2026-09-23 16:40:00\","
                + "\"scope\":\"项目 12（含 3 个子项目）\"}}";
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 请求形状 + 口径/数据时间原样透出
    // ══════════════════════════════════════════════════════════════════

    /**
     * §11.3 的调用契约：路径 {@code /{entity}}、Bearer 头、body {@code {filters,limit}}；
     * §11.4 的返回里 {@code caliber/data_time/scope} 必须原样到模型手里（否则数字无法审计）。
     */
    @Test
    void sendsContractShapeAndKeepsCaliber() throws Exception {
        reply.set(new Reply(200, okRows("[{\"phaseName\":\"初验\",\"attachmentCount\":3}]")));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("projectId", 12);
        filters.put("kind", "phase_attachment_count");
        Map<String, Object> result = call("stats", Map.of("filters", filters, "limit", 50));

        assertEquals("POST", capturedMethod);
        assertEquals("/stats", capturedPath);
        assertEquals("Bearer tok-abc", capturedAuthorization);

        Map<String, Object> sent = json(capturedBody);
        assertEquals(Set.of("filters", "limit"), sent.keySet(), "body 只允许 {filters,limit}");
        assertEquals(50, sent.get("limit"));
        assertEquals(Map.of("projectId", 12, "kind", "phase_attachment_count"), sent.get("filters"));

        assertFalse(result.containsKey("error"));
        assertEquals("个", result.get("unit"));
        assertEquals("按立项年度、含子项目", result.get("caliber"));
        assertEquals("2026-09-23 16:40:00", result.get("data_time"));
        assertEquals("项目 12（含 3 个子项目）", result.get("scope"));
        assertEquals("stats", result.get("entity"));
        assertEquals(1, result.get("count"));
        assertFalse(result.containsKey("note"), "有数据时不该出现空结果说明");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertEquals(3, rows.get(0).get("attachmentCount"));
    }

    /** 主系统给的地址可能带路径前缀、也可能带尾斜杠（§11.1 的例子就带 {@code /api/ai/query}）。 */
    @Test
    void joinsUrlPrefixAndTrimsTrailingSlash() {
        reply.set(new Reply(200, okRows("[]")));
        BizQuerySpec prefixed =
                new BizQuerySpec(baseUrl() + "/api/ai/query/", "tok-abc", List.of("projects"));
        call("projects", Map.of(), prefixed);
        assertEquals("/api/ai/query/projects", capturedPath);
    }

    /** entity 大小写宽容（模型偶尔写 {@code Projects}）：归一后照常请求。 */
    @Test
    void entityIsCaseInsensitive() {
        reply.set(new Reply(200, okRows("[]")));
        call("Projects", Map.of());
        assertEquals("/projects", capturedPath);
    }

    // ══════════════════════════════════════════════════════════════════
    // limit 契约：默认 20、上限 100
    // ══════════════════════════════════════════════════════════════════

    @Test
    void limitDefaultsTo20AndCapsAt100() throws Exception {
        reply.set(new Reply(200, okRows("[]")));

        call("projects", Map.of());
        assertEquals(20, json(capturedBody).get("limit"), "缺省必须是 20");

        call("projects", Map.of("limit", 1000));
        assertEquals(100, json(capturedBody).get("limit"), "上限必须是 100");

        // 模型把数字传成字符串（"30"）也要认
        call("projects", Map.of("limit", "30"));
        assertEquals(30, json(capturedBody).get("limit"));

        call("projects", Map.of("limit", 0));
        assertEquals(20, json(capturedBody).get("limit"), "非正数回落默认值");

        call("projects", Map.of("limit", "很多"));
        assertEquals(20, json(capturedBody).get("limit"), "不是数字时回落默认值");

        // filters 不传时也要发一个空对象（主系统按结构化字段收参，不吃 null）
        assertEquals(Map.of(), json(capturedBody).get("filters"));
    }

    /** 模型偶尔把 filters 序列化成字符串：解一层而不是报错。 */
    @Test
    void filtersAsJsonStringIsParsed() throws Exception {
        reply.set(new Reply(200, okRows("[]")));
        call("contracts", Map.of("filters", "{\"projectId\":12}"));
        assertEquals(Map.of("projectId", 12), json(capturedBody).get("filters"));
    }

    /** filters 传了但不是对象（字符串/数字/数组）→ 结构化错误，且不白跑一趟网络。 */
    @Test
    void nonObjectFiltersIsStructuredError() {
        Map<String, Object> result = call("projects", Map.of("filters", "不是JSON"));
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("filters 必须是 JSON 对象"));
        assertNull(capturedPath, "参数都不合法就不该发请求");
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 4xx / 403：结构化错误 + 对方原话
    // ══════════════════════════════════════════════════════════════════

    /**
     * §11.6：越权必须 {@code 403 + 原话}，且模型要能看出这是"授权范围"问题、<b>不是</b>"没有数据"。
     */
    @Test
    void forbiddenKeepsOtherPartyWording() {
        reply.set(new Reply(403, "{\"code\":403,\"msg\":\"该项目不在本次授权范围（scope）内\"}"));

        Map<String, Object> result = call("projects", Map.of("filters", Map.of("projectId", 99)));

        assertTrue(result.containsKey("error"), "403 必须是结构化 error");
        assertFalse(result.containsKey("rows"), "越权时不许返回任何数据形状");
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("该项目不在本次授权范围（scope）内"), "必须带对方原话：" + error);
        assertTrue(error.contains("403"));
        assertTrue(error.contains("授权范围"));
        assertFalse(error.contains(BizQueryClient.EMPTY_NOTE), "越权不能说成「没有数据」");
    }

    /** 400（filters 字段非法）：原话要带上——主系统的原话里就有"支持哪些字段"，模型据此改参数。 */
    @Test
    void badRequestKeepsFieldListFromOtherParty() {
        reply.set(new Reply(400,
                "{\"code\":400,\"message\":\"filters 不支持字段 foo，支持的字段：projectId/name/status/type/year\"}"));

        Map<String, Object> result = call("projects", Map.of("filters", Map.of("foo", 1)));

        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("filters 不支持字段 foo，支持的字段：projectId/name/status/type/year"), error);
        assertTrue(error.contains("重试"), "要教会模型下一步怎么做：" + error);
    }

    /** 主系统自己回 200 但 {@code code != 0}：同样是失败，不能说成"没有数据"。 */
    @Test
    void nonZeroCodeIsError() {
        reply.set(new Reply(200, "{\"code\":500,\"msg\":\"查询超时\"}"));
        Map<String, Object> result = call("payments", Map.of());
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("查询超时"), error);
        assertFalse(error.contains(BizQueryClient.EMPTY_NOTE));
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ 空结果：rows:[] + note，不是 error
    // ══════════════════════════════════════════════════════════════════

    @Test
    void emptyRowsIsNotAnError() {
        reply.set(new Reply(200, okRows("[]")));

        Map<String, Object> result = call("contracts", Map.of("filters", Map.of("projectId", 12)));

        assertFalse(result.containsKey("error"), "空结果不许伪装成错误");
        assertEquals(List.of(), result.get("rows"));
        assertEquals(0, result.get("count"));
        assertEquals(BizQueryClient.EMPTY_NOTE, result.get("note"));
        // 口径与数据时间仍要带出来：模型要说"该范围内没有匹配数据（口径…；数据时间…）"
        assertEquals("按立项年度、含子项目", result.get("caliber"));
        assertEquals("2026-09-23 16:40:00", result.get("data_time"));
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤ 非法/越权 entity：结构化错误，不抛异常
    // ══════════════════════════════════════════════════════════════════

    /** 模型幻觉出 {@code invoices}：错误里必须列出四个合法枚举（并给出中文含义）。 */
    @Test
    void hallucinatedEntityListsLegalValues() {
        Map<String, Object> result = call("invoices", Map.of());

        assertTrue(result.containsKey("error"));
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("invoices"), error);
        for (String entity : Tools.BIZ_ENTITIES) {
            assertTrue(error.contains(entity), "错误里必须列出合法枚举 " + entity + "：" + error);
        }
        assertNull(capturedPath, "entity 非法就不该发请求");
    }

    @Test
    void missingEntityIsStructuredError() {
        Map<String, Object> result = tools.execute(
                Tools.BIZ_QUERY_TOOL_NAME, Map.of("filters", Map.of()), null, spec);
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("缺少 entity"));
        assertNull(capturedPath);
    }

    /** 授权范围之外的 entity：说清"无权"，不查、也不当"没有数据"。 */
    @Test
    void entityOutsideAuthorizedScopeIsRejectedLocally() {
        BizQuerySpec narrow = new BizQuerySpec(baseUrl(), "tok-abc", List.of("projects"));
        Map<String, Object> result = call("payments", Map.of(), narrow);

        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("授权范围"), error);
        assertTrue(error.contains("payments"), error);
        assertNull(capturedPath, "范围外的实体不该发请求");
    }

    /** 未开通通道（本次问答没有 biz_query）：结构化错误，不抛异常。 */
    @Test
    void withoutChannelToolIsStructuredError() {
        Map<String, Object> result = call("projects", Map.of(), null);
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("未开通"));
    }

    /** 通道传了但地址/凭据为空（主系统侧配置错）：报"通道不可用"，不许静默当成"没有数据"。 */
    @Test
    void incompleteChannelIsStructuredError() {
        BizQuerySpec broken = new BizQuerySpec("  ", "", List.of());
        Map<String, Object> result = call("projects", Map.of(), broken);
        assertTrue(result.containsKey("error"));
        assertTrue(String.valueOf(result.get("error")).contains("scope_token"));
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤′ 5xx / 连不上：明确"调用失败"，绝不静默变"未找到"
    // ══════════════════════════════════════════════════════════════════

    @Test
    void serverErrorIsExplicitlyAFailure() {
        reply.set(new Reply(500, "内部错误：数据库连接池耗尽"));

        Map<String, Object> result = call("projects", Map.of());

        assertTrue(result.containsKey("error"), "5xx 必须是 error（不得静默变成「未找到」）");
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("500"), error);
        assertTrue(error.contains("调用失败"), error);
        assertFalse(error.contains(BizQueryClient.EMPTY_NOTE), "失败与「没有数据」必须区分");
    }

    @Test
    void connectionFailureIsExplicitlyAFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        reply.set(new Reply(200, okRows("[]")));

        Map<String, Object> result = call(
                "projects", Map.of(), new BizQuerySpec("http://127.0.0.1:" + closedPort, "tok", List.of()));

        assertTrue(result.containsKey("error"));
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("调用失败"), error);
        assertFalse(error.contains(BizQueryClient.EMPTY_NOTE));
    }

    /** 200 但没有 rows 字段（接口形状变了）：按错误报，不许当成"没有数据"。 */
    @Test
    void missingRowsFieldIsErrorNotEmptyResult() {
        reply.set(new Reply(200, "{\"code\":0,\"data\":{\"total\":0}}"));
        Map<String, Object> result = call("projects", Map.of());
        assertTrue(result.containsKey("error"), "缺 rows 不能当空结果");
        String error = String.valueOf(result.get("error"));
        assertTrue(error.contains("rows"), error);
        assertFalse(error.contains(BizQueryClient.EMPTY_NOTE));
    }

    /** 返回不是 JSON：同样是明确错误。 */
    @Test
    void nonJsonSuccessBodyIsError() {
        reply.set(new Reply(200, "<html>502 Bad Gateway</html>"));
        Map<String, Object> result = call("projects", Map.of());
        assertTrue(String.valueOf(result.get("error")).contains("不是合法 JSON"));
    }
}
