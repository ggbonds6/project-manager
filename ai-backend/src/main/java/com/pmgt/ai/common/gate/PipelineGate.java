package com.pmgt.ai.common.gate;

import com.pmgt.ai.common.config.AiSettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * L1 <b>业务并发闸门</b>（设计 §5）：AI 服务进程内的准入、排队、优先级、按项目公平与背压。
 *
 * <h2>它管什么、不管什么</h2>
 * <ul>
 *   <li><b>管</b>：谁可以开始干活（五条通道的准入上限）、谁先干（有效优先级 + 老化）、
 *       谁跟谁轮着干（按 {@code project_id} 轮转 + 每项目在飞上限）、满了怎么办（诚实失败）；</li>
 *   <li><b>不管</b>：一次模型调用的在飞上限（那是 L2 {@code ModelRegistry}，4/12/8/2 的公平信号量）、
 *       任务的持久化与领取（那是主系统 DB 的唯一事实源）。</li>
 * </ul>
 *
 * <h2>三条防死锁硬规则（改这个类之前先读，改错了就是死锁或饥饿）</h2>
 * <ol>
 *   <li><b>H1 顺序统一、L1 全程持有</b>：所有调用方都必须<b>先</b> {@link #admit} 拿 L1 permit、
 *       <b>再</b>在需要时去 L2 取模型 permit，且 L1 permit 覆盖整个任务期间
 *       （{@code try (GateResult r = gate.admit(...)) { … 任务全过程 … }}）。
 *       若允许"先拿 L2 再等 L1"，批量任务会持着 embed permit 去等 L1 槽位，而槽位被同样在等
 *       embed permit 的交互式占着 ⇒ 环形等待。本类把这个不变式做成<b>可检的</b>：
 *       准入期间 {@link #currentAdmission()} 非空，{@code ModelRegistry} 在取 L2 permit 前断言它
 *       确实已持有（{@code ModelRegistry.H1 守卫}）——违反时立刻抛错而不是静默死锁。</li>
 *   <li><b>H2 排队不嵌套</b>：L1 的排队只发生在<b>任务开始之前</b>（本类的队列），
 *       任务是"拿到 permit 才创建/才开始"；L2 的排队发生在<b>单次调用之前</b>。
 *       因此任何时刻一个任务最多只排在一层队列里——等待时长指标才有意义，
 *       503 语义（交互式快速失败）也不会被两层等待搅浑。
 *       ⇒ 所以本类<b>不提供"每阶段再取一次 permit"</b>的用法。</li>
 *   <li><b>H3 优先级只在 L1</b>：L2 永远是公平信号量（{@code new Semaphore(limit, true)}），
 *       因为 L2 的 permit 数是平台物理上限（4 个对话副本、重排单实例），在那里做优先级
 *       只会饿死低优先级而<b>不增加任何吞吐</b>。</li>
 * </ol>
 *
 * <h2>调度算法</h2>
 * <ol>
 *   <li>准入请求先算 {@code effective_priority = 任务优先级 + 通道权重 + 老化补偿}；</li>
 *   <li>有空闲 permit 且项目未到在飞上限 ⇒ <b>立即准入</b>（不排队）；</li>
 *   <li>否则入队（队列满 ⇒ {@code reason=full} 拒绝），等待期间只被"派发"或"超时"唤醒；</li>
 *   <li>每次归还／入队都触发一次<b>派发扫描</b>：从轮转游标开始，选第一个
 *       "还没被派发 + 项目未满 + 未被软闸门暂停"的条目，把 permit 直接记账给它
 *       ⇒ 一个项目灌 100 份不会把别的项目全堵在后面（同项目内仍按有效优先级）；</li>
 *   <li>软闸门：交互式队列非空或人工暂停时，**暂停派发新的 {@code BATCH.PARSE}**
 *       （只影响新任务，<b>绝不踢掉正在跑的批量任务</b>）；</li>
 *   <li>等待超时 ⇒ 出队并 {@code reason=timeout} 拒绝，文案里带深度/上限/原因/建议
 *       （<b>绝不出现"接受了但永远不跑"</b>）。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code Semaphore} + 线程池</b>：优先级、按项目轮转、每项目在飞上限、软闸门
 * 这四件事都不在它们的语义里；线程池还会把"排队"藏进它自己的队列（深度不可见、拒绝原因不可控），
 * 与"诚实失败"直接冲突。本类只用<b>每通道一把锁 + 一个条件变量</b>，队列与指标是同一份事实。
 */
@Slf4j
@Component
public class PipelineGate {

    /** 拒绝原因（与指标标签 {@code reason=full|timeout|paused} 同源）。 */
    public static final String REASON_FULL = "full";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_PAUSED = "paused";

    /** 每项目等待时长的采样上限（公平性 P95 的样本窗口，避免无界增长）。 */
    private static final int WAIT_SAMPLES = 200;

    /** 本线程当前持有的 L1 准入（H1 守卫读它；{@code ModelRegistry} 在取 L2 permit 前断言非空）。 */
    private static final ThreadLocal<Admission> HOLDER = new ThreadLocal<>();

    private final AiSettings settings;
    private final MeterRegistry registry;
    private final Map<GateChannel, Channel> channels = new EnumMap<>(GateChannel.class);
    private final Counter admitted;
    private final Counter rejected;
    private final Counter agingAwarded;
    private final Map<String, Timer> waitingTimers = new LinkedHashMap<>();
    private final Map<String, Counter> admittedByQueue = new ConcurrentHashMap<>();
    private final Map<String, Counter> rejectedByQueue = new ConcurrentHashMap<>();
    private final Map<String, Counter> perProject = new ConcurrentHashMap<>();

    /** 软闸门的人工开关（设计 §5.6：这是唯一建议支持的"热更新"之一）。 */
    private final AtomicBoolean batchDispatchPaused = new AtomicBoolean(false);

    /**
     * <b>交互式</b>通道当前的等待者计数（进程级，不是每通道一份）。
     *
     * <p>为什么必须是进程级：软闸门的判据是"交互式有人在等 ⇒ 暂停派发新的 {@code BATCH.PARSE}"，
     * 而判据是在 <b>bootstrap 通道的锁内</b>被读的（见 {@code Channel.wouldPause}）。
     * 如果这个计数器挂在通道实例上，{@code BATCH.PARSE} 读到的是<b>它自己</b>的计数（恒为 0），
     * 软闸门就永远不会生效——这是真实踩过的坑（由
     * {@code PipelineGateTest.softGatePausesOnlyNewBatchParse} 钉住）。
     * 放在外层还有一个好处：判据不必去取交互式通道的锁，避免通道间锁序问题。
     */
    private final AtomicInteger interactiveWaiting = new AtomicInteger();

    @org.springframework.beans.factory.annotation.Autowired
    public PipelineGate(AiSettings settings,
                        org.springframework.beans.factory.ObjectProvider<MeterRegistry> registries) {
        this(settings, registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    public PipelineGate(AiSettings settings, MeterRegistry registry) {
        this.settings = settings;
        this.registry = registry == null ? new SimpleMeterRegistry() : registry;
        this.admitted = Counter.builder("ai.gate.admitted")
                .description("L1 准入计数（与 ai.gate.rejected 配对，反映各通道的满载程度）")
                .register(this.registry);
        this.rejected = Counter.builder("ai.gate.rejected")
                .description("L1 拒绝计数（reason=full|timeout|paused；诚实失败：每次拒绝都带原因）")
                .register(this.registry);
        this.agingAwarded = Counter.builder("ai.gate.aging.bonus.awarded")
                .description("因老化补偿而被提权的准入次数（>0 说明确实有任务在等，防饥饿生效）")
                .register(this.registry);

        for (GateChannel channel : GateChannel.values()) {
            Channel state = new Channel(channel);
            channels.put(channel, state);
            String key = channel.key();
            Gauge.builder("ai.gate.depth", state, Channel::depth)
                    .tag("queue", key).description("队列深度（等 L1 permit 的任务数）").register(this.registry);
            Gauge.builder("ai.gate.inflight", state, Channel::inFlight)
                    .tag("queue", key).description("在途数（已持 L1 permit 的任务数）").register(this.registry);
            Gauge.builder("ai.gate.limit", state, Channel::limit)
                    .tag("queue", key).description("准入上限").register(this.registry);
            Gauge.builder("ai.gate.per_project.inflight.max", state, Channel::perProjectMax)
                    .tag("queue", key).description("每项目在飞上限（按项目公平调度）").register(this.registry);
            waitingTimers.put(key, Timer.builder("ai.gate.waiting_ms")
                    .tag("queue", key)
                    .description("等 L1 permit 的时长（P50/P95/P99；交互式 P99 必须 ≤ 1.5s）")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(this.registry));
        }
        // 老化补偿的当前值（当前等待者中的最大值；无等待者归 0）
        Gauge.builder("ai.gate.aging.bonus", this, PipelineGate::observedAgingBonus)
                .description("当前最大的老化补偿值（调参用：长时间 > 0 说明批量在排队）")
                .register(this.registry);
    }

    // ══════════════════════════════════════════════════════════════
    // 对外入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 申请准入并等待到"拿到 permit"或"超时/被拒"。返回对象必须在任务结束时 {@code close()}
     * （建议 try-with-resources）；未准入的结果 {@code close()} 是空操作，所以调用方可以无条件关闭。
     *
     * @param channel      通道
     * @param projectId    项目 id（公平调度维度；{@code null}/空 = 不参与公平限制的默认桶）
     * @param taskPriority 主系统写入的任务优先级（数值越大越优先；交互式传 0 即可——通道权重已含 100）
     * @param waitMs       最多等多久（{@code 0} = 只试一次，不等）
     */
    public GateResult admit(GateChannel channel, String projectId, int taskPriority, long waitMs) {
        AiSettings.Gate gate = gateSettings();
        String project = normalizeProject(projectId);
        if (!gate.isEnabled()) {
            // 显式关闭 L1：直接放行（只用于灰度对照/排障）。不建队列、不排队、不计数。
            return new GateResult.Admitted(channel, project, taskPriority, 0L, () -> { });
        }
        Channel state = channels.get(channel);
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
        Entry entry = new Entry(channel, project, taskPriority, deadline, started);

        // ── 第一段：持锁判定"立即准入 / 入队 / 队列满拒绝"（三者互斥，一次判定）──
        boolean granted = false;
        state.lock.lock();
        try {
            if (state.available.get() > 0 && state.projectAllowed(project) && !state.wouldPause(project)) {
                state.available.decrementAndGet();
                granted = true;
            } else if (state.queue.size() >= state.queueLimit) {
                int depth = state.queue.size();
                int inFlight = state.inFlight.get();
                long waited = elapsedMs(started);
                state.reject(REASON_FULL);
                return rejectInstant(state, channel, REASON_FULL, depth, inFlight, waited,
                        fullSuggestions(channel), project);
            } else {
                state.queue.add(entry);
                state.interactiveDepthAdd(channel);
                state.passPermits();
            }
        } finally {
            state.lock.unlock();
        }
        if (granted) {
            // 指标与统计在锁外记（避免把 Timer/Counter 的注册与统计拉进通道锁）
            return finishGrant(state, entry, started, 0);
        }

        // ── 第二段：等待（只在"被派发"或"超时"时醒来；这一层等待发生在任务开始之前 = H2）──
        GateResult timedOut = null;
        state.lock.lock();
        try {
            while (true) {
                if (entry.offered) {
                    state.queue.remove(entry);
                    state.interactiveDepthRemove(channel);
                    break;
                }
                long remaining = entry.deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    state.queue.remove(entry);
                    state.interactiveDepthRemove(channel);
                    int depth = state.queue.size();
                    int inFlight = state.inFlight.get();
                    long waited = elapsedMs(started);
                    // 被软闸门压住而超时 ⇒ 如实说 paused（"有人把批量暂停了"与"单纯没抢到"是两件事，
                    // 运维看 reason 就能分辨该不该人工干预）
                    String reason = entry.pausedAtLeastOnce ? REASON_PAUSED : REASON_TIMEOUT;
                    state.reject(reason);
                    timedOut = rejectInstant(state, channel, reason, depth, inFlight, waited,
                            timeoutSuggestions(channel), project);
                    break;
                }
                try {
                    state.availableArrived.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    state.queue.remove(entry);
                    state.interactiveDepthRemove(channel);
                    int depth = state.queue.size();
                    long waited = elapsedMs(started);
                    state.reject(REASON_TIMEOUT);
                    timedOut = rejectInstant(state, channel, REASON_TIMEOUT, depth, state.inFlight.get(), waited,
                            List.of("本线程在等待 L1 准入时被中断"), project);
                    break;
                }
            }
        } finally {
            state.lock.unlock();
        }
        if (timedOut != null) {
            // 边界情况：派发扫描在超时判定的同一瞬间预留了 permit。它已经从 available 扣掉了，
            // 若就这么走掉就是一个**永久泄漏的 permit**（V5 的"压测后 inflight 回 0"会失败），
            // 所以这里显式归还并立刻转派给下一个等待者。
            abandon(state, channel, entry);
            return timedOut;
        }
        return finishGrant(state, entry, started, entry.ageBonus(gate));
    }

    /** 拒绝的收口：指标 + 诚实失败文案（深度/上限/原因/建议一次说清）。 */
    private GateResult rejectInstant(Channel state, GateChannel channel, String reason, int depth, int inFlight,
                                     long waitedMs, List<String> suggestions, String project) {
        metricsRejected(state, reason);
        String message = GateResult.Messages.busy(channel, reason, depth, state.limit, state.queueLimit, waitedMs,
                inFlight, suggestions);
        log.warn("L1 拒绝：queue={} project={} reason={} depth={}/{} inflight={}/{} waited={}ms",
                channel.key(), project, reason, depth, state.queueLimit, inFlight, state.limit, waitedMs);
        return new GateResult.Rejected(channel, reason, message, depth, state.limit, state.queueLimit, waitedMs);
    }

    private void abandon(Channel state, GateChannel channel, Entry entry) {
        if (!entry.offered) {
            return;
        }
        state.lock.lock();
        try {
            entry.offered = false;
            state.available.incrementAndGet();
            state.passPermits();
        } finally {
            state.lock.unlock();
        }
        log.debug("L1 permit 回收：queue={} 的等待者在被派发后离开了队列，permit 已转派下一位", channel.key());
    }

    /** 准入的收口（<b>锁外</b>）：建凭证、记指标与项目公平性统计。 */
    private GateResult finishGrant(Channel state, Entry entry, long startedNanos, int agingBonus) {
        state.lock.lock();
        Admission admission;
        int effective;
        long waited;
        try {
            state.noteGrant(entry.projectId);
            admission = new Admission(state, entry.projectId);
            HOLDER.set(admission);
            waited = elapsedMs(startedNanos);
            entry.ageBonusAtOffer = agingBonus;
            effective = entry.effectivePriority(agingBonus);
            state.noteAdmitted(waited);
        } finally {
            state.lock.unlock();
        }
        statsRecord(state, entry.projectId, waited);
        metricsAdmitted(entry, waited);
        return new GateResult.Admitted(entry.channel, entry.projectId, effective, waited, admission);
    }

    private void statsRecord(Channel state, String projectId, long waitedMs) {
        ProjectStats stats = state.projects.get(projectId);
        if (stats != null) {
            stats.record(waitedMs);
        }
    }

    /** 交互式准入（{@code /chat}）：等待上限取配置，1.5s 内拿不到就是 503（**不排队到 180s**）。 */
    public GateResult admitInteractive(String projectId) {
        return admit(GateChannel.INTERACTIVE, projectId, 0, interactiveWaitMs());
    }

    /**
     * 只试一次（不等）：用于"立即返回、失败就诚实拒绝"的入口
     * （例如上传解析：HTTP 接口必须马上给结论，不能把 Tomcat 线程挂住）。
     */
    public GateResult tryAdmit(GateChannel channel, String projectId, int taskPriority) {
        return admit(channel, projectId, taskPriority, 0);
    }

    /**
     * <b>预检</b>：按当前状态判定"如果现在申请会不会立刻被派发"，<b>不占许可、不入队、不动任何计数</b>。
     *
     * <p>它的用途是"提交即给结论"的入口（上传解析）：HTTP 线程只做判断与诚实拒绝，
     * <b>真正的 permit 由任务线程在任务开始时获取</b>——这一点很重要：
     * L1 permit 必须由<b>持牌干活的那个线程</b>持有，否则 H1 守卫（{@code ModelRegistry}）在任务线程里
     * 就看不到 L1 permit，会误报违规；而"提交线程拿牌、任务线程干活"在架构上等于
     * "permit 与实际执行者分离"，取消/超时都更难说清谁该归还。
     *
     * <p>预检与真实准入之间必然有一个很小的窗口（预检通过后别人抢走了空位）——
     * 那时的表现是任务线程按 {@code waitMs} 排队等一会儿，而不是"接受了却永远不跑"。
     */
    public GateResult probe(GateChannel channel, String projectId) {
        AiSettings.Gate gate = gateSettings();
        if (!gate.isEnabled()) {
            return new GateResult.Admitted(channel, normalizeProject(projectId), 0, 0L, () -> { });
        }
        Channel state = channels.get(channel);
        String project = normalizeProject(projectId);
        state.lock.lock();
        try {
            boolean wouldAdmit = state.available.get() > 0 && state.projectAllowed(project)
                    && state.queue.isEmpty() && !state.wouldPause(project);
            if (wouldAdmit) {
                return new GateResult.Admitted(channel, project, 0, 0L, () -> { });
            }
            int depth = state.queue.size();
            int inFlight = state.inFlight.get();
            String reason = state.queue.size() >= state.queueLimit ? REASON_FULL
                    : (state.wouldPause(project) ? REASON_PAUSED : REASON_TIMEOUT);
            return new GateResult.Rejected(channel, reason,
                    GateResult.Messages.busy(channel, reason, depth, state.limit, state.queueLimit, 0, inFlight,
                            fullSuggestions(channel)),
                    depth, state.limit, state.queueLimit, 0);
        } finally {
            state.lock.unlock();
        }
    }

    /** 本线程当前持有的 L1 准入（没有则 {@code null}）——<b>H1 守卫</b>读它。 */
    public static Admission currentAdmission() {
        return HOLDER.get();
    }

    // ── 运行时热更新（设计 §5.6 白名单：只有优先级/老化/软闸门可以运行时改）──

    /** 人工降载：暂停派发新的 {@code BATCH.PARSE}（不影响已在跑的批量任务）。 */
    public void setBatchDispatchPaused(boolean paused) {
        batchDispatchPaused.set(paused);
        Channel state = channels.get(GateChannel.BATCH_PARSE);
        state.lock.lock();
        try {
            state.passPermits();
        } finally {
            state.lock.unlock();
        }
        log.info("L1 软闸门：批量解析派发{}（只影响新任务，不踢掉在跑的批量）", paused ? "已暂停" : "已恢复");
    }

    public boolean isBatchDispatchPaused() {
        return batchDispatchPaused.get();
    }

    /** 交互式队列是否非空（软闸门的自动判据，设计 §5.2）。 */
    public boolean interactiveQueueNonEmpty() {
        Channel state = channels.get(GateChannel.INTERACTIVE);
        state.lock.lock();
        try {
            return !state.queue.isEmpty();
        } finally {
            state.lock.unlock();
        }
    }
    /** 自检/测试用：交互式等待者计数（软闸门判据读的就是它）。 */
    public int interactiveWaitingCount() {
        return interactiveWaiting.get();
    }

    /** 某通道当前队列深度（测试与自检的直读口径；等价于 {@code ai.gate.depth}）。 */
    public int snapshotQueueDepth(String queueKey) {
        for (GateChannel channel : GateChannel.values()) {
            if (channel.key().equals(queueKey)) {
                return channels.get(channel).depth();
            }
        }
        return -1;
    }

    /** 某通道当前在飞数（等价于 {@code ai.gate.inflight}）。 */
    public int snapshotQueueInflight(String queueKey) {
        for (GateChannel channel : GateChannel.values()) {
            if (channel.key().equals(queueKey)) {
                return channels.get(channel).inFlight();
            }
        }
        return -1;
    }

    /** 所有通道在飞是否都已归零（V5 的判据：压测/取消后不允许有 permit 泄漏）。 */
    public boolean snapshotAllInflightZero() {
        for (GateChannel channel : GateChannel.values()) {
            if (channels.get(channel).inFlight() != 0) {
                return false;
            }
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    // 指标 / 自检快照
    // ══════════════════════════════════════════════════════════════

    /** 10 项 {@code ai.gate.*} 指标的"一眼看全"聚合（与 {@code /health?with_metrics=true} 同风格）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> queues = new ArrayList<>();
        for (GateChannel channel : GateChannel.values()) {
            Channel state = channels.get(channel);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("queue", channel.key());
            item.put("depth", state.depth());
            item.put("inflight", state.inFlight());
            item.put("limit", state.limit());
            item.put("queue_limit", state.queueLimit);
            item.put("per_project_inflight_max", state.perProjectMax());
            item.put("admitted", state.admittedCount());
            item.put("rejected", state.rejectedOf(REASON_FULL) + state.rejectedOf(REASON_TIMEOUT)
                    + state.rejectedOf(REASON_PAUSED));
            item.put("rejected_full", state.rejectedOf(REASON_FULL));
            item.put("rejected_timeout", state.rejectedOf(REASON_TIMEOUT));
            item.put("rejected_paused", state.rejectedOf(REASON_PAUSED));
            item.put("waiting_ms_p50", state.waitingPercentile(0.5));
            item.put("waiting_ms_p95", state.waitingPercentile(0.95));
            item.put("waiting_ms_p99", state.waitingPercentile(0.99));
            item.put("max_waiting_ms", state.waitingMaxMillis());
            item.put("weight", channel.weight(gateSettings().getPriority()));
            queues.add(item);
        }
        out.put("queues", queues);
        out.put("batch_dispatch_paused", batchDispatchPaused.get());
        out.put("pause_batch_when_interactive", gateSettings().isPauseBatchWhenInteractive());
        out.put("interactive_queue_non_empty", interactiveQueueNonEmpty());
        out.put("aging", agingSnapshot());
        out.put("fairness", fairnessSnapshot());
        out.put("admitted_total", (long) admitted.count());
        out.put("rejected_total", (long) rejected.count());
        out.put("aging_bonus_awarded", (long) agingAwarded.count());
        return out;
    }

    /** 每项目公平性视图（设计 §5.3 的可测判据，V3 靠它）。 */
    public Map<String, Object> fairnessSnapshot() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, ProjectStats> entry : sortedProjects().entrySet()) {
            ProjectStats stats = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("project", entry.getKey());
            item.put("admitted", stats.admitted.get());
            item.put("inflight", stats.inFlight.get());
            item.put("waiting_ms_p95", stats.waitingPercentile(0.95));
            item.put("waiting_ms_max", stats.maxWaitMillis());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("per_project", items);
        return out;
    }

    /** 老化补偿现状（当前等待者里的最大补偿值 + 权重快照）。 */
    public Map<String, Object> agingSnapshot() {
        AiSettings.Gate.Priority priority = gateSettings().getPriority();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step_seconds", priority.getAgingStepSeconds());
        out.put("bonus_per_step", priority.getAgingBonusPerStep());
        out.put("bonus_max", priority.getAgingBonusMax());
        out.put("current_max_bonus", observedAgingBonus());
        List<Map<String, Object>> weights = new ArrayList<>();
        for (GateChannel channel : GateChannel.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("queue", channel.key());
            item.put("weight", channel.weight(priority));
            weights.add(item);
        }
        out.put("weights", weights);
        return out;
    }

    /** 配置摘要（自检用；**只有数字与开关，没有任何凭据**）。 */
    public Map<String, Object> configSummary() {        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", gateSettings().isEnabled());
        out.put("per_project_inflight_max_default", gateSettings().getPerProjectInflightMax());
        out.put("pause_batch_when_interactive", gateSettings().isPauseBatchWhenInteractive());
        List<Map<String, Object>> queues = new ArrayList<>();
        for (GateChannel channel : GateChannel.values()) {
            Channel state = channels.get(channel);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("queue", channel.key());
            item.put("label", channel.label());
            item.put("batch", channel.batch());
            item.put("max", state.limit());
            item.put("queue_limit", state.queueLimit);
            item.put("wait_ms", state.waitMs);
            item.put("per_project_max", state.perProjectMax());
            queues.add(item);
        }
        out.put("queues", queues);
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // 内部：准入凭证 / 通道状态 / 派发扫描
    // ══════════════════════════════════════════════════════════════

    /** 当前持有的准入（L1 permit 的载体；H1 守卫据此判断"本线程是否已持 L1"）。 */
    public static final class Admission implements AutoCloseable {

        private final Channel channel;
        private final String projectId;
        private final AtomicBoolean released = new AtomicBoolean();

        Admission(Channel channel, String projectId) {
            this.channel = channel;
            this.projectId = projectId;
        }

        public GateChannel channel() {
            return channel.channel;
        }

        public String projectId() {
            return projectId;
        }

        public String queueKey() {
            return channel.channel.key();
        }

        /** 幂等：重复 {@code close()} 不重复归还（否则 permit 会越还越多 = 超订）。 */
        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            if (HOLDER.get() == this) {
                HOLDER.remove();
            }
            channel.releaseAdmission(this, projectId);
        }
    }

    /** 一个通道的全部状态（自己一把锁；通道之间互不影响）。 */
    private final class Channel {

        private final GateChannel channel;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition availableArrived = lock.newCondition();
        private final List<Entry> queue = new ArrayList<>();
        private final AtomicInteger available = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final Map<String, ProjectStats> projects = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> rejectedByReason = new ConcurrentHashMap<>();

        private final int limit;
        private final int queueLimit;
        private final long waitMs;
        private final int perProjectMax;
        private final ArrayDeque<Double> waitingSamples = new ArrayDeque<>();
        private long admittedCount;
        private long waitingMax;
        private int drainCursor;

        Channel(GateChannel channel) {
            this.channel = channel;
            AiSettings.Gate gate = gateSettings();
            this.limit = Math.max(1, limitOf(channel, gate));
            this.queueLimit = Math.max(0, queueLimitOf(channel, gate));
            this.waitMs = Math.max(0, waitMsOf(channel, gate));
            this.perProjectMax = Math.max(1, perProjectMaxOf(channel, gate));
            this.available.set(this.limit);
        }

        /**
         * 授予 permit 的记账（<b>必须在持锁时调用</b>）：在飞 +1、项目在飞 +1、等待时长入样本。
         * 凭证与指标的构造在 {@link PipelineGate#finishGrant} 里（锁外）完成。
         */
        void noteGrant(String project) {
            inFlight.incrementAndGet();
            projects.computeIfAbsent(project, key -> new ProjectStats()).inFlight.incrementAndGet();
        }

        /** 归还 permit（幂等由 {@link Admission} 保证）：在飞 -1、可用 +1、触发一次派发扫描。 */
        void releaseAdmission(Admission admission, String project) {
            lock.lock();
            try {
                inFlight.decrementAndGet();
                available.incrementAndGet();
                ProjectStats stats = projects.get(project);
                if (stats != null) {
                    stats.inFlight.updateAndGet(value -> Math.max(0, value - 1));
                }
                passPermits();
            } finally {
                lock.unlock();
            }
        }

        /** 给"被软闸门挡住"的批量条目记账（只计数，不拒绝——暂停是"稍后派发"）。 */
        void reject(String reason) {
            rejectedByReason.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
        }

        /**
         * 派发扫描（<b>必须在持锁时调用</b>）：把空闲 permit 派给"最该跑"的等待者。
         *
         * <p>选择规则（两级，顺序即优先级）：
         * <ol>
         *   <li><b>先按有效优先级取最大</b>（{@code 任务优先级 + 通道权重 + 老化补偿}，
         *       设计 §5.2）——这一条保证"高优先级先跑"，也是老化防饥饿生效的地方；</li>
         *   <li><b>同优先级再按 project_id 轮转</b>（{@code drainCursor}）——这一条保证
         *       一个项目灌 100 份不会把别的项目全堵在后面（设计 §5.3）。</li>
         * </ol>
         * 跳过"已被派发""项目在飞已满""被软闸门暂停"的条目；命中即
         * "记账（available--）+ 标记 offered + 唤醒等待者"。
         *
         * <p>若一个都没派出去：说明要么都在被派发中、要么项目都在飞满、要么批量被软闸门暂停；
         * 后者记一次 {@code reason=paused}（让"批量被交互式压住"在指标里可见）。
         *
         * <p>⚠️ 本方法在持锁状态下运行，因此它调用的判据<b>不得再取别的通道的锁</b>
         * （{@link #wouldPause} 读的是无锁的 {@code interactiveWaiting} 计数器）。
         */
        void passPermits() {
            int guard = 0;
            while (available.get() > 0) {
                if (++guard > 4096) {
                    return;
                }
                if (queue.isEmpty()) {
                    return;
                }
                List<Entry> snapshot = new ArrayList<>(queue);
                AiSettings.Gate gate = gateSettings();
                Entry best = null;
                int bestPriority = Integer.MIN_VALUE;
                int bestOffset = Integer.MAX_VALUE;
                boolean anyPaused = false;
                for (int i = 0; i < snapshot.size(); i++) {
                    Entry candidate = snapshot.get((drainCursor + i) % snapshot.size());
                    if (candidate.offered) {
                        continue;
                    }
                    // 已经超时的条目不要再派发：那个等待者下一刻就会走超时分支离开队列，
                    // 把 permit"预留"给它会让 permit 空转一圈（虽然会经 abandon 归还，但白绕一步）。
                    if (candidate.deadlineNanos - System.nanoTime() <= 0) {
                        continue;
                    }
                    if (paused(candidate)) {
                        candidate.pausedAtLeastOnce = true;
                        anyPaused = true;
                        continue;
                    }
                    if (!projectAllowed(candidate.projectId)) {
                        continue;
                    }
                    int priority = candidate.effectivePriority(candidate.ageBonus(gate));
                    if (priority > bestPriority || (priority == bestPriority && i < bestOffset)) {
                        best = candidate;
                        bestPriority = priority;
                        bestOffset = i;
                    }
                }
                if (best == null) {
                    if (anyPaused) {
                        reject(REASON_PAUSED);
                    }
                    return;
                }
                best.offered = true;
                // 游标推到"被选中者之后"，让下一次派发从别的项目开始（轮转公平）
                drainCursor = (drainCursor + bestOffset + 1) % Math.max(1, snapshot.size());
                available.decrementAndGet();
                availableArrived.signalAll();
            }
        }

        /** 软闸门判据：人工暂停，或（交互式有等待者且开关打开），且本条是 {@code BATCH.PARSE}。 */
        boolean paused(Entry entry) {
            return entry.channel == GateChannel.BATCH_PARSE && wouldPause(entry.projectId);
        }

        /** 软闸门判据（按通道 + 项目）：预检与派发扫描共用同一份判断。 */
        boolean wouldPause(String projectId) {
            if (channel != GateChannel.BATCH_PARSE) {
                return false;
            }
            if (batchDispatchPaused.get()) {
                return true;
            }
            return gateSettings().isPauseBatchWhenInteractive() && interactiveWaiting.get() > 0;
        }

        /** 交互式等待者计数（无锁）：软闸门判据必须在<b>不取别的通道锁</b>的前提下可用。 */
        void interactiveDepthAdd(GateChannel channelId) {
            if (channelId == GateChannel.INTERACTIVE) {
                interactiveWaiting.incrementAndGet();
            }
        }

        void interactiveDepthRemove(GateChannel channelId) {
            if (channelId == GateChannel.INTERACTIVE) {
                interactiveWaiting.updateAndGet(value -> Math.max(0, value - 1));
            }
        }

        boolean projectAllowed(String project) {
            ProjectStats stats = projects.get(project);
            return stats == null || stats.inFlight.get() < perProjectMax;
        }

        void noteAdmitted(long waitedMs) {
            admittedCount++;
            if (waitedMs >= 1) {
                waitingSamples.addLast((double) waitedMs);
                while (waitingSamples.size() > WAIT_SAMPLES) {
                    waitingSamples.removeFirst();
                }
            }
            if (waitedMs > waitingMax) {
                waitingMax = waitedMs;
            }
        }

        int limit() {
            return limit;
        }

        int perProjectMax() {
            return perProjectMax;
        }

        int depth() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }

        int inFlight() {
            return inFlight.get();
        }

        long admittedCount() {
            return admittedCount;
        }

        long rejectedOf(String reason) {
            AtomicLong counter = rejectedByReason.get(reason);
            return counter == null ? 0L : counter.get();
        }

        Double waitingPercentile(double percentile) {
            List<Double> values;
            lock.lock();
            try {
                values = new ArrayList<>(waitingSamples);
            } finally {
                lock.unlock();
            }
            return PipelineGate.percentile(values, percentile);
        }

        Double waitingMaxMillis() {
            lock.lock();
            try {
                return (double) waitingMax;
            } finally {
                lock.unlock();
            }
        }
    }

    /** 一个项目的公平性统计（准入数 / 在飞 / 等待时长分位）。 */
    private static final class ProjectStats {

        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicLong admitted = new AtomicLong();
        private final ArrayDeque<Double> waits = new ArrayDeque<>();
        private double maxWait;

        synchronized void record(long waitedMs) {
            admitted.incrementAndGet();
            if (waitedMs > maxWait) {
                maxWait = waitedMs;
            }
            waits.addLast((double) waitedMs);
            while (waits.size() > WAIT_SAMPLES) {
                waits.removeFirst();
            }
        }

        synchronized Double waitingPercentile(double percentile) {
            return PipelineGate.percentile(new ArrayList<>(waits), percentile);
        }

        synchronized Double maxWaitMillis() {
            return (double) maxWait;
        }
    }

    /** 队列里的一个等待条目（可变状态都在通道锁内读写）。 */
    private final class Entry {

        private final GateChannel channel;
        private final String projectId;
        private final int taskPriority;
        private final long deadlineNanos;
        private final long enqueuedNanos;
        /** 是否已被派发扫描"预留"（预留后 permit 已从 available 扣掉，等待者只需接受）。 */
        private boolean offered;
        /** 是否曾经被软闸门压住过（超时时用它把 reason 说成 paused，而不是笼统的 timeout）。 */
        private boolean pausedAtLeastOnce;
        private int ageBonusAtOffer;

        Entry(GateChannel channel, String projectId, int taskPriority, long deadlineNanos, long enqueuedNanos) {
            this.channel = channel;
            this.projectId = projectId;
            this.taskPriority = taskPriority;
            this.deadlineNanos = deadlineNanos;
            this.enqueuedNanos = enqueuedNanos;
        }

        /** 老化补偿：{@code floor(等待秒数 / step) × bonus}，上限 {@code bonus_max}（设计 §5.2）。 */
        int ageBonus(AiSettings.Gate gate) {
            AiSettings.Gate.Priority priority = gate.getPriority();
            int step = Math.max(1, priority.getAgingStepSeconds());
            long waitedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - enqueuedNanos);
            int steps = (int) (waitedSeconds / step);
            return Math.min(priority.getAgingBonusMax(), steps * Math.max(0, priority.getAgingBonusPerStep()));
        }

        /** 有效优先级 = 任务优先级 + 通道权重 + 老化补偿。 */
        int effectivePriority(int agingBonus) {
            return taskPriority + channel.weight(gateSettings().getPriority()) + agingBonus;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 指标记账 / 配置派生 / 小工具
    // ══════════════════════════════════════════════════════════════

    private void metricsAdmitted(Entry entry, long waitedMs) {
        String queue = entry.channel.key();
        admitted.increment();
        admittedByQueue.computeIfAbsent(queue, key -> Counter.builder("ai.gate.admitted.by_queue")
                .tag("queue", key)
                .tag("class", entry.channel.batch() ? "batch" : "interactive")
                .description("按通道的准入计数（class=interactive|batch）")
                .register(registry)).increment();
        waitingTimers.get(queue).record(Math.max(0, waitedMs), TimeUnit.MILLISECONDS);
        perProject.computeIfAbsent(entry.projectId, key -> Counter.builder("ai.gate.fairness.per_project")
                .tag("project", key)
                .description("每项目 L1 准入数（公平性验证：3 个项目各 40 份时不应出现一个项目垄断）")
                .register(registry)).increment();
        if (entry.ageBonusAtOffer > 0) {
            agingAwarded.increment();
        }
        Channel state = channels.get(entry.channel);
        ProjectStats stats = state.projects.get(entry.projectId);
        if (stats != null) {
            stats.record(waitedMs);
        }
    }

    private void metricsRejected(Channel state, String reason) {
        rejected.increment();
        state.reject(reason);
        rejectedByQueue.computeIfAbsent(state.channel.key() + "|" + reason,
                key -> Counter.builder("ai.gate.rejected.by_queue")
                        .tag("queue", state.channel.key())
                        .tag("reason", reason)
                        .description("按通道与原因的拒绝计数（full=队列满 / timeout=等超时 / paused=软闸门暂停）")
                        .register(registry)).increment();
    }

    /** 当前等待者中最大的老化补偿（Gauge 的读法；无等待者归 0）。 */
    private int observedAgingBonus() {
        AiSettings.Gate gate = gateSettings();
        int max = 0;
        for (GateChannel channel : GateChannel.values()) {
            Channel state = channels.get(channel);
            if (state == null) {
                continue;
            }
            state.lock.lock();
            try {
                for (Entry entry : state.queue) {
                    max = Math.max(max, entry.ageBonus(gate));
                }
            } finally {
                state.lock.unlock();
            }
        }
        return max;
    }

    private AiSettings.Gate gateSettings() {
        AiSettings.Gate gate = settings.getGate();
        return gate == null ? new AiSettings.Gate() : gate;
    }

    long interactiveWaitMs() {
        return waitMsOf(GateChannel.INTERACTIVE, gateSettings());
    }

    private static String normalizeProject(String projectId) {
        return projectId == null || projectId.isBlank() ? "-" : projectId.strip();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private Map<String, ProjectStats> sortedProjects() {
        Map<String, ProjectStats> out = new LinkedHashMap<>();
        for (GateChannel channel : GateChannel.values()) {
            Channel state = channels.get(channel);
            if (state == null) {
                continue;
            }
            List<String> keys = new ArrayList<>(state.projects.keySet());
            keys.sort(String::compareTo);
            for (String key : keys) {
                out.putIfAbsent(key, state.projects.get(key));
            }
        }
        return out;
    }

    static Double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        values.sort(Double::compareTo);
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private static List<String> fullSuggestions(GateChannel channel) {
        return switch (channel) {
            case INTERACTIVE -> List.of("当前问答繁忙，请稍后重试（不排队等待，避免拖到前端 180s 超时）");
            case BATCH_PARSE -> List.of("解析队列已满，请稍后重试或等待在途解析完成");
            case BATCH_REINDEX -> List.of("已有重建任务在跑，请等它结束或先取消");
            default -> List.of("请稍后重试");
        };
    }

    private static List<String> timeoutSuggestions(GateChannel channel) {
        return switch (channel) {
            case INTERACTIVE -> List.of("当前问答繁忙，请稍后重试");
            case BATCH_PARSE, BATCH_EMBED -> List.of("批量通道繁忙，任务的临时文件已清理，不会留下半成品");
            default -> List.of("请稍后重试");
        };
    }

    // ── 配置派生（集中在这里，避免"哪个字段读哪个通道"散落各处）──────────

    private static int limitOf(GateChannel channel, AiSettings.Gate gate) {
        return switch (channel) {
            case INTERACTIVE -> gate.getInteractive().getMax();
            case BATCH_PARSE -> gate.getBatchParse().getMax();
            case BATCH_REINDEX -> gate.getBatchReindex().getMax();
            case BATCH_EMBED -> gate.getBatchEmbed().getMax();
            case BATCH_EVAL -> gate.getBatchEval().getMax();
        };
    }

    private static int queueLimitOf(GateChannel channel, AiSettings.Gate gate) {
        return switch (channel) {
            case INTERACTIVE -> gate.getInteractive().getQueue();
            case BATCH_PARSE -> gate.getBatchParse().getQueue();
            case BATCH_REINDEX -> gate.getBatchReindex().getQueue();
            case BATCH_EMBED -> gate.getBatchEmbed().getQueue();
            case BATCH_EVAL -> gate.getBatchEval().getQueue();
        };
    }

    private static long waitMsOf(GateChannel channel, AiSettings.Gate gate) {
        return switch (channel) {
            case INTERACTIVE -> gate.getInteractive().getWaitMs();
            case BATCH_PARSE -> gate.getBatchParse().getWaitMs();
            case BATCH_REINDEX -> gate.getBatchReindex().getWaitMs();
            case BATCH_EMBED -> gate.getBatchEmbed().getWaitMs();
            case BATCH_EVAL -> gate.getBatchEval().getWaitMs();
        };
    }

    private static int perProjectMaxOf(GateChannel channel, AiSettings.Gate gate) {
        int configured = switch (channel) {
            case INTERACTIVE -> gate.getPerProjectInflightMax();
            case BATCH_PARSE -> gate.getBatchParse().getPerProjectMax();
            case BATCH_REINDEX -> gate.getBatchReindex().getPerProjectMax();
            case BATCH_EMBED -> gate.getBatchEmbed().getPerProjectMax();
            case BATCH_EVAL -> gate.getBatchEval().getPerProjectMax();
        };
        return configured > 0 ? configured : Math.max(1, gate.getPerProjectInflightMax());
    }
}
