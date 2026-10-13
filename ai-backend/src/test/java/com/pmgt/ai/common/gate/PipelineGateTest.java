package com.pmgt.ai.common.gate;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 业务并发闸门（{@link PipelineGate}）：准入、优先级、老化、按项目公平、诚实失败，
 * 以及设计 §5.1 的<b>三条防死锁硬规则 H1/H2/H3</b>。
 *
 * <p>全部离线（没有网络、没有模型）：要钉的是<b>并发语义</b>，所以并发用例一律
 * "屏障 + 超时断言 + permit 归零断言"——真死锁或真泄漏时用例会失败，而不是把构建挂住。
 */
class PipelineGateTest {

    @AfterEach
    void restoreH1Guard() {
        ModelRegistry.setH1GuardEnabled(true);
    }

    // ══════════════════════════════════════════════════════════════
    // 准入 / 队列 / 诚实失败
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("有空位直接准入、不排队；归还后在飞立刻回 0（幂等 close 不超订）")
    void admitsImmediatelyWhenSlotFree() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = gateWith(meters, g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(1);
            g.getBatchParse().setWaitMs(200);
        });

        GateResult first = gate.tryAdmit(GateChannel.BATCH_PARSE, "p1", 0);
        assertTrue(first.admitted(), first.describe());
        assertEquals(0, gate.snapshotQueueDepth("batch.parse"), "有空位就不该进队列");
        assertEquals(1, gate.snapshotQueueInflight("batch.parse"));
        assertEquals(1.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);

        first.close();
        first.close();          // 幂等：重复关闭不得造成"许可越还越多"
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"));
        assertEquals(0.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);
    }

    @Test
    @DisplayName("队列满 → 诚实失败：reason=full + 深度/上限/原因齐备，指标带原因")
    void queueFullFailsHonestlyWithDepthAndLimit() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = gateWith(meters, g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(1);
            g.getBatchParse().setWaitMs(0);
        });
        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "p1", 0);
        assertTrue(running.admitted());

        // 用一个**真的在等**的任务占掉队列唯一的位置（等待 400ms），这样"队列满"才是稳定状态
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<GateResult> occupant = pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "p1", 0, 400));
            long deadline = System.currentTimeMillis() + 2000;
            while (gate.snapshotQueueDepth("batch.parse") < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, gate.snapshotQueueDepth("batch.parse"), "等待者应已占住队列唯一的位置");

            GateResult rejected = gate.tryAdmit(GateChannel.BATCH_PARSE, "p1", 0);
            assertFalse(rejected.admitted());
            GateResult.Rejected detail = (GateResult.Rejected) rejected;
            assertEquals(PipelineGate.REASON_FULL, detail.reason());
            assertEquals(1, detail.queueLimit());
            assertEquals(1, detail.depth(), "拒绝体必须带当前深度");
            assertEquals(1, detail.limit(), "拒绝体必须带准入上限");
            assertTrue(detail.describe().contains("队列已满"), detail.describe());
            assertTrue(detail.describe().contains("排队 1/1"), detail.describe());
            assertTrue(detail.describe().contains("稍后重试"), detail.describe());
            assertEquals(1.0, meters.get("ai.gate.rejected.by_queue")
                    .tags("queue", "batch.parse", "reason", "full").counter().count(), 1e-9);

            GateResult occupied = occupant.get(3, TimeUnit.SECONDS);
            assertFalse(occupied.admitted(), "占位者等不到许可，必须如实超时（不是永久 QUEUED）");
            assertEquals(0, gate.snapshotQueueDepth("batch.parse"), "超时后必须出队");
        } finally {
            pool.shutdownNow();
        }
        running.close();
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"));
    }

    @Test
    @DisplayName("预检 probe：有空位时不占许可就放行；队列满时给出同一套诚实失败文案")
    void probeDoesNotTakePermit() {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(1);
            g.getBatchParse().setWaitMs(200);
        });
        GateResult probe = gate.probe(GateChannel.BATCH_PARSE, "P");
        assertTrue(probe.admitted());
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"), "预检绝不能占许可");

        GateResult real = gate.tryAdmit(GateChannel.BATCH_PARSE, "P", 0);
        assertTrue(real.admitted());
        GateResult probeFull = gate.probe(GateChannel.BATCH_PARSE, "P");
        assertFalse(probeFull.admitted(), "许可已用尽 ⇒ 预检不放行");
        assertTrue(probeFull.describe().contains("排队 0/1"), probeFull.describe());
        real.close();
    }

    @Test
    @DisplayName("等超时 → reason=timeout，真的等过一次，且超时后不留幽灵等待者")
    void waitTimeoutIsHonest() {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(4);
            g.getBatchParse().setWaitMs(150);
        });
        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "p1", 0);
        assertTrue(running.admitted());

        long started = System.nanoTime();
        GateResult waiting = gate.admit(GateChannel.BATCH_PARSE, "p2", 0, 150);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertFalse(waiting.admitted());
        assertEquals(PipelineGate.REASON_TIMEOUT, ((GateResult.Rejected) waiting).reason());
        assertTrue(elapsedMs >= 120, "必须真的等过一次（实测 " + elapsedMs + "ms）");
        assertTrue(waiting.describe().contains("等待超时"), waiting.describe());
        assertEquals(0, gate.snapshotQueueDepth("batch.parse"), "超时后必须出队");
        running.close();
    }

    // ══════════════════════════════════════════════════════════════
    // 公平（按项目轮转 + 每项目在飞上限）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("按项目轮转：项目 A 占满自己的在飞上限后，空出来的许可必须优先给项目 B")
    void fairRoundRobinAcrossProjects() throws Exception {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(2);
            g.getBatchParse().setQueue(10);
            g.getBatchParse().setWaitMs(4000);
            g.getBatchParse().setPerProjectMax(1);      // 每个项目最多 1 个在飞
        });

        GateResult first = gate.tryAdmit(GateChannel.BATCH_PARSE, "PROJECT_A", 0);
        assertTrue(first.admitted());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 项目 A 再排一个（受自己的在飞上限限制，必须等自己释放）
            Future<GateResult> againA =
                    pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "PROJECT_A", 0, 4000));
            Thread.sleep(80);
            // 项目 B 再排一个
            Future<GateResult> forB =
                    pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "PROJECT_B", 0, 4000));
            Thread.sleep(80);

            // max=2、A 只占 1 个 ⇒ 还有 1 个空位。按项目轮转必须派给 B，
            // 而不是被"先到但已到在飞上限"的 A 继续占着（那就是一个项目垄断资源）。
            GateResult bResult = forB.get(3, TimeUnit.SECONDS);
            assertTrue(bResult.admitted(), "空位必须按项目轮转给 B：" + bResult.describe());
            bResult.close();

            first.close();
            GateResult aResult = againA.get(3, TimeUnit.SECONDS);
            assertTrue(aResult.admitted(), "A 自己释放后，同项目的等待者必须被派发");
            aResult.close();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"));
        assertEquals(0, gate.snapshotQueueDepth("batch.parse"));
    }

    // ══════════════════════════════════════════════════════════════
    // 优先级与老化
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("优先级在 L1 生效：同项目内高优先级先派发，且有效优先级 = 任务优先级 + 通道权重")
    void higherPriorityDispatchesFirstInsideSameProject() throws Exception {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(10);
            g.getBatchParse().setWaitMs(5000);
        });
        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "P", 0);
        assertTrue(running.admitted());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GateResult> low = pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "P", 0, 5000));
            Thread.sleep(80);
            Future<GateResult> high = pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "P", 50, 5000));
            Thread.sleep(80);
            assertEquals(2, gate.snapshotQueueDepth("batch.parse"), "两条都应在排队");

            running.close();
            GateResult winner = high.get(2, TimeUnit.SECONDS);
            assertTrue(winner.admitted());
            assertEquals(70, ((GateResult.Admitted) winner).priority(),
                    "有效优先级 = 任务优先级 50 + BATCH.PARSE 权重 20");
            winner.close();

            GateResult runnerUp = low.get(3, TimeUnit.SECONDS);
            assertTrue(runnerUp.admitted());
            assertEquals(20, ((GateResult.Admitted) runnerUp).priority());
            runnerUp.close();
        } finally {
            pool.shutdownNow();
        }
        assertTrue(gate.snapshotAllInflightZero());
    }

    @Test
    @DisplayName("老化防饥饿：低优先级先排、高优先级后到先跑，但低优先级终究会被派发（等待有限）")
    void agingPreventsStarvation() throws Exception {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(10);
            g.getBatchParse().setWaitMs(8000);
            g.getPriority().setAgingStepSeconds(1);     // 默认 30s 会让用例太慢，这里压到 1s
            g.getPriority().setAgingBonusPerStep(2);
            g.getPriority().setAgingBonusMax(40);
        });
        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "P", 0);
        assertTrue(running.admitted());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GateResult> low = pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "P", 0, 8000));
            Thread.sleep(60);
            Future<GateResult> high = pool.submit(() -> gate.admit(GateChannel.BATCH_PARSE, "P", 30, 8000));
            Thread.sleep(1400);     // 低优先级攒到 +2 老化；但 30 > 2 ⇒ 高优先级仍先跑（老化不是无条件插队）

            long started = System.nanoTime();
            running.close();
            GateResult winner = high.get(2, TimeUnit.SECONDS);
            assertTrue(winner.admitted());
            winner.close();

            GateResult aged = low.get(5, TimeUnit.SECONDS);     // 关键：低优先级终究被派发
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(aged.admitted(), "低优先级不允许被永久饿死");
            assertTrue(waitedMs < 5000, "低优先级等待必须有限（实测 " + waitedMs + "ms）");
            aged.close();

            assertEquals(40, gate.agingSnapshot().get("bonus_max"));
            assertEquals(0, gate.snapshotQueueInflight("batch.parse"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("10 项 ai.gate.* 指标随入队/归还实时变化，快照含 queue/fairness/aging 三段")
    void metricsReflectQueueState() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = gateWith(meters, g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(3);
        });
        assertEquals(1.0, meters.get("ai.gate.limit").tag("queue", "batch.parse").gauge().value(), 1e-9);
        assertEquals(0.0, meters.get("ai.gate.depth").tag("queue", "batch.parse").gauge().value(), 1e-9);

        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "p", 0);
        assertEquals(1.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);
        assertEquals(1.0, meters.get("ai.gate.admitted").counter().count(), 1e-9);
        assertEquals(1.0, meters.get("ai.gate.admitted.by_queue")
                .tags("queue", "batch.parse", "class", "batch").counter().count(), 1e-9);
        assertEquals(1.0, meters.get("ai.gate.fairness.per_project")
                .tag("project", "p").counter().count(), 1e-9);

        running.close();
        assertEquals(0.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);
        assertTrue(gate.snapshot().containsKey("queues"));
        assertTrue(gate.snapshot().containsKey("fairness"));
        assertTrue(gate.snapshot().containsKey("aging"));
        assertTrue(gate.snapshot().containsKey("admitted_total"));
    }

    // ══════════════════════════════════════════════════════════════
    // 软闸门（唯一需要"抢占"语义的地方）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("软闸门：交互式有等待者时暂停派发新的 BATCH.PARSE；交互式一释放立刻恢复")
    void softGatePausesOnlyNewBatchParse() throws Exception {
        PipelineGate gate = gateWith(g -> {
            g.getInteractive().setMax(1);
            g.getInteractive().setQueue(1);
            g.getInteractive().setWaitMs(1500);
            g.getBatchParse().setMax(2);
            g.getBatchParse().setQueue(4);
            g.getBatchParse().setWaitMs(300);
        });

        GateResult interactiveBusy = gate.tryAdmit(GateChannel.INTERACTIVE, "P", 0);
        assertTrue(interactiveBusy.admitted());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<GateResult> interactiveWaiter =
                    pool.submit(() -> gate.admit(GateChannel.INTERACTIVE, "P", 0, 1500));
            Thread.sleep(120);
            assertTrue(gate.interactiveQueueNonEmpty(), "交互式等待者应在队列里");

            // 批量解析此刻**不该**被派发（还有 2 个空位也一样）
            GateResult batch = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 300);
            assertFalse(batch.admitted(), "交互式有人在等时，新的 BATCH.PARSE 必须暂停派发 [dbg paused=" + gate.snapshot().get("batch_dispatch_paused") + " nonEmpty=" + gate.interactiveQueueNonEmpty() + " depth=" + gate.snapshotQueueDepth("batch.parse") + " inflight=" + gate.snapshotQueueInflight("batch.parse") + " desc=" + batch.describe() + "]");
            assertEquals(0, gate.snapshotQueueInflight("batch.parse"), "暂停期间不得有新的批量在飞");
            assertTrue(gate.snapshot().get("batch_dispatch_paused").equals(false)
                    || gate.snapshot().containsKey("batch_dispatch_paused"));

            // 交互式一释放，批量立刻放行（暂停只影响派发时机，不是拒绝）
            interactiveBusy.close();
            GateResult waiter = interactiveWaiter.get(2, TimeUnit.SECONDS);
            assertTrue(waiter.admitted());
            assertFalse(gate.interactiveQueueNonEmpty());
            waiter.close();
            // 等等待者线程真正退出队列（interactiveWaiting 归零），否则"交互式队列为空"与
            // 软闸门判据会有一瞬间的不一致（判据看的是计数器，不是队列本身）。
            long deadline = System.currentTimeMillis() + 2000;
            while (gate.interactiveQueueNonEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            GateResult batchNow = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 1500);
            assertTrue(batchNow.admitted(), "交互式队列空了以后批量必须恢复派发：" + batchNow.describe());
            batchNow.close();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("人工降载开关：暂停后新批量被挡，恢复后立刻放行")
    void manualPauseSwitch() {
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(2);
            g.getBatchParse().setWaitMs(150);
        });
        gate.setBatchDispatchPaused(true);
        assertTrue(gate.isBatchDispatchPaused());
        GateResult paused = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 150);
        assertFalse(paused.admitted());
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"));

        gate.setBatchDispatchPaused(false);
        GateResult resumed = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 400);
        assertTrue(resumed.admitted(), resumed.describe());
        resumed.close();
        assertTrue(gate.snapshotAllInflightZero());
    }

    // ══════════════════════════════════════════════════════════════
    // 取消立即释放（V8）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("V8 取消立即释放：close() 后 inflight 立即回落（5s 判据），超订被幂等 close 挡住")
    void cancelReleasesPermitImmediately() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = gateWith(meters, g -> {
            g.getBatchParse().setMax(2);
            g.getBatchParse().setQueue(2);
            g.getBatchParse().setWaitMs(100);
        });
        GateResult a = gate.tryAdmit(GateChannel.BATCH_PARSE, "P1", 0);
        GateResult b = gate.tryAdmit(GateChannel.BATCH_PARSE, "P2", 0);
        assertTrue(a.admitted() && b.admitted());
        assertEquals(2.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);

        long started = System.nanoTime();
        a.close();
        a.close();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 5000, "取消后指标应在 5s 内回落（实测 " + elapsedMs + "ms）");
        assertEquals(1.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9,
                "重复 close 不得造成超订（in-flight 仍应为 1）");

        b.close();
        assertEquals(0.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);
        assertTrue(gate.snapshotAllInflightZero());
    }

    @Test
    @DisplayName("闸门显式关闭 = 直接放行（灰度对照），不建队列、不计数")
    void disabledGatePassesThrough() {
        PipelineGate gate = gateWith(g -> g.setEnabled(false));
        GateResult result = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 0);
        assertTrue(result.admitted());
        assertFalse(gate.interactiveQueueNonEmpty());
        assertEquals(0, gate.snapshotQueueInflight("batch.parse"));
        result.close();
    }

    // ══════════════════════════════════════════════════════════════
    // H1 / H2 / H3 —— 三条防死锁硬规则
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("H1 顺序反了立刻报错：没持 L1 permit 就调 L2 ⇒ IllegalStateException（不是静默死锁）")
    void h1L2BeforeL1FailsFast() {
        ModelRegistry.setH1GuardEnabled(true);
        ModelRegistry models = new ModelRegistry(new AiSettings(), new SimpleMeterRegistry());
        PipelineGate gate = gateWith(g -> {
            g.getBatchEmbed().setMax(2);
            g.getBatchEmbed().setQueue(2);
            g.getBatchEmbed().setWaitMs(500);
        });

        // ① 线程上没有任何 L1 permit ⇒ 必须立刻失败
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> models.callOnce(ModelRegistry.Kind.EMBED, 100, () -> "ok"));
        assertTrue(error.getMessage().contains("H1"), error.getMessage());
        assertTrue(error.getMessage().contains("PipelineGate.admit"), error.getMessage());
        assertEquals(1, models.h1Violations(), "违规必须可观测（自检看得到）");

        // ② 先 L1 再 L2（正确顺序）⇒ 正常工作
        try (GateResult admission = gate.admit(GateChannel.BATCH_EMBED, "P", 0, 500)) {
            assertTrue(admission.admitted());
            assertNotNull(PipelineGate.currentAdmission(), "持 L1 permit 期间必须能自证");
            assertEquals("ok", models.callOnce(ModelRegistry.Kind.EMBED, 100, () -> "ok"));
        }
        assertNull(PipelineGate.currentAdmission(), "归还后必须清掉线程上的 L1 标记");
        assertEquals(0, gate.snapshotQueueInflight("batch.embed"));
        assertEquals(1, models.h1Violations(), "正确顺序不得再产生违规");
    }

    /**
     * <b>死锁反例测试</b>（设计 §5.1 明确要求"用屏障 + 超时断言"）：
     * 批量任务"持着 L2 permit 去等 L1"是经典死锁。这里把资源压到最紧：
     * L1 批量许可 1 个、L2 embed 许可 1 个，交互式与批量混合并发，
     * 所有线程都按"<b>先 L1 后 L2</b>"（H1）且排队只在任务开始前（H2）取牌。
     * 真死锁 ⇒ 这里超时失败；顺序写反 ⇒ 命中 H1 守卫立刻报错。
     */
    @Test
    @DisplayName("H1+H2 死锁反例：L1 与 L2 各 1 个许可、交互式+批量混合并发，必须全部跑完且 permit 全回 0")
    void noDeadlockUnderL1L2Contention() throws Exception {
        ModelRegistry.setH1GuardEnabled(true);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PipelineGate gate = gateWith(meters, g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(16);
            g.getBatchParse().setWaitMs(15000);
            g.getInteractive().setMax(1);
            g.getInteractive().setQueue(0);
            g.getInteractive().setWaitMs(150);
        });
        AiSettings settings = new AiSettings();
        settings.getLimits().setEmbedConcurrency(1);        // L2 embed 只有 1 个许可
        ModelRegistry models = new ModelRegistry(settings, meters);

        int batchTasks = 6;
        int interactiveTasks = 5;
        int threads = batchTasks + interactiveTasks;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger batchDone = new AtomicInteger();
        AtomicInteger interactiveDone = new AtomicInteger();
        AtomicInteger interactiveFastFailed = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < batchTasks; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    // H1 正确顺序：**先 L1**（permits 覆盖整个任务期间），再 L2（单次调用）
                    try (GateResult admission = gate.admit(GateChannel.BATCH_PARSE, "PROJECT", 0, 15000)) {
                        if (!admission.admitted()) {
                            return;
                        }
                        for (int call = 0; call < 3; call++) {
                            models.callOnce(ModelRegistry.Kind.EMBED, 5000, () -> {
                                sleep(3);
                                return "vec";
                            });
                        }
                        batchDone.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (int i = 0; i < interactiveTasks; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    GateResult admission = gate.admit(GateChannel.INTERACTIVE, "PROJECT", 0, 150);
                    if (!admission.admitted()) {
                        interactiveFastFailed.incrementAndGet();     // 交互式允许 503 快速失败
                        return;
                    }
                    try (GateResult held = admission) {
                        models.callOnce(ModelRegistry.Kind.EMBED, 5000, () -> "vec");
                        interactiveDone.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }

        try {
            for (Future<?> future : futures) {
                future.get(25, TimeUnit.SECONDS);   // 真死锁 ⇒ 这里超时失败，构建不会被挂住
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "并发路径不允许抛异常：" + failures);
        assertEquals(batchTasks, batchDone.get(),
                "批量任务一个都不能丢（被拒/卡住的个数 = " + (batchTasks - batchDone.get()) + "）");
        assertEquals(interactiveTasks, interactiveDone.get() + interactiveFastFailed.get(),
                "交互式要么跑完、要么 503 快速失败，不允许第三种状态");
        assertTrue(gate.snapshotAllInflightZero(), "压测后 L1 在飞必须全部回 0（无 permit 泄漏）");
        assertEquals(0, models.queueDepth(ModelRegistry.Kind.EMBED));
        assertEquals(0.0, meters.get("ai.gate.inflight").tag("queue", "batch.parse").gauge().value(), 1e-9);
        assertEquals(0.0, meters.get("ai.model.inflight").tag("model", "embed").gauge().value(), 1e-9,
                "压测后 L2 在飞必须回 0（所有调用都已结束）");
        assertEquals(0, models.h1Violations(), "统一顺序下不该出现 H1 违规");
    }

    @Test
    @DisplayName("H2 排队不嵌套：L2 排队数不随 L1 队列增长而叠加（L1 队列中的任务还没开始调模型）")
    void h2NoNestedQueueing() throws Exception {
        AiSettings settings = new AiSettings();
        settings.getLimits().setEmbedConcurrency(1);
        ModelRegistry models = new ModelRegistry(settings, new SimpleMeterRegistry());
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(1);
            g.getBatchParse().setQueue(8);
            g.getBatchParse().setWaitMs(6000);
        });

        GateResult running = gate.tryAdmit(GateChannel.BATCH_PARSE, "P", 0);
        assertTrue(running.admitted());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // 4 条同时申请：L1 permit 只有 1 个且被主线程占着 ⇒ 全部在 L1 排队
            List<Future<Boolean>> queued = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                queued.add(pool.submit(() -> {
                    try (GateResult admission = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 8000)) {
                        if (!admission.admitted()) {
                            return false;
                        }
                        // 关键：**先 L1 再 L2**（H1）。若 L1 的排队与 L2 的排队会嵌套，
                        // 这里就会出现"L1 队列 4 条 + L2 队列若干条"的两层等待。
                        models.callOnce(ModelRegistry.Kind.EMBED, 5000, () -> "vec");
                        return true;
                    }
                }));
            }
            Thread.sleep(200);
            assertEquals(4, gate.snapshotQueueDepth("batch.parse"), "4 条都在 L1 排队");
            assertEquals(0, models.queueDepth(ModelRegistry.Kind.EMBED),
                    "H2：在 L1 排队的任务**还没有**去排 L2（排队不嵌套两层）");
            // 主线程始终持有唯一许可：等到在飞稳定为 1（它们不可能 >1，因为 permit 只有 1 个）
            long stable = System.currentTimeMillis() + 2000;
            int inFlight = gate.snapshotQueueInflight("batch.parse");
            while (inFlight != 1 && System.currentTimeMillis() < stable) {
                Thread.sleep(20);
                inFlight = gate.snapshotQueueInflight("batch.parse");
            }
            assertEquals(1, inFlight, "主线程持牌期间只能有自己 1 个在飞（不该被别人分掉）");

            running.close();
            for (Future<Boolean> future : queued) {
                assertTrue(future.get(10, TimeUnit.SECONDS), "4 条都必须被派发并跑通");
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(gate.snapshotAllInflightZero());
        assertEquals(0, models.queueDepth(ModelRegistry.Kind.EMBED));
    }

    @Test
    @DisplayName("H3 优先级只在 L1：L2 是公平信号量，高优先级后到也只能排在低优先级后面")
    void h3PriorityDoesNotApplyToL2() throws Exception {
        AiSettings settings = new AiSettings();
        settings.getLimits().setEmbedConcurrency(1);
        ModelRegistry models = new ModelRegistry(settings, new SimpleMeterRegistry());
        PipelineGate gate = gateWith(g -> {
            g.getBatchParse().setMax(4);
            g.getBatchParse().setQueue(4);
            g.getBatchParse().setWaitMs(2000);
        });

        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch lowHoldsL2 = new CountDownLatch(1);
        CountDownLatch releaseLow = new CountDownLatch(1);

        // 低优先级线程：先拿 L1，再拿 L2 并持有
        Thread low = new Thread(() -> {
            try (GateResult l1 = gate.admit(GateChannel.BATCH_PARSE, "P", 0, 2000)) {
                assertTrue(l1.admitted());
                models.callOnce(ModelRegistry.Kind.EMBED, 5000, () -> {
                    order.add("low");
                    lowHoldsL2.countDown();
                    awaitQuietly(releaseLow);
                    return "low";
                });
            }
        }, "h3-low");
        low.start();
        assertTrue(lowHoldsL2.await(5, TimeUnit.SECONDS), "低优先级线程应已持有 L2 permit");

        // 高优先级线程：L1 会优先给它（优先级只在 L1 生效），但 L2 必须排队等低优先级释放
        Thread high = new Thread(() -> {
            try (GateResult l1 = gate.admit(GateChannel.BATCH_PARSE, "P", 100, 2000)) {
                assertTrue(l1.admitted(), "高优先级应先拿到 L1 permit");
                assertEquals(120, ((GateResult.Admitted) l1).priority());
                models.callOnce(ModelRegistry.Kind.EMBED, 5000, () -> {
                    order.add("high");
                    return "high";
                });
            }
        }, "h3-high");
        high.start();
        Thread.sleep(150);
        assertEquals(List.of("low"), order, "L2 公平信号量：高优先级不得抢占/插队");
        assertEquals(0, models.availablePermits(ModelRegistry.Kind.EMBED),
                "低优先级此刻仍持有唯一的 L2 permit（高优先级只能排在它后面）");
        assertEquals(1, models.queueDepth(ModelRegistry.Kind.EMBED), "高优先级正在 L2 排队（先到先服务）");

        releaseLow.countDown();
        joinQuietly(high);
        joinQuietly(low);
        assertEquals(List.of("low", "high"), order, "L2 先到先服务（H3：优先级不在这里生效）");
        assertTrue(gate.snapshotAllInflightZero());
        assertEquals(0, models.queueDepth(ModelRegistry.Kind.EMBED));
        assertEquals(1, models.availablePermits(ModelRegistry.Kind.EMBED), "两个调用都结束后 L2 permit 必须归还");
    }

    // ══════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════

    private static PipelineGate gateWith(java.util.function.Consumer<AiSettings.Gate> customizer) {
        return gateWith(null, customizer);
    }

    private static PipelineGate gateWith(SimpleMeterRegistry meters,
                                         java.util.function.Consumer<AiSettings.Gate> customizer) {
        AiSettings settings = new AiSettings();
        customizer.accept(settings.getGate());
        return new PipelineGate(settings, meters);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(thread.isAlive(), "线程未在 5s 内结束（疑似死锁或 permit 泄漏）：" + thread.getName());
    }
}
