package com.pmgt.module.ai.pipeline.service;

import com.pmgt.module.ai.TestTableInfoInitializer;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.dto.ClaimRequest;
import com.pmgt.module.ai.pipeline.dto.ClaimVO;
import com.pmgt.module.ai.pipeline.dto.HeartbeatRequest;
import com.pmgt.module.ai.pipeline.dto.HeartbeatVO;
import com.pmgt.module.ai.pipeline.dto.ReleaseRequest;
import com.pmgt.module.ai.pipeline.dto.ReleaseVO;
import com.pmgt.module.ai.pipeline.entity.AiPipelineEvent;
import com.pmgt.module.ai.pipeline.mapper.AiPipelineEventMapper;
import com.pmgt.module.ai.service.AiAttachmentIndexWriter;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 抢占式领取（§5.4）：<b>"影响行数 = 1 才算领到"</b>的语义测试。
 *
 * <p>关键用例是 {@link #并发领取同一任务时只有一个调用者成功()}：用一个"按 CAS 决胜"的
 * 假 Mapper 模拟数据库的条件 UPDATE，8 个线程同时领同一行，断言<b>恰好一个</b>拿到，
 * 其余拿 404。这条测试是"双机 LB 下同一任务只被领取一次"的最小可复现证据
 * （真实数据库上的行锁行为无法在单测里复现，只能用受影响行数语义来钉住服务层）。
 */
class AiPipelineClaimServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz";

    private AttachmentAiTaskMapper taskMapper;
    private AiPipelineEventMapper eventMapper;
    private AiPipelineProperties props;
    private AiAttachmentIndexWriter indexWriter;
    private AiPipelineClaimService service;

    @BeforeEach
    void setUp() {
        TestTableInfoInitializer.init();
        taskMapper = mock(AttachmentAiTaskMapper.class);
        eventMapper = mock(AiPipelineEventMapper.class);
        props = new AiPipelineProperties();
        props.setServiceToken("s3cret-service-token");
        props.setLeaseSeconds(90);
        // 提交路径会写 attachment 索引状态，用真实现 + mock mapper，保证与生产同一份代码
        indexWriter = new AiAttachmentIndexWriter(mock(AttachmentMapper.class));
        service = newService(taskMapper, eventMapper, indexWriter);
    }

    private AiPipelineClaimService newService(AttachmentAiTaskMapper tasks,
                                              AiPipelineEventMapper events,
                                              AiAttachmentIndexWriter writer) {
        return new AiPipelineClaimService(tasks, new AiPipelineEventWriter(events), props,
                new AiPipelineTokenService(SECRET),
                new AiPipelineRunLookup(tasks), writer);
    }

    private static AttachmentAiTask queuedTask() {
        AttachmentAiTask task = new AttachmentAiTask();
        task.setId(12L);
        task.setAttachmentId(34L);
        task.setProjectId(56L);
        task.setFilename("中标通知书.pdf");
        task.setStatus(AttachmentAiTask.QUEUED);
        task.setAttempts(0);
        task.setTaskType(AttachmentAiTask.TYPE_PARSE);
        task.setPriority(10);
        task.setContentHash("abc123");
        task.setPipelineRev("parse=1,clean=0,chunk=0");
        task.setCleanRev(0);
        task.setCreateTime(LocalDateTime.now().minusSeconds(5));
        return task;
    }

    private ClaimRequest claimRequest() {
        ClaimRequest request = new ClaimRequest();
        request.setClaimedBy("ai-node-1/8899");
        return request;
    }

    /**
     * 桩：候选查询返回给定任务，"回收过期行"的查询返回空。
     *
     * <p>claim 内部会先查"租约过期的 RUNNING 行"（T7）、再查"可领取的 QUEUED 行"，
     * 两次都是 {@code selectList}。用"连续返回值"（第一次空、之后给定任务）区分它们：
     * 单线程下顺序是确定的。（注意不能靠 {@code wrapper.getParamNameValuePairs()} 区分——
     * MyBatis-Plus 3.5.7 在构造期那个 map 还是空的，参数要到生成 SQL 时才绑上去。）
     */
    private void stubCandidates(AttachmentAiTask task) {
        when(taskMapper.selectList(any())).thenReturn(List.of(), task == null ? List.of() : List.of(task));
    }

    // ── ④ 只被领一次 ────────────────────────────────────────────────

    @Test
    void claim成功时写状态事件与附件解析中() {
        AttachmentAiTask task = queuedTask();
        stubCandidates(task);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        ClaimVO vo = service.claim(claimRequest());

        assertEquals("run-12", vo.getRunId());
        assertEquals(12L, vo.getTaskId());
        assertEquals(34L, vo.getAttachmentId());
        assertEquals(AttachmentAiTask.RUNNING, vo.getStatus());
        assertEquals(1, vo.getAttempts(), "领取即 attempts+1（原值 0 ⇒ 1）");
        assertEquals("ai-node-1/8899", vo.getClaimedBy());
        assertNotNull(vo.getLeaseExpiresAt());
        assertNotNull(vo.getPipelineToken(), "claim 响应必须带运行级令牌");
        assertEquals("PARSE", vo.getStageKey());
        assertEquals("识别", vo.getStageName());
        assertEquals(30, vo.getHeartbeatSeconds(), "建议心跳 = 租约/3（90s ⇒ 30s）");
        // 幂等键 = sha1(content_hash + pipeline_rev)，与工具方法逐字一致
        assertEquals(AiPipelineKeys.taskKey("abc123", "parse=1,clean=0,chunk=0"), vo.getTaskKey());

        ArgumentCaptor<AiPipelineEvent> event = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("QUEUED", event.getValue().getFromStatus());
        assertEquals("RUNNING", event.getValue().getToStatus());
        assertTrue(event.getValue().getReason().startsWith("CLAIM:"), event.getValue().getReason());
    }

    @Test
    void 并发领取同一任务时只有一个调用者成功() throws Exception {
        // 假 Mapper：update 用 CAS 模拟"条件 UPDATE 的受影响行数"（1 = 抢到，0 = 被别人抢走）。
        // 这里直接调 claimNext（纯领取）：回收步骤有它自己的用例，混在一起会让
        // "8 个线程里到底几个在跑回收查询"变成测试噪音。
        AtomicReference<String> state = new AtomicReference<>(AttachmentAiTask.QUEUED);
        when(taskMapper.selectList(any())).thenAnswer(inv ->
                AttachmentAiTask.QUEUED.equals(state.get()) ? List.of(queuedTask()) : List.of());
        when(taskMapper.update(isNull(), any())).thenAnswer(inv ->
                state.compareAndSet(AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING) ? 1 : 0);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        ConcurrentLinkedQueue<String> winners = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final String worker = "ai-node-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ClaimRequest request = new ClaimRequest();
                    request.setClaimedBy(worker);
                    ClaimVO vo = service.claimNext(request);
                    won.incrementAndGet();
                    winners.add(vo.getClaimedBy());
                } catch (AiPipelineException e) {
                    assertEquals(404, e.getStatus(), "竞争失败必须是 404（没领到），而不是 500");
                    lost.incrementAndGet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发用例超时");

        assertEquals(1, won.get(), "同一行只能被领到一次（受影响行数=1 的语义）");
        assertEquals(threads - 1, lost.get());
        assertEquals(1, winners.size());
        verify(eventMapper, times(1)).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 队列为空时返回404而不是500() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.claim(claimRequest()));

        assertEquals(404, e.getStatus());
        assertTrue(e.getMessage().contains("当前没有可领取的任务"), e.getMessage());
        assertTrue(e.getMessage().contains("正常情况"), e.getMessage());
    }

    @Test
    void limit不为1时明确拒绝而不是静默夹到1() {
        ClaimRequest request = claimRequest();
        request.setLimit(5);

        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.claim(request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("limit 当前只支持 1"), e.getMessage());
    }

    @Test
    void task_type非法时拒绝并列出合法值() {
        ClaimRequest request = claimRequest();
        request.setTaskType("SOMETHING");

        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.claim(request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("合法值：PARSE/RETRY/REINDEX/EMBED/EVAL"), e.getMessage());
    }

    @Test
    void claimed_by为空时拒绝() {
        ClaimRequest request = new ClaimRequest();

        AiPipelineException e = assertThrows(AiPipelineException.class, () -> service.claim(request));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("claimed_by 不能为空"), e.getMessage());
    }

    // ── ⑤ 心跳 ─────────────────────────────────────────────────────

    @Test
    void 心跳在租约有效时续约成功() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        HeartbeatRequest request = new HeartbeatRequest();
        request.setClaimedBy("ai-node-1/8899");
        request.setLeaseSeconds(90);
        HeartbeatVO vo = service.heartbeat("run-12", request);

        assertEquals("run-12", vo.getRunId());
        assertEquals(AttachmentAiTask.RUNNING, vo.getStatus());
        assertEquals(90, vo.getLeaseSeconds());
        assertEquals(30, vo.getHeartbeatSeconds());
        assertNotNull(vo.getLeaseExpiresAt());
    }

    @Test
    void 租约已过期时心跳返回409并要求放回() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().minusSeconds(5)); // 已过期
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(0);      // 条件不成立 ⇒ 影响行数 0

        HeartbeatRequest request = new HeartbeatRequest();
        request.setClaimedBy("ai-node-1/8899");
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("run-12", request));

        assertEquals(409, e.getStatus(), "租约失效必须是 409：AI 应停止并放回，而不是继续写");
        assertTrue(e.getMessage().contains("租约已失效"), e.getMessage());
        assertTrue(e.getMessage().contains("/release"), e.getMessage());
    }

    @Test
    void 心跳不带claimed_by也能续约() {
        // AI 侧当前对心跳发的是空 body（只靠 X-AI-Service-Token 表明身份）：
        // 这条兼容分支让它不必改代码就有 409 语义，否则每次心跳都会 400
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(20));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        HeartbeatVO vo = service.heartbeat("run-12", new HeartbeatRequest());

        assertEquals(AttachmentAiTask.RUNNING, vo.getStatus());
        assertEquals(90, vo.getLeaseSeconds(), "不带 lease_seconds 时用配置默认值");
        assertEquals("ai-node-1/8899", vo.getClaimedBy(), "回显库里记的持有者");
    }

    @Test
    void 心跳不带claimed_by且租约过期时仍然409() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().minusSeconds(5));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("run-12", new HeartbeatRequest()));

        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("租约已失效"), e.getMessage());
    }

    @Test
    void 心跳不是本持有者时409而不是抢过来() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-9/1111");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        HeartbeatRequest request = new HeartbeatRequest();
        request.setClaimedBy("ai-node-1/8899");
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("run-12", request));

        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("当前由 ai-node-9/1111 持有"), e.getMessage());
    }

    @Test
    void 任务已终态时心跳409并说明无需放回() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.DONE);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        HeartbeatRequest request = new HeartbeatRequest();
        request.setClaimedBy("ai-node-1/8899");
        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("run-12", request));

        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("已不在 RUNNING"), e.getMessage());
    }

    @Test
    void run不存在时404而不是400() {
        when(taskMapper.selectById(999L)).thenReturn(null);

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("run-999", new HeartbeatRequest()));
        assertEquals(404, e.getStatus());

        AiPipelineException bad = assertThrows(AiPipelineException.class,
                () -> service.heartbeat("not-a-run-id", new HeartbeatRequest()));
        assertEquals(400, bad.getStatus(), "run_id 形状不对是 400（报文错），不是 404");
        assertTrue(bad.getMessage().contains("run-<taskId>"), bad.getMessage());
    }

    // ── 3) release ─────────────────────────────────────────────────

    @Test
    void 放回队列清空租约并记事件() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.RUNNING);
        task.setClaimedBy("ai-node-1/8899");
        task.setClaimExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        ReleaseRequest request = new ReleaseRequest();
        request.setClaimedBy("ai-node-1/8899");
        request.setReason("租约失效后主动放回");
        ReleaseVO vo = service.release("run-12", request);

        assertEquals(AttachmentAiTask.QUEUED, vo.getStatus());
        ArgumentCaptor<AiPipelineEvent> event = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("RUNNING", event.getValue().getFromStatus());
        assertTrue(event.getValue().getReason().contains("租约失效后主动放回"), event.getValue().getReason());
    }

    @Test
    void 放回已经是QUEUED的任务是幂等成功() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.QUEUED);
        when(taskMapper.selectById(12L)).thenReturn(task);

        ReleaseVO vo = service.release("run-12", new ReleaseRequest());

        assertEquals(AttachmentAiTask.QUEUED, vo.getStatus());
        verify(taskMapper, never()).update(isNull(), any());
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 已结束的任务不能放回队列() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.DONE);
        when(taskMapper.selectById(12L)).thenReturn(task);

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.release("run-12", new ReleaseRequest()));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("放回队列只适用于 RUNNING 的任务"), e.getMessage());
        assertTrue(e.getMessage().contains("重新解析/重建"), e.getMessage());
        verify(taskMapper, never()).update(isNull(), any());
    }

    @Test
    void 取消态任务放回队列给出终态文案() {
        AttachmentAiTask task = task();
        task.setStatus(AttachmentAiTask.CANCELLED);
        when(taskMapper.selectById(12L)).thenReturn(task);

        AiPipelineException e = assertThrows(AiPipelineException.class,
                () -> service.release("run-12", new ReleaseRequest()));

        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("终态"), e.getMessage());
    }

    // ── T7 租约过期回收 ─────────────────────────────────────────────

    @Test
    void 租约过期的RUNNING行被回收成QUEUED() {
        AttachmentAiTask stale = task();
        stale.setStatus(AttachmentAiTask.RUNNING);
        stale.setClaimedBy("ai-node-dead/1");
        stale.setClaimExpiresAt(LocalDateTime.now().minusSeconds(300));
        when(taskMapper.selectList(any())).thenReturn(List.of(stale));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        int reclaimed = service.reclaimStaleClaims();

        assertEquals(1, reclaimed);
        ArgumentCaptor<AiPipelineEvent> event = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper).insert(event.capture());
        assertTrue(event.getValue().getReason().startsWith("STALE_CLAIM"), event.getValue().getReason());
        assertEquals("ai-node-dead/1", event.getValue().getOperator());
    }

    @Test
    void 回收竞争失败时不记事件也不报错() {
        AttachmentAiTask stale = task();
        stale.setStatus(AttachmentAiTask.RUNNING);
        stale.setClaimExpiresAt(LocalDateTime.now().minusSeconds(300));
        when(taskMapper.selectList(any())).thenReturn(List.of(stale));
        when(taskMapper.update(isNull(), any())).thenReturn(0); // 刚好被真正的主人续约了

        assertEquals(0, service.reclaimStaleClaims());
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    // ── 推送模式登记 ────────────────────────────────────────────────

    @Test
    void 主系统推送后登记领取成功并写入租约() {
        AttachmentAiTask task = queuedTask();
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(service.registerPushedClaim(task));
        assertNotNull(task.getClaimedBy());
        assertNotNull(task.getClaimExpiresAt());
        assertEquals(AttachmentAiTask.QUEUED, task.getStatus(),
                "内存对象的状态刻意不动：DB 行被条件 UPDATE 改成 RUNNING，"
                        + "而这个对象是「提交响应」的快照（既有行为不变，控制器只用它的 id/docId）");
        verify(eventMapper).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 已被别的持有者领走时登记失败且不再写() {
        AttachmentAiTask task = queuedTask();
        when(taskMapper.update(isNull(), any())).thenReturn(0);

        assertFalse(service.registerPushedClaim(task));
        verify(eventMapper, never()).insert(any(AiPipelineEvent.class));
    }

    @Test
    void 新任务按动作填分类与优先级() {
        AttachmentAiTask manual = new AttachmentAiTask();
        service.decorateNewTask(manual, "PARSE");
        assertEquals(AttachmentAiTask.TYPE_PARSE, manual.getTaskType());
        assertEquals(10, manual.getPriority(), "手动点击是交互式：优先级更高");

        AttachmentAiTask auto = new AttachmentAiTask();
        service.decorateNewTask(auto, "AUTO_PARSE");
        assertEquals(AttachmentAiTask.TYPE_PARSE, auto.getTaskType());
        assertEquals(0, auto.getPriority(), "上传后自动触发是批量：优先级 0");

        AttachmentAiTask retry = new AttachmentAiTask();
        service.decorateNewTask(retry, "RETRY");
        assertEquals(AttachmentAiTask.TYPE_RETRY, retry.getTaskType());
        assertEquals(10, retry.getPriority());
        assertEquals("parse=1,clean=0,chunk=0", retry.getPipelineRev());
        assertEquals(0, retry.getCleanRev());
    }

    @Test
    void 提交事件与领取登记都写审计() {
        AttachmentAiTask task = queuedTask();
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        service.recordSubmit(task, "PARSE");
        service.registerPushedClaim(task);

        ArgumentCaptor<AiPipelineEvent> events = ArgumentCaptor.forClass(AiPipelineEvent.class);
        verify(eventMapper, times(2)).insert(events.capture());
        assertEquals("SUBMIT:PARSE", events.getAllValues().get(0).getReason());
        assertNotNull(events.getAllValues().get(1).getReason());
    }

    private static AttachmentAiTask task() {
        return queuedTask();
    }
}
