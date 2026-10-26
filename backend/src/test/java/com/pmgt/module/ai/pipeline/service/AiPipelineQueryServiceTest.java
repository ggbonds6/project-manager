package com.pmgt.module.ai.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.dto.ArtifactContentVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunDetailVO;
import com.pmgt.module.ai.pipeline.dto.PipelineRunPageVO;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineArtifactMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineStageMapper;
import com.pmgt.module.ai.service.AiScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 详情/列表/产物读取，重点是<b>投影兜底（契约 10）</b>。
 *
 * <p>核心断言不是"标了 STALE"，而是"<b>除了 select 什么都没干</b>"：
 * {@code taskMapper/stageMapper/artifactMapper/eventMapper} 的写入方法一律 {@code never()}。
 * 读接口一旦会写库，"刷新页面"就成了一种副作用——同一个 run 刷新两次能看到不同的状态，
 * 排障时再也分不清"任务真的失败了"还是"被某次读取改坏了"。
 */
class AiPipelineQueryServiceTest {

    private AttachmentAiTaskMapper taskMapper;
    private AiPipelineStageMapper stageMapper;
    private AiPipelineArtifactMapper artifactMapper;
    private AiPipelineEventMapper eventMapper;
    private AiScopeResolver scopeResolver;
    private AiPipelineProperties props;
    private AiPipelineQueryService service;

    @BeforeEach
    void setUp() {
        TestTableInfoInitializer.init();
        taskMapper = mock(AttachmentAiTaskMapper.class);
        stageMapper = mock(AiPipelineStageMapper.class);
        artifactMapper = mock(AiPipelineArtifactMapper.class);
        eventMapper = mock(AiPipelineEventMapper.class);
        scopeResolver = mock(AiScopeResolver.class);
        props = new AiPipelineProperties();
        props.setStaleRunningSeconds(300);
        service = new AiPipelineQueryService(taskMapper, stageMapper, artifactMapper, eventMapper,
                props, scopeResolver, new ObjectMapper());
    }

    private static AttachmentAiTask task() {
        AttachmentAiTask task = new AttachmentAiTask();
        task.setId(12L);
        task.setAttachmentId(34L);
        task.setProjectId(56L);
        task.setFilename("中标通知书.pdf");
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setTaskType(AttachmentAiTask.TYPE_PARSE);
        task.setPriority(10);
        task.setAttempts(1);
        task.setContentHash("abc123");
        task.setPipelineRev("parse=1,clean=0,chunk=0");
        task.setCleanRev(0);
        task.setStageKey("PARSE");
        task.setStage("识别");
        task.setStageDone(20);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(60));
        task.setCreateTime(LocalDateTime.now().minusMinutes(10));
        task.setUpdateTime(LocalDateTime.now().minusMinutes(9));
        return task;
    }

    private static AiPipelineStage stage(String key, String status, LocalDateTime lastTouch) {
        AiPipelineStage stage = new AiPipelineStage();
        stage.setId((long) key.hashCode());
        stage.setRunId("run-12");
        stage.setStageKey(key);
        stage.setStageName("识别");
        stage.setSeq(1);
        stage.setStatus(status);
        stage.setUpdateTime(lastTouch);
        stage.setStartedAt(lastTouch);
        stage.setCreateTime(lastTouch);
        stage.setItemCount(20);
        stage.setItemFailed(0);
        stage.setElapsedMs(3000L);
        stage.setCountersJson("{\"empty_pages\":2}");
        stage.setAttempts(1);
        return stage;
    }

    private void stubDetail(AiPipelineStage... stages) {
        when(taskMapper.selectById(12L)).thenReturn(task());
        when(scopeResolver.projectNames(any())).thenReturn(Map.of(56L, "某政务信息化项目"));
        when(stageMapper.selectList(any())).thenReturn(List.of(stages));
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(eventMapper.selectList(any())).thenReturn(List.of());
    }

    // ── ⑦ 投影兜底：只影响读展示，不改 DB ───────────────────────────

    @Test
    void 长时间RUNNING的阶段被标STALE且不写库() {
        stubDetail(stage("PARSE", AiPipelineStage.RUNNING, LocalDateTime.now().minusHours(1)));

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        assertEquals(1, detail.getStages().size());
        assertEquals("STALE", detail.getStages().get(0).getDisplayStatus());
        assertTrue(detail.getStages().get(0).getStale());
        assertEquals(AiPipelineStage.RUNNING, detail.getStages().get(0).getStatus(),
                "DB 状态原样返回：STALE 只是展示层标记");
        assertEquals(List.of("PARSE"), detail.getStaleStageKeys());
        assertEquals(300, detail.getStaleAfterSeconds());
        assertNotNull(detail.getProjectionNote());
        assertTrue(detail.getProjectionNote().contains("只读投影"), detail.getProjectionNote());
        assertTrue(detail.getProjectionNote().contains("没有被修改"), detail.getProjectionNote());

        // 关键：读接口一行都不许写
        verify(taskMapper, never()).update(isNull(), any());
        verify(taskMapper, never()).updateById(any(AttachmentAiTask.class));
        verify(taskMapper, never()).insert(any(AttachmentAiTask.class));
        verify(stageMapper, never()).updateById(any(AiPipelineStage.class));
        verify(stageMapper, never()).insert(any(AiPipelineStage.class));
        verify(stageMapper, never()).update(any(), any());
        verify(artifactMapper, never()).insert(any(AiPipelineArtifact.class));
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 刚更新过的RUNNING阶段不标STALE() {
        stubDetail(stage("PARSE", AiPipelineStage.RUNNING, LocalDateTime.now()));

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        assertEquals("RUNNING", detail.getStages().get(0).getDisplayStatus());
        assertEquals(Boolean.FALSE, detail.getStages().get(0).getStale());
        assertTrue(detail.getStaleStageKeys().isEmpty());
        assertNull(detail.getProjectionNote(), "没有异常时不给提示，避免狼来了");
    }

    @Test
    void 终态阶段再久也不标STALE() {
        stubDetail(stage("PARSE", AiPipelineStage.DONE, LocalDateTime.now().minusDays(3)));

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        assertEquals("DONE", detail.getStages().get(0).getDisplayStatus());
        assertTrue(detail.getStaleStageKeys().isEmpty());
    }

    @Test
    void 阶段顺序按seq而不是插入顺序() {
        stubDetail(stage("CHUNK", AiPipelineStage.DONE, LocalDateTime.now()),
                stage("PARSE", AiPipelineStage.DONE, LocalDateTime.now()));

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        // CHUNK 的 seq 是 3、PARSE 是 1：即便映射里给 CHUNK 的 seq 被写成 1，
        // 这里断言的是"输出顺序由 stage_key 决定"，防止 DB 里的 seq 脏值把阶段条打乱
        assertEquals(2, detail.getStages().size());
        assertEquals("PARSE", detail.getStages().get(0).getStageKey());
    }

    @Test
    void 计数明细被解析成对象且原文保留() {
        stubDetail(stage("PARSE", AiPipelineStage.DONE, LocalDateTime.now()));

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        assertEquals(2, detail.getStages().get(0).getCounters().get("empty_pages").asInt());
        assertEquals("{\"empty_pages\":2}", detail.getStages().get(0).getCountersJson());
    }

    @Test
    void 详情带幂等键与当前阶段供前端列表用() {
        stubDetail();

        PipelineRunDetailVO detail = service.detailByRunId("run-12");

        assertEquals("run-12", detail.getRunId());
        assertEquals("某政务信息化项目", detail.getProjectName());
        assertEquals("PARSE", detail.getStageKey());
        assertEquals("识别", detail.getStage());
        assertEquals(20, detail.getStageDone());
        assertEquals("ai-node-1/8899", detail.getClaimedBy());
        assertNotNull(detail.getClaimExpiresAt());
        assertEquals(com.pmgt.module.ai.pipeline.AiPipelineKeys.taskKey("abc123", "parse=1,clean=0,chunk=0"),
                detail.getTaskKey());
        assertEquals(Boolean.FALSE, detail.getTerminal());
    }

    // ── 列表与产物读取 ──────────────────────────────────────────────

    @Test
    void 列表按附件与状态过滤并分页() {
        when(taskMapper.selectList(any())).thenReturn(List.of(task(), task(), task()));
        when(scopeResolver.projectNames(any())).thenReturn(Map.of(56L, "某项目"));

        PipelineRunPageVO page = service.list(34L, "running", 2, 2);

        assertEquals(3, page.getTotal(), "total 是过滤后的全量条数，不是本页条数");
        assertEquals(2, page.getPage());
        assertEquals(2, page.getSize());
        assertEquals(1, page.getRecords().size());
    }

    @Test
    void 列表状态非法时400() {
        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.list(34L, "PARSING", 1, 20));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("合法值"), e.getMessage());
    }

    // ── 任务级 vs 阶段级：status 多值与"拿错枚举"的提示（真机 400 的回归） ──────

    @Test
    void 单值状态照旧可用() {
        when(taskMapper.selectList(any())).thenReturn(List.of(task()));
        when(scopeResolver.projectNames(any())).thenReturn(Map.of(56L, "某项目"));

        PipelineRunPageVO page = service.list(34L, "RUNNING", 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals(List.of("RUNNING"), AiPipelineQueryService.parseStatuses("RUNNING"));
    }

    @Test
    void 多值状态逗号分隔且去重保序() {
        when(taskMapper.selectList(any())).thenReturn(List.of(task(), task()));
        when(scopeResolver.projectNames(any())).thenReturn(Map.of(56L, "某项目"));

        PipelineRunPageVO page = service.list(34L, "queued, RUNNING,running", 1, 50);

        assertEquals(2, page.getTotal(), "多值过滤不应报错（前端「进行中」= QUEUED,RUNNING）");
        assertEquals(List.of("QUEUED", "RUNNING"), AiPipelineQueryService.parseStatuses("queued, RUNNING,running"),
                "大小写归一 + 去重 + 保持请求顺序");
    }

    @Test
    void 多值状态真的进where条件() {
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(scopeResolver.projectNames(any())).thenReturn(Map.of());

        service.list(34L, "QUEUED,RUNNING", 1, 50);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AttachmentAiTask>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "where 里必须有 status：" + sql);
        assertTrue(sql.toUpperCase().contains("IN"), "两个值必须走 IN（两次 eq 是错的）：" + sql);
    }

    @Test
    void 阶段级PENDING被拒且报错点明这是阶段级状态() {
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.list(34L, "PENDING", 1, 50));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("阶段级"), e.getMessage());
        assertTrue(e.getMessage().contains("QUEUED,RUNNING"), "报错要直接给出任务级的「进行中」写法：" + e.getMessage());

        // 混在合法值里也一样要拒绝（不能静默忽略其中一项）
        AiPipelineException mixed = assertThrows(AiPipelineException.class,
                () -> service.list(34L, "QUEUED,PENDING", 1, 50));
        assertEquals(400, mixed.getStatus());
        assertTrue(mixed.getMessage().contains("阶段级"), mixed.getMessage());
    }

    @Test
    void 阶段级SKIPPED与空token也被拒() {
        assertTrue(assertThrows(AiPipelineException.class, () -> service.list(34L, "SKIPPED", 1, 50))
                .getMessage().contains("阶段级"));
        AiPipelineException empty = assertThrows(AiPipelineException.class,
                () -> service.list(34L, " , ", 1, 50));
        assertEquals(400, empty.getStatus());
        assertEquals(List.of(), AiPipelineQueryService.parseStatuses(null));
        assertEquals(List.of(), AiPipelineQueryService.parseStatuses("   "));
    }

    @Test
    void 产物内容被截断时给出取全文的指路() {
        when(taskMapper.selectById(12L)).thenReturn(task());
        AiPipelineArtifact artifact = new AiPipelineArtifact();
        artifact.setId(5L);
        artifact.setRunId("run-12");
        artifact.setTaskId(12L);
        artifact.setAttachmentId(34L);
        artifact.setStageKey("CHUNK");
        artifact.setArtifactType("chunk_manifest");
        artifact.setArtifactRev(2);
        artifact.setContentJson("x".repeat(4000));
        artifact.setCharCount(99_000L);
        artifact.setObjectKey("chunks/docX.json");
        artifact.setItemCount(30);
        artifact.setCreateTime(LocalDateTime.now());
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact));

        ArtifactContentVO vo = service.artifactContent(12L, "chunk_manifest", null);

        assertEquals("chunk_manifest", vo.getArtifactType());
        assertEquals(2, vo.getArtifactRev());
        assertEquals(Boolean.TRUE, vo.getTruncated());
        assertEquals(4000, vo.getContentChars());
        assertEquals(99_000L, vo.getCharCount());
        assertNotNull(vo.getNote());
        assertTrue(vo.getNote().contains("object_key"), vo.getNote());
        assertTrue(vo.getNote().contains("截断"), vo.getNote());
    }

    @Test
    void 产物不存在时404而不是空对象() {
        when(taskMapper.selectById(12L)).thenReturn(task());
        when(artifactMapper.selectList(any())).thenReturn(List.of());

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.artifactContent(12L, "clean_diff", null));
        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("clean_diff"), e.getMessage());
    }

    @Test
    void 附件没有任何任务时404并说明不是正在解析() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.detailByAttachmentId(34L));
        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("从未被触发过解析"), e.getMessage());
    }
}
