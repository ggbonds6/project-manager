package com.pmgt.module.ai.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.dto.ArtifactAppendRequest;
import com.pmgt.module.ai.pipeline.dto.ArtifactVO;
import com.pmgt.module.ai.pipeline.dto.EventReportRequest;
import com.pmgt.module.ai.pipeline.dto.EventVO;
import com.pmgt.module.ai.pipeline.dto.FinishRequest;
import com.pmgt.module.ai.pipeline.dto.FinishVO;
import com.pmgt.module.ai.pipeline.dto.StageReportRequest;
import com.pmgt.module.ai.pipeline.dto.StageReportVO;
import com.pmgt.module.ai.pipeline.entity.AiPipelineArtifact;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.entity.AiPipelineStage;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineArtifactMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineStageMapper;
import com.pmgt.module.ai.service.AiAttachmentIndexWriter;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回写落库（契约 4~7）：阶段<b>幂等 upsert</b>、产物<b>只 INSERT + rev 自增</b>、
 * 事件只追加、终态的状态机与并发保护。
 *
 * <p>三条"写错很难发现"的语义在这里被钉死：
 * ① 同 {@code (run_id, stage_key)} 两次上报 ⇒ <b>1 行 + attempts=2</b>（不是 2 行）；
 * ② 产物两次追加 ⇒ <b>2 行、rev 分别 1/2</b>，且<b>从不 update</b>；
 * ③ 终态非法迁移 400（带允许目标）、并发冲突 409、同终态重复上报幂等。
 */
class AiPipelineReportServiceTest {

    private AttachmentAiTaskMapper taskMapper;
    private AiPipelineStageMapper stageMapper;
    private AiPipelineArtifactMapper artifactMapper;
    private AiPipelineEventMapper eventMapper;
    private AttachmentMapper attachmentMapper;
    private AiPipelineReportService service;

    @BeforeEach
    void setUp() {
        TestTableInfoInitializer.init();
        taskMapper = mock(AttachmentAiTaskMapper.class);
        stageMapper = mock(AiPipelineStageMapper.class);
        artifactMapper = mock(AiPipelineArtifactMapper.class);
        eventMapper = mock(AiPipelineEventMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        AiPipelineProperties props = new AiPipelineProperties();
        service = new AiPipelineReportService(
                new AiPipelineRunLookup(taskMapper),
                stageMapper,
                artifactMapper,
                taskMapper,
                new AiPipelineEventWriter(eventMapper),
                props,
                new AiAttachmentIndexWriter(attachmentMapper),
                new ObjectMapper());
    }

    private static AttachmentAiTask runningTask() {
        AttachmentAiTask task = new AttachmentAiTask();
        task.setId(12L);
        task.setAttachmentId(34L);
        task.setProjectId(56L);
        task.setFilename("中标通知书.pdf");
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setAttempts(1);
        task.setContentHash("abc123");
        task.setPipelineRev("parse=1,clean=0,chunk=0");
        task.setCreateTime(LocalDateTime.now().minusMinutes(2));
        return task;
    }

    // ── ② 阶段上报幂等 ──────────────────────────────────────────────

    @Test
    void 同阶段两次上报只有一行且attempts为2() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        // 用"内存表"模拟数据库：selectOne → 已插入的行；insert 把它放进去
        AtomicReference<AiPipelineStage> table = new AtomicReference<>();
        when(stageMapper.selectOne(any())).thenAnswer(inv -> table.get());
        when(stageMapper.insert(any(AiPipelineStage.class))).thenAnswer(inv -> {
            AiPipelineStage row = inv.getArgument(0);
            row.setId(777L);
            table.set(row);
            return 1;
        });
        when(stageMapper.updateById(any(AiPipelineStage.class))).thenAnswer(inv -> {
            table.set(inv.getArgument(0));
            return 1;
        });

        StageReportRequest first = new StageReportRequest();
        first.setStatus("RUNNING");
        first.setItemCount(20);
        StageReportVO firstVo = service.reportStage("run-12", "parse", first);

        StageReportRequest second = new StageReportRequest();
        second.setStatus("DONE");
        second.setItemCount(20);
        second.setElapsedMs(1234L);
        second.setOutputDigest("切片 30，其中表格 4，平均 412 字");
        StageReportVO secondVo = service.reportStage("run-12", "PARSE", second);

        verify(stageMapper, times(1)).insert(any(AiPipelineStage.class));
        verify(stageMapper, times(1)).updateById(any(AiPipelineStage.class));
        assertEquals(1, firstVo.getAttempts());
        assertEquals(2, secondVo.getAttempts(), "同键重复上报必须 attempts+1（幂等语义的可见证据）");
        assertEquals(777L, table.get().getId(), "两次上报是同一行");
        assertEquals("DONE", table.get().getStatus());
        assertEquals(1234L, table.get().getElapsedMs());
        assertEquals(2, table.get().getAttempts());
        assertEquals(1, table.get().getSeq(), "PARSE 的顺序是 1");
        assertEquals("识别", table.get().getStageName());
        assertEquals("PARSE", firstVo.getStageKey(), "阶段键会被归一化成大写");
        assertEquals("PARSE", secondVo.getStageKey());
    }

    @Test
    void 阶段键或状态非法时400并列出合法值() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        StageReportRequest bad = new StageReportRequest();
        bad.setStatus("RUNNING");

        AiPipelineException key = assertThrows(AiPipelineException.class,
                () -> service.reportStage("run-12", "PDF2PNG", bad));
        assertEquals(400, key.getStatus());
        assertTrue(key.getMessage().contains("PARSE/CLEAN/CHUNK/EMBED/INDEX/RETRIEVE/GENERATE"), key.getMessage());

        StageReportRequest badStatus = new StageReportRequest();
        badStatus.setStatus("OK");
        AiPipelineException status = assertThrows(AiPipelineException.class,
                () -> service.reportStage("run-12", "PARSE", badStatus));
        assertEquals(400, status.getStatus());
        assertTrue(status.getMessage().contains("PENDING/RUNNING/DONE/PARTIAL/SKIPPED/FAILED/CANCELLED"),
                status.getMessage());
    }

    @Test
    void 非法阶段迁移400并说明允许目标() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        AiPipelineStage pending = new AiPipelineStage();
        pending.setId(1L);
        pending.setRunId("run-12");
        pending.setStageKey("PARSE");
        pending.setStatus(AiPipelineStage.PENDING);
        when(stageMapper.selectOne(any())).thenReturn(pending);

        StageReportRequest request = new StageReportRequest();
        request.setStatus("DONE");
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.reportStage("run-12", "PARSE", request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("允许的目标状态"), e.getMessage());
        verify(stageMapper, never()).insert(any(AiPipelineStage.class));
    }

    @Test
    void run不存在时404() {
        when(taskMapper.selectById(999L)).thenReturn(null);
        StageReportRequest request = new StageReportRequest();
        request.setStatus("RUNNING");

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.reportStage("run-999", "PARSE", request));
        assertEquals(404, e.getStatus());
    }

    // ── ③ 产物只 INSERT + rev 自增 ──────────────────────────────────

    @Test
    void 产物两次追加产生两行且rev自增() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        List<AiPipelineArtifact> table = new ArrayList<>();
        when(artifactMapper.selectList(any())).thenAnswer(inv -> {
            // 服务层按 artifact_rev 倒序取第一行：这里退化成"整表返回最新在前"
            List<AiPipelineArtifact> copy = new ArrayList<>(table);
            copy.sort((a, b) -> Integer.compare(b.getArtifactRev(), a.getArtifactRev()));
            return copy;
        });
        when(artifactMapper.insert(any(AiPipelineArtifact.class))).thenAnswer(inv -> {
            AiPipelineArtifact row = inv.getArgument(0);
            row.setId((long) (table.size() + 1));
            table.add(row);
            return 1;
        });

        ArtifactAppendRequest first = new ArtifactAppendRequest();
        first.setArtifactType("chunk_manifest");
        first.setItemCount(30);
        first.setContent(new ObjectMapper().createObjectNode().put("chunk_count", 30));
        first.setCharCount(99000L);
        ArtifactVO firstVo = service.appendArtifact("run-12", first);

        ArtifactAppendRequest second = new ArtifactAppendRequest();
        second.setArtifactType("chunk_manifest");
        second.setItemCount(31);
        second.setSha1("deadbeef");
        ArtifactVO secondVo = service.appendArtifact("run-12", second);

        assertEquals(1, firstVo.getArtifactRev());
        assertEquals(2, secondVo.getArtifactRev(), "同一类型再追加必须是新版本，而不是覆盖");
        assertEquals(2, table.size(), "只 INSERT：两次追加两行");
        assertEquals("CHUNK", firstVo.getStageKey(), "chunk_manifest 由 CHUNK 阶段产出");
        assertEquals(99000L, firstVo.getCharCount(), "char_count 反映全文真实体量");
        // 产物表没有 update 路径（§4.1 P4）
        verify(artifactMapper, never()).updateById(any(AiPipelineArtifact.class));
        verify(artifactMapper, never()).update(any(), any());
    }

    @Test
    void 产物内容超长被截断且charCount保留真实长度() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(artifactMapper.insert(any(AiPipelineArtifact.class))).thenAnswer(inv -> 1);

        ArtifactAppendRequest request = new ArtifactAppendRequest();
        request.setArtifactType("page_digest");
        request.setContent(new ObjectMapper().getNodeFactory().textNode("x".repeat(5000)));

        service.appendArtifact("run-12", request);

        ArgumentCaptor<AiPipelineArtifact> saved = ArgumentCaptor.forClass(AiPipelineArtifact.class);
        verify(artifactMapper).insert(saved.capture());
        assertEquals(4000, saved.getValue().getContentJson().length(), "与 V15 的 content_json 列宽对齐");
        assertEquals(5000L, saved.getValue().getCharCount(), "真实长度不能被截断丢掉");
    }

    @Test
    void 带note的产物把备注并入contentJson() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(artifactMapper.insert(any(AiPipelineArtifact.class))).thenAnswer(inv -> 1);

        ArtifactAppendRequest request = new ArtifactAppendRequest();
        request.setArtifactType("embed_batch");
        request.setContent(new ObjectMapper().createObjectNode().put("embedded", 32));
        request.setNote("向量缓存命中 12 片");

        service.appendArtifact("run-12", request);

        ArgumentCaptor<AiPipelineArtifact> saved = ArgumentCaptor.forClass(AiPipelineArtifact.class);
        verify(artifactMapper).insert(saved.capture());
        assertTrue(saved.getValue().getContentJson().contains("\"_note\":\"向量缓存命中 12 片\""),
                saved.getValue().getContentJson());
        assertTrue(saved.getValue().getContentJson().contains("\"_content\""), saved.getValue().getContentJson());
    }

    @Test
    void 产物类型非法时400并列出五类() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        ArtifactAppendRequest request = new ArtifactAppendRequest();
        request.setArtifactType("raw_text");

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.appendArtifact("run-12", request));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("page_digest/clean_diff/chunk_manifest/embed_batch/index_result"),
                e.getMessage());
    }

    // ── 6) 事件审计 ─────────────────────────────────────────────────

    @Test
    void 事件审计只追加且不校验迁移合法性() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(eventMapper.insert(any(AiPipelineEvent.class))).thenAnswer(inv -> {
            AiPipelineEvent row = inv.getArgument(0);
            row.setId(9L);
            return 1;
        });

        EventReportRequest request = new EventReportRequest();
        request.setFromStatus("QUEUED");
        request.setToStatus("DONE");
        request.setReason("历史补记（异常形状也要看得见）");
        EventVO vo = service.reportEvent("run-12", request);

        assertEquals(9L, vo.getId());
        assertEquals("QUEUED", vo.getFromStatus());
        assertEquals("DONE", vo.getToStatus());
        assertNotNull(vo.getOperator(), "没有 operator 时记 SYSTEM，不留空");
        // 审计不改任务状态
        verify(taskMapper, never()).update(isNull(), any());
        verify(taskMapper, never()).updateById(any(AttachmentAiTask.class));
    }

    @Test
    void 事件状态值非法时400() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        EventReportRequest request = new EventReportRequest();
        request.setToStatus("PARSING");

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.reportEvent("run-12", request));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("合法值：QUEUED/RUNNING/DONE/PARTIAL/FAILED/CANCELLED/DEAD"),
                e.getMessage());
    }

    // ── 7) 终态 ─────────────────────────────────────────────────────

    @Test
    void 终态非法迁移400并说明允许的目标状态() {
        AttachmentAiTask done = runningTask();
        done.setStatus(AttachmentAiTask.DONE);
        when(taskMapper.selectById(12L)).thenReturn(done);

        FinishRequest request = new FinishRequest();
        request.setStatus("RUNNING");   // 终态接口不接受运行中状态
        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.finish("run-12", request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("finish 只接受终态"), e.getMessage());
    }

    @Test
    void DONE之后不允许改成FAILED() {
        AttachmentAiTask done = runningTask();
        done.setStatus(AttachmentAiTask.DONE);
        when(taskMapper.selectById(12L)).thenReturn(done);

        FinishRequest request = new FinishRequest();
        request.setStatus("FAILED");
        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.finish("run-12", request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("非法状态迁移：DONE → FAILED"), e.getMessage());
        assertTrue(e.getMessage().contains("允许的目标状态：QUEUED"), e.getMessage());
        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    void 终态落库成功时回写附件可检索并记事件() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        FinishRequest request = new FinishRequest();
        request.setStatus("DONE");
        request.setDocId("doc-777");
        request.setPipelineRev("parse=1,clean=1,chunk=1,embed=ab12cd34");
        FinishVO vo = service.finish("run-12", request);

        assertEquals(AttachmentAiTask.DONE, vo.getStatus());
        assertEquals("doc-777", vo.getDocId());
        assertEquals("READY", vo.getAttachmentIndexStatus());
        assertNull(vo.getNote());
        assertNotNull(vo.getElapsedMs());

        ArgumentCaptor<Attachment> attachment = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).updateById(attachment.capture());
        assertEquals("READY", attachment.getValue().getAiIndexStatus());
        assertEquals("doc-777", attachment.getValue().getAiDocId());

        ArgumentCaptor<AiPipelineEvent> event = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("AI_DONE", event.getValue().getReason());
        assertEquals("RUNNING", event.getValue().getFromStatus());
    }

    @Test
    void 没有docId的DONE不假装可检索() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        FinishRequest request = new FinishRequest();
        request.setStatus("DONE");
        FinishVO vo = service.finish("run-12", request);

        assertEquals("PARSING", vo.getAttachmentIndexStatus(), "没有 doc_id 就不能说「已可检索」");
        assertNotNull(vo.getNote());
        assertTrue(vo.getNote().contains("doc_id"), vo.getNote());
        verify(attachmentMapper, never()).updateById(any(Attachment.class));
    }

    @Test
    void 状态被并发改写时409() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(0);   // 条件 UPDATE 没命中

        FinishRequest request = new FinishRequest();
        request.setStatus("DONE");
        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.finish("run-12", request));

        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("状态刚刚被其它写入者改掉"), e.getMessage());
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 同终态重复上报被幂等忽略() {
        AttachmentAiTask done = runningTask();
        done.setStatus(AttachmentAiTask.DONE);
        done.setDocId("doc-777");
        when(taskMapper.selectById(12L)).thenReturn(done);

        FinishRequest request = new FinishRequest();
        request.setStatus("DONE");
        FinishVO vo = service.finish("run-12", request);

        assertEquals(AttachmentAiTask.DONE, vo.getStatus());
        assertTrue(vo.getNote().contains("幂等"), vo.getNote());
        verify(taskMapper, never()).update(isNull(), any());
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    @Test
    void FAILED回写附件失败并清空docId() {
        when(taskMapper.selectById(12L)).thenReturn(runningTask());
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        FinishRequest request = new FinishRequest();
        request.setStatus("FAILED");
        request.setErrorMsg("未提取到任何文本");
        FinishVO vo = service.finish("run-12", request);

        assertEquals("FAILED", vo.getAttachmentIndexStatus());
        assertNull(vo.getDocId());
        ArgumentCaptor<AiPipelineEvent> event = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper).insert(event.capture());
        assertTrue(event.getValue().getReason().startsWith("AI_FAILED:"), event.getValue().getReason());
    }
}
