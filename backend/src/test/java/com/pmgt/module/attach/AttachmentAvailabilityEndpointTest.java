package com.pmgt.module.attach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.exception.GlobalExceptionHandler;
import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.common.storage.AttachmentStorageProperties;
import com.pmgt.common.storage.ObjectStat;
import com.pmgt.module.attach.controller.AttachmentController;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.attach.mapper.AttachmentUploadTaskMapper;
import com.pmgt.module.attach.service.AttachmentAvailabilityService;
import com.pmgt.module.attach.service.AttachmentUploadService;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.service.ContractLinkService;
import com.pmgt.module.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 附件可用性批量探测接口（{@code GET /api/attachments/availability}）的端到端用例。
 *
 * <p>装配方式与 {@code AiPipelineEndpointTest} 一致：<b>真实 Controller + 真实 Service +
 * 真实 {@code GlobalExceptionHandler}</b>，只把 Mapper 与 {@link AttachmentStorage} 换成桩。
 * 因此这里断言的 HTTP 状态码与响应 JSON 就是线上会给出的形状。
 *
 * <p>桩存储（{@link StubStorage}）刻意能"逐条不同"：可用的返回 {@link ObjectStat}，
 * 不存在的返回 {@code null}，指定路径抛异常——用来钉住"单条失败不影响其它条"这条要求。
 *
 * <p>每组"请求 → 响应"都会写进 {@code target/attachment-availability-samples.txt}
 * （{@code target/} 在 .gitignore 内）：报告里的接口样例直接取自这份文件，不是手写的"预期长相"。
 */
class AttachmentAvailabilityEndpointTest {

    private static final Path SAMPLES = Path.of("target", "attachment-availability-samples.txt");

    private static final StringBuilder OUT = new StringBuilder();

    /** 样例文件在整轮测试里只清一次（每个 @Test 都会跑 @BeforeEach） */
    private static boolean samplesInitialized;

    /** 桩里"存储侧不可达"的异常文案：故意塞进 endpoint / AK / SK / 对象 key，用来验证不外泄 */
    private static final String LEAKY_MESSAGE =
            "https://obs.lhim.internal:443/some-bucket/uploads/2026/09/x.pdf AK=AKIA_TEST_ACCESS_KEY "
                    + "SK=TEST_SECRET_KEY_0123456789";

    private MockMvc mvc;
    private StubStorage storage;

    /** 当前"库里的附件元数据"（每个用例自己往里放行） */
    private Map<Long, Attachment> table;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        if (!samplesInitialized) {
            Files.writeString(SAMPLES, "# 附件可用性探测样例（由 AttachmentAvailabilityEndpointTest 生成）\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            samplesInitialized = true;
        }
        OUT.append("\n## ").append(testInfo.getDisplayName()).append('\n');
        flush();

        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        storage = new StubStorage();

        // 桩 Mapper 模拟 selectBatchIds 的真实语义：只返回"问到的且存在"的行（逻辑删除的行不会返回）
        Map<Long, Attachment> table = new HashMap<>();
        when(attachmentMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Collection<?> asked = inv.getArgument(0);
            List<Attachment> rows = new ArrayList<>();
            for (Object id : asked) {
                Attachment row = table.get(((Number) id).longValue());
                if (row != null) {
                    rows.add(row);
                }
            }
            return rows;
        });
        this.table = table;

        AttachmentStorageProperties props = new AttachmentStorageProperties();
        props.setType("obs");

        AttachmentAvailabilityService availabilityService =
                new AttachmentAvailabilityService(attachmentMapper, storage, props);

        AttachmentController controller = new AttachmentController(
                attachmentMapper,
                mock(ProjectMapper.class),
                mock(ProjectPhaseMapper.class),
                mock(PaymentMapper.class),
                mock(ContractMapper.class),
                mock(ContractLinkService.class),
                mock(SysUserMapper.class),
                mock(OperationLogService.class),
                storage,
                mock(AttachmentUploadService.class),
                mock(AttachmentUploadTaskMapper.class),
                availabilityService);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                // 响应用 UTF-8 解码：MockHttpServletResponse 默认 ISO-8859-1，否则中文断言必失败
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    private Attachment attachment(long id, String filePath) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setFileName("附件-" + id + ".pdf");
        a.setFileExt("pdf");
        a.setFilePath(filePath);
        // 登记的 file_size 与存储侧无关：接口一律回存储侧真实大小，不回落这个值
        a.setFileSize(12345L);
        table.put(id, a);
        return a;
    }

    private JsonNode call(String label, String query) throws Exception {
        String path = "/api/attachments/availability" + (query == null ? "" : "?" + query);
        MvcResult result = mvc.perform(get(path)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        OUT.append("── ").append(label).append('\n')
                .append("请求  : GET ").append(path).append('\n')
                .append("响应  : HTTP ").append(status).append('\n')
                .append("        ").append(body).append('\n');
        flush();
        return new ObjectMapper().readTree(body);
    }

    private static void flush() throws java.io.IOException {
        Files.writeString(SAMPLES, OUT.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── ① 全可用 ────────────────────────────────────────────────────

    @Test
    void 全部可用时逐条返回size且reason为null() throws Exception {
        attachment(12L, "2026/09/a.pdf");
        attachment(13L, "2026/09/b.pdf");
        storage.put("2026/09/a.pdf", 12345L);
        storage.put("2026/09/b.pdf", 999L);

        JsonNode body = call("1) 全可用（?ids=12,13）", "ids=12,13");

        assertEquals(0, body.get("code").asInt(), body.toString());
        JsonNode items = body.get("data").get("items");
        assertEquals(2, items.size());
        assertEquals(12, items.get(0).get("id").asLong());
        assertTrue(items.get(0).get("available").asBoolean(), body.toString());
        assertEquals(12345L, items.get(0).get("size").asLong());
        assertTrue(items.get(0).get("reason").isNull(), "可用时 reason 必须是 null：" + body);
        assertEquals(13, items.get(1).get("id").asLong());
        assertEquals(999L, items.get(1).get("size").asLong());
    }

    // ── ② 全不可用 ──────────────────────────────────────────────────

    @Test
    void 全部不可用时available为false且给通用原因() throws Exception {
        attachment(21L, "2026/09/missing-1.pdf");
        attachment(22L, "2026/09/missing-2.pdf");

        JsonNode body = call("2) 全不可用（存储里两条都不存在）", "ids=21,22");

        JsonNode items = body.get("data").get("items");
        assertEquals(2, items.size());
        for (JsonNode item : items) {
            assertFalse(item.get("available").asBoolean(), body.toString());
            assertTrue(item.get("size").isNull(), "不可用时 size 必须是 null：" + body);
            assertEquals(AttachmentAvailabilityService.REASON_MISSING_IN_STORAGE,
                    item.get("reason").asText());
        }
    }

    // ── ③ 混合：逐条独立判定 ────────────────────────────────────────

    @Test
    void 混合场景逐条独立判定且顺序与请求一致() throws Exception {
        attachment(31L, "2026/09/ok.pdf");
        attachment(32L, "2026/09/gone.pdf");
        storage.put("2026/09/ok.pdf", 1024L);

        JsonNode items = call("3) 混合（一条在、一条不在）", "ids=31,32").get("data").get("items");

        assertEquals(2, items.size());
        assertEquals(31, items.get(0).get("id").asLong(), "顺序必须与请求一致");
        assertTrue(items.get(0).get("available").asBoolean());
        assertEquals(1024L, items.get(0).get("size").asLong());
        assertEquals(32, items.get(1).get("id").asLong());
        assertFalse(items.get(1).get("available").asBoolean());
        assertEquals(AttachmentAvailabilityService.REASON_MISSING_IN_STORAGE,
                items.get(1).get("reason").asText());
    }

    /** 附件 id 存在但元数据行查不到（已逻辑删除 / id 不存在）→ 单独一句话，不伪装成"文件缺失" */
    @Test
    void 附件记录不存在时给记录原因() throws Exception {
        attachment(41L, "2026/09/here.pdf");
        storage.put("2026/09/here.pdf", 10L);

        JsonNode items = call("3b) 混合（其中一条元数据查不到）", "ids=41,404").get("data").get("items");

        assertTrue(items.get(0).get("available").asBoolean());
        assertFalse(items.get(1).get("available").asBoolean());
        assertEquals(AttachmentAvailabilityService.REASON_MISSING_RECORD, items.get(1).get("reason").asText());
    }

    // ── ④ 超上限 / 空 / 非法 → 真实 400 ─────────────────────────────

    @Test
    void 超过五十个id时真实400并说明上限() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= AttachmentAvailabilityService.MAX_IDS + 1; i++) {
            if (i > 1) {
                ids.append(',');
            }
            ids.append(i);
        }

        String path = "/api/attachments/availability?ids=" + ids;
        MvcResult result = mvc.perform(get(path)).andReturn();
        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(400, result.getResponse().getStatus(), "超上限必须是真实 HTTP 400：" + responseBody);
        JsonNode body = new ObjectMapper().readTree(responseBody);
        assertEquals(400, body.get("code").asInt());
        String message = body.get("message").asText();
        assertTrue(message.contains("50"), "报错里必须写出上限 50：" + message);
        OUT.append("── 4) 超上限（51 个 id）\n")
                .append("请求  : GET /api/attachments/availability?ids=1,2,…,51\n")
                .append("响应  : HTTP 400\n        ").append(responseBody).append('\n');
        flush();

        // 空 / 非法 id 同样是真实 400（不是 200 + code400，也不是 500）
        assertEquals(400, mvc.perform(get("/api/attachments/availability")).andReturn()
                .getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/attachments/availability?ids=")).andReturn()
                .getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/attachments/availability?ids=1,abc")).andReturn()
                .getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/attachments/availability?ids=1,-2")).andReturn()
                .getResponse().getStatus());
        OUT.append("── 4b) 空 / 非法 id（无参数、空串、1,abc、1,-2）\n")
                .append("响应  : 全部 HTTP 400\n");
        flush();
    }

    // ── ⑤ 单条存储异常：该条不可用，其它条不受影响 ──────────────────

    @Test
    void 单条存储异常只影响该条() throws Exception {
        attachment(51L, "2026/09/ok-1.pdf");
        attachment(52L, "2026/09/boom.pdf");
        attachment(53L, "2026/09/ok-2.pdf");
        storage.put("2026/09/ok-1.pdf", 111L);
        storage.put("2026/09/ok-2.pdf", 333L);
        storage.fail("2026/09/boom.pdf", new RuntimeException(LEAKY_MESSAGE));

        JsonNode items = call("5) 单条异常（中间那条 stat 抛异常）", "ids=51,52,53").get("data").get("items");

        assertEquals(3, items.size());
        assertTrue(items.get(0).get("available").asBoolean(), "异常条目前后两条必须照常判定：" + items);
        assertFalse(items.get(1).get("available").asBoolean());
        assertEquals(AttachmentAvailabilityService.REASON_MISSING_IN_STORAGE,
                items.get(1).get("reason").asText());
        assertTrue(items.get(2).get("available").asBoolean(), "异常条目前后两条必须照常判定：" + items);
        assertEquals(333L, items.get(2).get("size").asLong());
    }

    // ── ⑥ 不泄漏：端点/AK/SK/对象 key 全名/异常堆栈 ──────────────────

    @Test
    void 响应体不泄漏端点凭据与堆栈() throws Exception {
        attachment(61L, "2026/09/boom.pdf");
        storage.fail("2026/09/boom.pdf", new IllegalStateException(LEAKY_MESSAGE));

        String path = "/api/attachments/availability?ids=61";
        MvcResult result = mvc.perform(get(path)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(200, result.getResponse().getStatus());
        // 通用中文原因在
        assertTrue(body.contains(AttachmentAvailabilityService.REASON_MISSING_IN_STORAGE), body);
        // 四类内部信息一个都不许出现
        for (String leaked : List.of("obs.lhim.internal", "AKIA_TEST_ACCESS_KEY",
                "TEST_SECRET_KEY_0123456789", "some-bucket", "uploads/2026/09", "IllegalStateException",
                "java.lang", "at com.pmgt")) {
            assertFalse(body.contains(leaked), "响应体不得包含「" + leaked + "」：" + body);
        }
        OUT.append("── 6) 不泄漏（存储异常带 endpoint/AK/SK/对象 key）\n")
                .append("请求  : GET ").append(path).append('\n')
                .append("响应  : HTTP 200\n        ").append(body).append('\n');
        flush();
    }

    // ── 别名与缓存 ──────────────────────────────────────────────────

    @Test
    void attachmentIds别名同样接受() throws Exception {
        attachment(71L, "2026/09/alias.pdf");
        storage.put("2026/09/alias.pdf", 7L);

        JsonNode items = call("7) 别名 attachmentIds", "attachmentIds=71").get("data").get("items");

        assertEquals(1, items.size());
        assertEquals(71, items.get(0).get("id").asLong());
        assertTrue(items.get(0).get("available").asBoolean());
    }

    @Test
    void 六十秒内同一附件只探测一次() throws Exception {
        attachment(81L, "2026/09/cached.pdf");
        storage.put("2026/09/cached.pdf", 8L);

        call("8) 缓存：第一次探测", "ids=81");
        call("8b) 缓存：60 秒内第二次探测（不再访问存储）", "ids=81");

        assertEquals(1, storage.callsOf("2026/09/cached.pdf"),
                "60 秒内同一 id+filePath+存储类型 应复用上次结论，不应重复 HEAD");
    }

    /** 存储桩：可用的路径返回 {@link ObjectStat}，没登记路径返回 null（= 存储里没有），可指定路径抛异常 */
    private static final class StubStorage implements AttachmentStorage {

        private final Map<String, Long> sizes = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();

        void put(String relKey, Long size) {
            sizes.put(relKey, size);
        }

        void fail(String relKey, RuntimeException error) {
            failures.put(relKey, error);
        }

        int callsOf(String relKey) {
            return calls.getOrDefault(relKey, 0);
        }

        @Override
        public void save(String relKey, InputStream in, long size) {
            throw new UnsupportedOperationException("本用例不使用上传");
        }

        @Override
        public InputStream open(String relKey) {
            throw new UnsupportedOperationException("本用例不使用下载");
        }

        @Override
        public ObjectStat stat(String relKey) {
            calls.merge(relKey, 1, Integer::sum);
            RuntimeException failure = failures.get(relKey);
            if (failure != null) {
                throw failure;
            }
            Long size = sizes.get(relKey);
            // 本地/OBS 的 stat 语义：对象不存在返回 null（不抛异常）
            return size == null ? null : new ObjectStat("etag-" + relKey, size, null);
        }
    }
}
