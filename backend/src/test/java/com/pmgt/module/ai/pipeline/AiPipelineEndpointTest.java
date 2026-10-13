package com.pmgt.module.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.exception.GlobalExceptionHandler;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.pipeline.controller.AiPipelineController;
import com.pmgt.module.ai.pipeline.controller.AiPipelineViewController;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineArtifactMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineStageMapper;
import com.pmgt.module.ai.pipeline.service.AiPipelineClaimService;
import com.pmgt.module.ai.pipeline.service.AiPipelineEventWriter;
import com.pmgt.module.ai.pipeline.service.AiPipelineQueryService;
import com.pmgt.module.ai.pipeline.service.AiPipelineReportService;
import com.pmgt.module.ai.pipeline.service.AiPipelineRunLookup;
import com.pmgt.module.ai.pipeline.service.AiPipelineTokenService;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.service.AiAttachmentIndexWriter;
import com.pmgt.module.ai.service.AiScopeResolver;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 端到端（MockMvc 独立装配）：<b>真实 HTTP 状态码 + 真实响应体</b>。
 *
 * <p>它跑的是真实的 Controller + 真实 Service + 真实过滤器 + 真实
 * {@code GlobalExceptionHandler}，只把 Mapper 换成桩。因此这里断言的状态码
 * （401/400/404/409/200）与响应 JSON 就是线上会给出的形状。
 *
 * <p>同时把每一组"请求 → 响应"写进 {@code target/pipeline-endpoint-samples.txt}
 * （target/ 已在 .gitignore 内）：报告里的接口样例直接取自这份文件，
 * 不是手写的"预期长相"。凭据类字段（pipeline_token）在写入前被替换成占位符。
 */
class AiPipelineEndpointTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String SERVICE_TOKEN = "service-token-abc-0123456789";
    private static final String H = AiPipelineTokenFilter.HEADER;
    private static final Path SAMPLES = Path.of("target", "pipeline-endpoint-samples.txt");

    private static final StringBuilder OUT = new StringBuilder();

    /** 样例文件在整轮测试里只清一次（每个 @Test 都会跑 @BeforeEach，不能每次都清空）。 */
    private static boolean samplesInitialized;

    private MockMvc mvc;
    private AttachmentAiTaskMapper taskMapper;
    private AiPipelineStageMapper stageMapper;
    private AiPipelineArtifactMapper artifactMapper;
    private AiPipelineEventMapper eventMapper;
    private AttachmentMapper attachmentMapper;
    private AiScopeResolver scopeResolver;
    private AiPipelineProperties props;

    @BeforeEach
    void setUp(org.junit.jupiter.api.TestInfo testInfo) throws Exception {
        // LambdaUpdateWrapper/LambdaQueryWrapper 需要 MyBatis-Plus 的实体元信息缓存，
        // 而 MockMvc 独立装配没有 Spring/MyBatis 的启动过程（踩过一次：报
        // "can not find lambda cache for this entity"，且被兜底处理器变成 200+code500）
        TestTableInfoInitializer.init();
        if (!samplesInitialized) {
            Files.writeString(SAMPLES, "# 流水线端点样例（由 AiPipelineEndpointTest 生成，凭据已脱敏）\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            samplesInitialized = true;
        }
        OUT.append("\n## ").append(testInfo.getDisplayName()).append('\n');
        flush();

        taskMapper = mock(AttachmentAiTaskMapper.class);
        stageMapper = mock(AiPipelineStageMapper.class);
        artifactMapper = mock(AiPipelineArtifactMapper.class);
        eventMapper = mock(AiPipelineEventMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        scopeResolver = mock(AiScopeResolver.class);
        props = new AiPipelineProperties();
        props.setServiceToken(SERVICE_TOKEN);
        ObjectMapper om = new ObjectMapper();

        AiAttachmentIndexWriter indexWriter = new AiAttachmentIndexWriter(attachmentMapper);
        AiPipelineEventWriter events = new AiPipelineEventWriter(eventMapper);
        AiPipelineTokenService tokens = new AiPipelineTokenService(SECRET);
        AiPipelineRunLookup lookup = new AiPipelineRunLookup(taskMapper);
        AiPipelineClaimService claimService =
                new AiPipelineClaimService(taskMapper, events, props, tokens, lookup, indexWriter);
        AiPipelineReportService reportService = new AiPipelineReportService(
                lookup, stageMapper, artifactMapper, taskMapper, events, props, indexWriter, om);
        AiPipelineQueryService queryService = new AiPipelineQueryService(
                taskMapper, stageMapper, artifactMapper, eventMapper, props, scopeResolver, om);

        mvc = MockMvcBuilders.standaloneSetup(
                        new AiPipelineController(claimService, reportService, queryService, tokens),
                        new AiPipelineViewController(queryService, scopeResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AiPipelineTokenFilter(props, om))
                // 响应用 UTF-8 解码：MockHttpServletResponse 默认 ISO-8859-1，
                // 否则 getContentAsString() 会把中文全变成 '?'
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();

        when(scopeResolver.projectNames(any())).thenReturn(Map.of(56L, "某政务信息化项目"));
        when(scopeResolver.requireAccessible(34L)).thenReturn(attachment());
        when(eventMapper.insert(any(AiPipelineEvent.class))).thenAnswer(inv -> {
            AiPipelineEvent row = inv.getArgument(0);
            row.setId(1L);
            return 1;
        });
        when(artifactMapper.insert(any(AiPipelineArtifact.class))).thenAnswer(inv -> {
            AiPipelineArtifact row = inv.getArgument(0);
            row.setId(5L);
            return 1;
        });
    }

    /** 一个"排队中"的任务（claim 用例用）。 */
    private static AttachmentAiTask queuedTask() {
        AttachmentAiTask task = runningTask();
        task.setStatus(AttachmentAiTask.QUEUED);
        task.setClaimedBy(null);
        task.setClaimExpiresAt(null);
        return task;
    }

    private static Attachment attachment() {
        Attachment a = new Attachment();
        a.setId(34L);
        a.setFileName("中标通知书.pdf");
        return a;
    }

    private static AttachmentAiTask runningTask() {
        AttachmentAiTask task = new AttachmentAiTask();
        task.setId(12L);
        task.setAttachmentId(34L);
        task.setProjectId(56L);
        task.setFilename("中标通知书.pdf");
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setTaskType(AttachmentAiTask.TYPE_PARSE);
        task.setPriority(10);
        task.setAttempts(1);
        task.setProgress(30);
        task.setStageKey("PARSE");
        task.setStage("识别");
        task.setStageDone(20);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(60));
        task.setContentHash("abc123");
        task.setPipelineRev("parse=1,clean=0,chunk=0");
        task.setCleanRev(0);
        task.setCreateTime(LocalDateTime.now().minusMinutes(3));
        task.setUpdateTime(LocalDateTime.now());
        return task;
    }

    private MvcResult call(String label, String method, String path,
                           MockHttpServletRequestBuilder builder, String requestJson) throws Exception {
        boolean pipeline = path.startsWith("/api/ai/pipeline");
        if (pipeline) {
            builder.header(H, SERVICE_TOKEN);
        }
        if (requestJson != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(requestJson);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        // MockHttpServletResponse 的默认字符集是 ISO-8859-1：必须显式按 UTF-8 解码，
        // 否则中文全变 '?'（断言会莫名其妙失败，且样例文件是乱码）
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 样例里不能出现真实凭据（与"绝不写进日志/异常"同一条纪律）
        body = body.replaceAll("\"pipeline_token\":\"[^\"]*\"", "\"pipeline_token\":\"<redacted>\"")
                .replaceAll("\"pipelineToken\":\"[^\"]*\"", "\"pipelineToken\":\"<redacted>\"");
        OUT.append("── ").append(label).append('\n')
                .append("请求  : ").append(method).append(' ').append(path)
                .append(requestJson == null ? "" : "\n        body: " + requestJson).append('\n')
                .append("响应  : HTTP ").append(result.getResponse().getStatus()).append('\n')
                .append("        ").append(body).append('\n');
        flush();
        return result;
    }

    /** 把已收集的样例落盘（报告直接引用它，不手写"预期长相"）。 */
    private static void flush() throws java.io.IOException {
        Files.writeString(SAMPLES, OUT.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── 1~9：AI 服务侧端点 ──────────────────────────────────────────

    @Test
    void claim端点返回领取详情且空队列是404() throws Exception {
        // 一次 claim 有两次 selectList（先回收过期行、再取候选）；单线程下顺序确定，
        // 用"连续返回值"区分：第一次空（没有过期行）、之后返回候选任务
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(queuedTask()));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        MvcResult ok = call("1) 领取任务（成功）", "POST", "/api/ai/pipeline/claim",
                post("/api/ai/pipeline/claim"),
                "{\"task_type\":\"PARSE\",\"limit\":1,\"claimed_by\":\"ai-node-1/8899\",\"lease_seconds\":90}");
        assertEquals(200, ok.getResponse().getStatus());
        String body = ok.getResponse().getContentAsString();
        assertTrue(body.contains("\"run_id\":\"run-12\""), body);
        assertTrue(body.contains("\"task_key\":"), body);
        assertTrue(body.contains("\"pipeline_token\":\""), body);
        assertTrue(body.contains("\"lease_expires_at\":"), body);
        assertTrue(body.contains("\"heartbeat_seconds\":30"), body);
        assertTrue(body.contains("\"runId\":\"run-12\""), "camelCase 别名也要在：" + body);

        when(taskMapper.selectList(any())).thenReturn(List.of());
        MvcResult empty = call("1b) 领取任务（队列为空）", "POST", "/api/ai/pipeline/claim",
                post("/api/ai/pipeline/claim"), "{\"claimed_by\":\"ai-node-1/8899\"}");
        assertEquals(404, empty.getResponse().getStatus(), "空队列是 404（没领到），不是 200");
        assertTrue(empty.getResponse().getContentAsString().contains("当前没有可领取的任务"),
                empty.getResponse().getContentAsString());

        MvcResult bad = call("1c) 领取任务（limit 不是 1）", "POST", "/api/ai/pipeline/claim",
                post("/api/ai/pipeline/claim"), "{\"limit\":3,\"claimed_by\":\"ai-node-1/8899\"}");
        assertEquals(400, bad.getResponse().getStatus());
    }

    @Test
    void 心跳端点在租约失效时409() throws Exception {
        AttachmentAiTask task = runningTask();
        task.setClaimExpiresAt(LocalDateTime.now().minusSeconds(10));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        MvcResult result = call("2) 续约（租约已失效）", "POST", "/api/ai/pipeline/runs/run-12/heartbeat",
                post("/api/ai/pipeline/runs/run-12/heartbeat"),
                "{\"claimed_by\":\"ai-node-1/8899\",\"lease_seconds\":90}");

        assertEquals(409, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("租约已失效"),
                result.getResponse().getContentAsString());
    }

    @Test
    void 心跳续约成功返回新的到期时刻() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        MvcResult result = call("2b) 续约（成功）", "POST", "/api/ai/pipeline/runs/run-12/heartbeat",
                post("/api/ai/pipeline/runs/run-12/heartbeat"),
                "{\"claimed_by\":\"ai-node-1/8899\",\"lease_seconds\":120}");

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"lease_seconds\":120"), body);
        assertTrue(body.contains("\"leaseSeconds\":120"), body);
    }

    @Test
    void release端点放回队列() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        MvcResult result = call("3) 放回队列", "POST", "/api/ai/pipeline/runs/run-12/release",
                post("/api/ai/pipeline/runs/run-12/release"),
                "{\"claimed_by\":\"ai-node-1/8899\",\"reason\":\"平台 OCR 不可用，先放回\"}");

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"status\":\"QUEUED\""),
                result.getResponse().getContentAsString());
    }

    @Test
    void 阶段上报端点幂等且返回attempts() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        AtomicReference<AiPipelineStage> table = new AtomicReference<>();
        when(stageMapper.selectOne(any())).thenAnswer(inv -> table.get());
        when(stageMapper.insert(any(AiPipelineStage.class))).thenAnswer(inv -> {
            AiPipelineStage row = inv.getArgument(0);
            row.setId(900L);
            table.set(row);
            return 1;
        });
        when(stageMapper.updateById(any(AiPipelineStage.class))).thenAnswer(inv -> {
            table.set(inv.getArgument(0));
            return 1;
        });

        MvcResult first = call("4) 阶段上报（首次：识别 RUNNING）", "POST",
                "/api/ai/pipeline/runs/run-12/stages/PARSE",
                post("/api/ai/pipeline/runs/run-12/stages/PARSE"),
                "{\"status\":\"RUNNING\",\"item_count\":20,\"counters\":{\"pages\":20}}");
        assertEquals(200, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsString().contains("\"attempts\":1"),
                first.getResponse().getContentAsString());

        MvcResult second = call("4b) 阶段上报（同键第二次：识别 DONE）", "POST",
                "/api/ai/pipeline/runs/run-12/stages/PARSE",
                post("/api/ai/pipeline/runs/run-12/stages/PARSE"),
                "{\"status\":\"DONE\",\"item_count\":20,\"item_failed\":2,\"elapsed_ms\":8400,"
                        + "\"output_digest\":\"20 页 / 12,340 字\",\"doc_id\":\"doc-777\"}");
        assertEquals(200, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("\"attempts\":2"),
                "同键第二次上报必须 attempts=2（幂等语义）：" + second.getResponse().getContentAsString());
        assertEquals(2, table.get().getAttempts().intValue(),
                "数据库里始终是同一行（插入一次、更新一次），attempts=2");

        MvcResult badKey = call("4c) 阶段上报（非法 stage_key）", "POST",
                "/api/ai/pipeline/runs/run-12/stages/PDF2PNG",
                post("/api/ai/pipeline/runs/run-12/stages/PDF2PNG"), "{\"status\":\"RUNNING\"}");
        assertEquals(400, badKey.getResponse().getStatus());
    }

    @Test
    void 产物追加端点rev自增() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        AtomicReference<Integer> maxRev = new AtomicReference<>(0);
        when(artifactMapper.selectList(any())).thenAnswer(inv -> maxRev.get() == 0
                ? List.of() : List.of(artifact(maxRev.get())));

        MvcResult first = call("5) 追加产物（切片清单，第 1 版）", "POST",
                "/api/ai/pipeline/runs/run-12/artifacts",
                post("/api/ai/pipeline/runs/run-12/artifacts"),
                "{\"artifact_type\":\"chunk_manifest\",\"item_count\":30,\"char_count\":99000,"
                        + "\"object_key\":\"chunks/docX.json\",\"sha1\":\"ab12cd34\","
                        + "\"content\":{\"chunks\":[{\"chunk_key\":\"docX:3:ab12:0:1\",\"page_no\":3}]}}");
        assertEquals(200, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsString().contains("\"artifact_rev\":1"),
                first.getResponse().getContentAsString());

        maxRev.set(1);
        MvcResult second = call("5b) 追加产物（同类型第 2 版，只 INSERT）", "POST",
                "/api/ai/pipeline/runs/run-12/artifacts",
                post("/api/ai/pipeline/runs/run-12/artifacts"),
                "{\"artifact_type\":\"chunk_manifest\",\"item_count\":31,\"note\":\"切分规则微调后重跑\"}");
        assertEquals(200, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("\"artifact_rev\":2"),
                second.getResponse().getContentAsString());

        MvcResult badType = call("5c) 追加产物（非法 artifact_type）", "POST",
                "/api/ai/pipeline/runs/run-12/artifacts",
                post("/api/ai/pipeline/runs/run-12/artifacts"), "{\"artifact_type\":\"raw_text\"}");
        assertEquals(400, badType.getResponse().getStatus());
    }

    @Test
    void 事件上报端点只追加审计() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());

        MvcResult result = call("6) 状态迁移审计", "POST", "/api/ai/pipeline/runs/run-12/events",
                post("/api/ai/pipeline/runs/run-12/events"),
                "{\"from_status\":\"RUNNING\",\"to_status\":\"CANCELLED\","
                        + "\"reason\":\"CANCEL_BY_USER:9\",\"operator\":\"user-9\"}");

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"to_status\":\"CANCELLED\""),
                result.getResponse().getContentAsString());
    }

    @Test
    void finish端点终态与非法迁移() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        MvcResult ok = call("7) 终态（DONE + doc_id）", "POST", "/api/ai/pipeline/runs/run-12/finish",
                post("/api/ai/pipeline/runs/run-12/finish"),
                "{\"status\":\"DONE\",\"doc_id\":\"doc-777\","
                        + "\"pipeline_rev\":\"parse=1,clean=1,chunk=1,embed=ab12cd34\",\"clean_rev\":1}");
        assertEquals(200, ok.getResponse().getStatus());
        String body = ok.getResponse().getContentAsString();
        assertTrue(body.contains("\"attachment_index_status\":\"READY\""), body);
        assertTrue(body.contains("\"attachmentIndexStatus\":\"READY\""), body);

        AttachmentAiTask done = runningTask();
        done.setStatus(AttachmentAiTask.DONE);
        when(taskMapper.selectById(12L)).thenReturn(done);
        MvcResult illegal = call("7b) 终态（DONE → FAILED：非法迁移）", "POST",
                "/api/ai/pipeline/runs/run-12/finish",
                post("/api/ai/pipeline/runs/run-12/finish"), "{\"status\":\"FAILED\"}");
        assertEquals(400, illegal.getResponse().getStatus());
        assertTrue(illegal.getResponse().getContentAsString().contains("允许的目标状态"),
                illegal.getResponse().getContentAsString());

        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(0);
        MvcResult conflict = call("7c) 终态（并发冲突）", "POST", "/api/ai/pipeline/runs/run-12/finish",
                post("/api/ai/pipeline/runs/run-12/finish"), "{\"status\":\"DONE\"}");
        assertEquals(409, conflict.getResponse().getStatus());
    }

    @Test
    void 详情端点给出阶段产物事件与投影提示() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(stageMapper.selectList(any())).thenReturn(List.of(stage("PARSE", "RUNNING",
                LocalDateTime.now().minusHours(2)), stage("CHUNK", "DONE", LocalDateTime.now())));
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact(2)));
        when(eventMapper.selectList(any())).thenReturn(List.of(event()));

        MvcResult result = call("8) 运行详情（含投影兜底）", "GET", "/api/ai/pipeline/runs/run-12",
                get("/api/ai/pipeline/runs/run-12"), null);

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"display_status\":\"STALE\""), body);
        assertTrue(body.contains("\"projection_note\":"), body);
        assertTrue(body.contains("\"stale_stage_keys\":[\"PARSE\"]"), body);
        assertTrue(body.contains("\"artifacts\":[{\"id\":5"), body);
        assertTrue(body.contains("\"events\":["), body);
        // 详情里的产物带 content（前端产物抽屉直接读它），但只到"摘要"级别：
        // 全文在 AI 服务 work/（object_key 指路），主系统这一列 ≤4000 字符
        assertTrue(body.contains("\"content\":{"), "详情产物应带解析后的 content：" + body);
        assertTrue(body.contains("\"content_chars\":"), body);
    }

    @Test
    void 用户侧提供与冻结契约同形状的列表与详情路径() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(stageMapper.selectList(any())).thenReturn(List.of(stage("PARSE", "RUNNING", LocalDateTime.now())));
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact(2)));
        when(eventMapper.selectList(any())).thenReturn(List.of(event()));
        when(taskMapper.selectList(any())).thenReturn(List.of(runningTask()));

        MvcResult list = call("15) 用户侧列表（与冻结契约同形状：/api/ai/runs）", "GET",
                "/api/ai/runs?attachment_id=34&status=RUNNING&page=1&size=20",
                get("/api/ai/runs?attachment_id=34&status=RUNNING&page=1&size=20"), null);
        assertEquals(200, list.getResponse().getStatus());
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(listBody.contains("\"records\":["), listBody);
        assertTrue(listBody.contains("\"run_id\":\"run-12\""), listBody);
        assertTrue(!listBody.contains("stages"), "列表不带阶段明细（详情另取）：" + listBody);

        MvcResult detail = call("15b) 用户侧详情（run_id 直取：/api/ai/runs/{run_id}/pipeline）", "GET",
                "/api/ai/runs/run-12/pipeline", get("/api/ai/runs/run-12/pipeline"), null);
        assertEquals(200, detail.getResponse().getStatus());
        assertTrue(detail.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"stages\":["),
                detail.getResponse().getContentAsString(StandardCharsets.UTF_8));

        MvcResult badRun = call("15c) 用户侧详情（run_id 形状不对）", "GET",
                "/api/ai/runs/not-a-run/pipeline", get("/api/ai/runs/not-a-run/pipeline"), null);
        assertEquals(400, badRun.getResponse().getStatus());
    }

    @Test
    void 列表端点分页与状态过滤() throws Exception {
        when(taskMapper.selectList(any())).thenReturn(List.of(runningTask(), runningTask()));

        MvcResult result = call("9) 运行列表（按附件 + 分页）", "GET",
                "/api/ai/pipeline/runs?attachment_id=34&page=1&size=1",
                get("/api/ai/pipeline/runs?attachment_id=34&page=1&size=1"), null);

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"total\":2"), body);
        assertTrue(body.contains("\"size\":1"), body);

        MvcResult bad = call("9b) 运行列表（非法 status）", "GET",
                "/api/ai/pipeline/runs?status=PARSING",
                get("/api/ai/pipeline/runs?status=PARSING"), null);
        assertEquals(400, bad.getResponse().getStatus());
    }

    // ── 鉴权与报文错误 ─────────────────────────────────────────────

    @Test
    void 缺少服务间令牌时401() throws Exception {
        MvcResult result = mvc.perform(post("/api/ai/pipeline/claim")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":401"), body);
        OUT.append("── 10) 缺少 X-AI-Service-Token\n")
                .append("请求  : POST /api/ai/pipeline/claim（只带 Content-Type，不带任何令牌头）\n")
                .append("响应  : HTTP 401\n        ").append(body).append('\n');
        flush();
    }

    @Test
    void 用户JWT不能调该前缀() throws Exception {
        String userJwt = new com.pmgt.common.security.JwtUtil(SECRET, 168)
                .createToken(9L, "admin", "管理员", com.pmgt.common.security.Role.ADMIN);

        MvcResult result = mvc.perform(post("/api/ai/pipeline/claim")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus(), "该前缀只认 X-AI-Service-Token");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        OUT.append("── 10b) 拿合法用户 JWT 调流水线前缀（必须被拒）\n")
                .append("请求  : POST /api/ai/pipeline/claim（Authorization: Bearer <用户JWT>）\n")
                .append("响应  : HTTP 401\n        ").append(body).append('\n');
        flush();
    }

    @Test
    void 请求体坏掉时真实400() throws Exception {
        MvcResult result = mvc.perform(post("/api/ai/pipeline/claim")
                        .header(H, SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("请求体格式错误"), body);
        OUT.append("── 10c) 请求体不是合法 JSON\n")
                .append("请求  : POST /api/ai/pipeline/claim（body: {not-json）\n")
                .append("响应  : HTTP 400\n        ").append(body).append('\n');
        flush();
    }

    // ── 前端只读端点（用户 JWT 侧） ────────────────────────────────

    @Test
    void 前端任务流端点返回阶段条所需字段() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(stageMapper.selectList(any())).thenReturn(List.of(stage("PARSE", "RUNNING", LocalDateTime.now())));
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact(2)));
        when(eventMapper.selectList(any())).thenReturn(List.of(event()));

        MvcResult result = call("11) 前端任务流视图（按 taskId）", "GET", "/api/ai/tasks/12/pipeline",
                get("/api/ai/tasks/12/pipeline"), null);

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"stage_key\":\"PARSE\""), body);
        assertTrue(body.contains("\"elapsed_ms\":8400"), body);
        assertTrue(body.contains("\"item_count\":20"), body);
        assertTrue(body.contains("\"item_failed\":2"), body);
    }

    @Test
    void 前端切片清单端点给出chunk_key映射入口() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        AiPipelineArtifact manifest = artifact(1);
        manifest.setArtifactType("chunk_manifest");
        manifest.setStageKey("CHUNK");
        manifest.setContentJson("{\"chunks\":[{\"chunk_key\":\"docX:3:ab12:0:1\",\"page_no\":3}]}");
        // char_count = 摘要未被截断时的真实长度（>内容长度就会被判 truncated）
        manifest.setCharCount((long) manifest.getContentJson().length());
        when(artifactMapper.selectList(any())).thenReturn(List.of(manifest));

        MvcResult result = call("12) 前端取切片清单（chunk_key 映射入口）", "GET",
                "/api/ai/tasks/12/pipeline/artifacts/chunk_manifest",
                get("/api/ai/tasks/12/pipeline/artifacts/chunk_manifest"), null);

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("chunk_key"), body);
        assertTrue(body.contains("docX:3:ab12:0:1"), body);
        assertTrue(body.contains("\"truncated\":false"), body);

        MvcResult list = call("12b) 前端产物清单（含 content）", "GET",
                "/api/ai/tasks/12/pipeline/artifacts",
                get("/api/ai/tasks/12/pipeline/artifacts"), null);
        assertEquals(200, list.getResponse().getStatus());
        // 产物清单是"详情级"读取（每个 run ≤6 条）：带 content 才让前端抽屉一次拿全
        assertTrue(list.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"content\":"),
                list.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 前端附件入口与阶段字典() throws Exception {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(stageMapper.selectList(any())).thenReturn(List.of());
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of(runningTask()));

        MvcResult pipeline = call("13) 按附件取最近一次任务流", "GET", "/api/ai/attachments/34/pipeline",
                get("/api/ai/attachments/34/pipeline"), null);
        assertEquals(200, pipeline.getResponse().getStatus());

        MvcResult dict = call("14) 阶段字典（前端不写死映射）", "GET", "/api/ai/stage-dict",
                get("/api/ai/stage-dict"), null);
        assertEquals(200, dict.getResponse().getStatus());
        assertTrue(dict.getResponse().getContentAsString().contains("\"stage_name\":\"识别\""),
                dict.getResponse().getContentAsString());
    }

    private static AiPipelineStage stage(String key, String status, LocalDateTime lastTouch) {
        AiPipelineStage stage = new AiPipelineStage();
        stage.setId(1L);
        stage.setRunId("run-12");
        stage.setTaskId(12L);
        stage.setStageKey(key);
        stage.setStageName("识别");
        stage.setSeq(1);
        stage.setStatus(status);
        stage.setElapsedMs(8400L);
        stage.setItemCount(20);
        stage.setItemFailed(2);
        stage.setAttempts(1);
        stage.setStartedAt(lastTouch);
        stage.setCreateTime(lastTouch);
        stage.setUpdateTime(lastTouch);
        return stage;
    }

    private static AiPipelineArtifact artifact(int rev) {
        AiPipelineArtifact artifact = new AiPipelineArtifact();
        artifact.setId(5L);
        artifact.setRunId("run-12");
        artifact.setTaskId(12L);
        artifact.setAttachmentId(34L);
        artifact.setStageKey("CHUNK");
        artifact.setArtifactType("chunk_manifest");
        artifact.setArtifactRev(rev);
        artifact.setObjectKey("chunks/docX.json");
        artifact.setContentJson("{\"chunk_count\":30,\"avg_char_len\":412}");
        artifact.setCharCount(58L);
        artifact.setItemCount(30);
        artifact.setSha1("ab12cd34");
        artifact.setCreateTime(LocalDateTime.now());
        return artifact;
    }

    private static AiPipelineEvent event() {
        AiPipelineEvent event = new AiPipelineEvent();
        event.setId(1L);
        event.setRunId("run-12");
        event.setTaskId(12L);
        event.setFromStatus("QUEUED");
        event.setToStatus("RUNNING");
        event.setReason("CLAIM:ai-node-1/8899");
        event.setOperator("ai-node-1/8899");
        event.setCreateTime(LocalDateTime.now());
        return event;
    }
}
