package com.pmgt.module.ai.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.ai.pipeline.AiPipelineException;
import com.pmgt.module.ai.pipeline.AiPipelineKeys;
import com.pmgt.module.ai.pipeline.AiPipelineProperties;
import com.pmgt.module.ai.pipeline.AiPipelineStages;
import com.pmgt.module.ai.pipeline.dto.ClaimRequest;
import com.pmgt.module.ai.pipeline.dto.ClaimVO;
import com.pmgt.module.ai.pipeline.dto.HeartbeatRequest;
import com.pmgt.module.ai.pipeline.dto.HeartbeatVO;
import com.pmgt.module.ai.pipeline.dto.ReleaseRequest;
import com.pmgt.module.ai.pipeline.dto.ReleaseVO;
import com.pmgt.module.ai.service.AiAttachmentIndexWriter;
import com.pmgt.module.ai.service.AiScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * <b>抢占式领取</b>（契约 1~3 与 §5.4：「影响行数 = 1 才算领到」）。
 *
 * <h2>为什么必须用"条件 UPDATE + 受影响行数"，而不是先查再改</h2>
 * <p>主系统是<b>双机 LB</b>（两个 pm-backend 实例共用同一个崖山库），AI 侧也会来领同一批任务。
 * "先 SELECT 出 QUEUED 的任务，再 UPDATE"在并发下是错的：两个节点会同时读到同一行、
 * 都认为自己领到了，于是同一份文档被解析两遍（写同一份 {@code work/}、抢同一个 doc_id）。
 * 唯一正确的形态是<b>把状态判断写进 UPDATE 的 WHERE</b>，用数据库的行锁与提交语义裁决：
 * <pre>
 *   UPDATE attachment_ai_task
 *      SET status='RUNNING', claimed_by=?, claim_expires_at=?, attempts=attempts+1
 *    WHERE id=? AND status='QUEUED' AND (claim_expires_at IS NULL OR claim_expires_at &lt; ?)
 * </pre>
 * 受影响行数 = 1 才是"我领到了"，0 就是"被别人先领走了"，换下一个候选继续试。
 *
 * <h2>方言纪律（§5.7）</h2>
 * <p>崖山 Oracle 模式下刻意<b>不用</b> {@code SELECT ... FOR UPDATE}：
 * 那会把行锁与长事务绑定到"解析整份文档"这段时间（分钟级），连接池立刻被打满。
 * 这里只用"短事务 + 条件 UPDATE"，与仓库既有写法（{@code AiTaskService} 的
 * {@code LambdaUpdateWrapper}）同风格，没有引入任何新方言。
 *
 * <h2>主系统 worker 与 AI 侧 claim 的分工（本批的明确选择）</h2>
 * <p>两条链路<b>共用同一把锁</b>（同一行、同一个 WHERE），因此天然互斥，不存在"都被领取"：
 * <ol>
 *   <li><b>推送模式（本批主系统侧实际在跑的）</b>：{@code AiTaskService.submit} 先把附件字节
 *       推给 AI 服务（{@code POST /upload-tasks}，这是唯一把文件交到 AI 手里的通道），
 *       然后调用本类的 {@link #registerPushedClaim} 把这一行<b>登记为已领取</b>
 *       （QUEUED→RUNNING + {@code claimed_by=本节点} + 租约）。此后 AI 侧来 claim 时
 *       这一行已不是 QUEUED，领不到——这就是"互斥"的落点；</li>
 *   <li><b>拉取模式（本批开放给 AI 侧）</b>：AI 服务调 {@link #claim} 主动领取。
 *       它拿到的是"主系统还没推给任何人的任务"，因此不会与推送模式撞车。</li>
 * </ol>
 * <b>为什么这轮不改主系统为纯拉取</b>：拉取模式要求 AI 侧能自己取到附件字节，
 * 而冻结契约里没有"按 attachment_id 取附件内容"的接口（只有 claim 拿到 id），
 * 硬改成拉取会留下一条"领到了但取不到文件"的断链。所以本批的分工是：
 * <b>文件交付仍走推送，任务归属权统一走 claim 的同一把锁</b>；
 * 真正的"AI 主动拉取 + 自助取文件"是 P1-β 的活（见设计文档 §9.3）。
 */
@Service
public class AiPipelineClaimService {

    private static final Logger log = LoggerFactory.getLogger(AiPipelineClaimService.class);

    /** 一次 claim 最多尝试的候选行数（每个候选一次条件 UPDATE，避免"全是竞争失败"时空转）。 */
    static final int MAX_CANDIDATES = 10;

    /** 一次 claim 调用最多回收多少个"租约过期却还挂在 RUNNING"的行（T7）。 */
    static final int MAX_RECLAIM_PER_CALL = 20;

    /**
     * 任务分类白名单（与 V15 的 task_type 注释一致）。
     *
     * <p>刻意分成"有序 List（报错文案用）+ Set（判存在用）"：用 {@code Set.of} 拼接文案时
     * 迭代顺序不保证，同一个错误每次给出不同的值序，调用方没法照着写分支。
     */
    private static final List<String> TASK_TYPE_LIST = List.of(
            AttachmentAiTask.TYPE_PARSE, AttachmentAiTask.TYPE_RETRY, AttachmentAiTask.TYPE_REINDEX,
            AttachmentAiTask.TYPE_EMBED, AttachmentAiTask.TYPE_EVAL);
    private static final Set<String> TASK_TYPES = Set.copyOf(TASK_TYPE_LIST);

    private final AttachmentAiTaskMapper taskMapper;
    private final AiPipelineEventWriter events;
    private final AiPipelineProperties props;
    private final AiPipelineTokenService tokens;
    private final AiPipelineRunLookup lookup;
    private final AiAttachmentIndexWriter indexWriter;

    /** 本节点标识（{@code 主机名/pid}），写进 claimed_by 与事件流，便于"谁领的"可查。 */
    private final String nodeId = localNodeId();

    public AiPipelineClaimService(AttachmentAiTaskMapper taskMapper,
                                  AiPipelineEventWriter events,
                                  AiPipelineProperties props,
                                  AiPipelineTokenService tokens,
                                  AiPipelineRunLookup lookup,
                                  AiAttachmentIndexWriter indexWriter) {
        this.taskMapper = taskMapper;
        this.events = events;
        this.props = props;
        this.tokens = tokens;
        this.lookup = lookup;
        this.indexWriter = indexWriter;
    }

    // ══════════════════════════════════════════════════════════════════
    // 1) claim：抢占式领取
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>HTTP 入口</b>：先把"租约过期却还挂在 RUNNING"的行放回队列（§4.4.1 T7），再领一个任务。
     *
     * <p>为什么回收放在领取里：领取是"队列有人消费"的信号，此时回收最及时，
     * 不需要额外的定时任务（少一个只跑在某个节点上的调度器）。
     * 两步拆成两个方法是为了各自可测：回收只做一件事，领取只做一件事。
     *
     * <p>⚠️ <b>不要给回收加定时任务/线程</b>（grep 可证：全仓 {@code reclaimStaleClaims}
     * 只有这里一个调用点，且没有任何 {@code @Scheduled}/{@code TaskScheduler}）。
     * 原因不是"省事"，而是语义：回收把 RUNNING 改回 QUEUED，随后任何一次 claim 都可能重新领到它。
     * 一旦有定时扫描，就会出现"提交后已 RUNNING、解析其实还在跑（只是还没到租约截止）⇒ 被定时器
     * 放回 QUEUED ⇒ 同一份文件被第二次领取/二次解析"——重复花钱、产物写两版（{@code artifact_rev}
     * 会涨、{@code attempts} 也涨），而且不会报错。若将来确实需要主动扫描，必须同时满足：
     * ① 用独立的开关且默认关闭；② 只扫"心跳也停了"的行（{@code claim_expires_at} 之外再判
     * 最后心跳时间）；③ 与 {@code claim} 走同一把条件 UPDATE。否则宁可没有它。
     *
     * @throws AiPipelineException 400 报文非法；404 当前没有可领取的任务
     */
    @Transactional
    public ClaimVO claim(ClaimRequest request) {
        reclaimStaleClaims();
        return claimNext(request);
    }

    /**
     * <b>纯领取</b>（不含回收）：候选查询 → 逐个条件 UPDATE → 受影响行数 = 1 才算领到。
     *
     * <p>包级可见：产品代码只走 {@link #claim}（HTTP 入口），单测可以直接测"领取本身"的
     * 并发语义而不必先构造"回收"的桩。
     *
     * @throws AiPipelineException 400 报文非法；404 当前没有可领取的任务
     */
    ClaimVO claimNext(ClaimRequest request) {
        String claimedBy = requireText(request == null ? null : request.getClaimedBy(), "claimed_by");
        Integer limit = request == null ? null : request.getLimit();
        if (limit != null && limit != 1) {
            throw AiPipelineException.badRequest("limit 当前只支持 1（当前 " + limit + "）："
                    + "claim 的响应契约是单个 {run_id, task_id, …}；要一次领多个请连续调用本接口"
                    + "（不做静默夹到 1，以免调用方以为拿到了多个）");
        }
        String taskType = normalizeTaskType(request == null ? null : request.getTaskType());
        int leaseSeconds = leaseSeconds(request == null ? null : request.getLeaseSeconds());

        LocalDateTime now = LocalDateTime.now();
        List<AttachmentAiTask> candidates = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .select(AttachmentAiTask::getId, AttachmentAiTask::getAttachmentId,
                        AttachmentAiTask::getProjectId, AttachmentAiTask::getFilename,
                        AttachmentAiTask::getStatus, AttachmentAiTask::getAttempts,
                        AttachmentAiTask::getClaimExpiresAt, AttachmentAiTask::getTaskType,
                        AttachmentAiTask::getPriority, AttachmentAiTask::getContentHash,
                        AttachmentAiTask::getPipelineRev, AttachmentAiTask::getCleanRev,
                        AttachmentAiTask::getDocId, AttachmentAiTask::getCreateTime)
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED)
                .and(w -> w.isNull(AttachmentAiTask::getClaimExpiresAt)
                        .or().lt(AttachmentAiTask::getClaimExpiresAt, now))
                .eq(taskType != null, AttachmentAiTask::getTaskType, taskType)
                .orderByDesc(AttachmentAiTask::getPriority)
                .orderByAsc(AttachmentAiTask::getId));

        int tried = 0;
        for (AttachmentAiTask candidate : candidates) {
            if (tried >= MAX_CANDIDATES) {
                break;
            }
            tried++;
            LocalDateTime expiresAt = now.plusSeconds(leaseSeconds);
            if (tryClaim(candidate.getId(), claimedBy, expiresAt, now, stageKeyFor(candidate))) {
                return claimed(candidate, claimedBy, expiresAt, leaseSeconds, now);
            }
            // 受影响行数 = 0：被别的节点/AI 实例先领走了，安静地试下一个
        }
        throw AiPipelineException.notFound("当前没有可领取的任务"
                + (taskType == null ? "" : "（task_type=" + taskType + "）")
                + "：队列为空或候选任务都被其它节点先领走了。这是正常情况（不是错误），"
                + "请按退避策略稍后再试，不要把它当成故障做重试风暴");
    }

    /** 领取成功的收尾：事件流 + 附件索引状态 + 响应组装。 */
    private ClaimVO claimed(AttachmentAiTask task, String claimedBy, LocalDateTime expiresAt,
                            int leaseSeconds, LocalDateTime now) {
        events.record(AiPipelineKeys.runIdOf(task.getId()), task.getId(), task.getProjectId(), null,
                AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING,
                "CLAIM:" + claimedBy, claimedBy);
        // 附件立刻变成"解析中"：拉取模式下主系统没有别的写入点，不写这一笔前端会一直显示"未解析"
        indexWriter.write(task.getAttachmentId(), AiScopeResolver.STATUS_PARSING, null, null);

        AiPipelineStages.Stage stage = AiPipelineStages.of(stageKeyFor(task));
        ClaimVO vo = new ClaimVO();
        vo.setRunId(AiPipelineKeys.runIdOf(task.getId()));
        vo.setTaskId(task.getId());
        vo.setAttachmentId(task.getAttachmentId());
        vo.setProjectId(task.getProjectId());
        vo.setFilename(task.getFilename());
        vo.setTaskKey(AiPipelineKeys.taskKey(task.getContentHash(), task.getPipelineRev()));
        vo.setPipelineToken(tokens.issue(vo.getRunId()));
        vo.setLeaseExpiresAt(AiPipelineRunLookup.format(expiresAt));
        vo.setClaimedBy(claimedBy);
        vo.setTaskType(task.getTaskType());
        vo.setStatus(AttachmentAiTask.RUNNING);
        vo.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        vo.setContentHash(task.getContentHash());
        vo.setPipelineRev(task.getPipelineRev());
        vo.setCleanRev(task.getCleanRev());
        vo.setPriority(task.getPriority());
        vo.setStageKey(stage == null ? null : stage.key());
        vo.setStageName(stage == null ? null : stage.name());
        vo.setHeartbeatSeconds(heartbeatSeconds(leaseSeconds));
        log.info("[ai-pipeline] {} 领取任务 {}（attachment={}, taskType={}, lease={}s）",
                claimedBy, vo.getRunId(), task.getAttachmentId(), task.getTaskType(), leaseSeconds);
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════
    // 2) heartbeat：续约
    // ══════════════════════════════════════════════════════════════════

    /**
     * 续约。
     *
     * <p><b>{@code claimed_by} 是可选字段</b>（契约里列了它，但不强制）：AI 侧当前对心跳发的是
     * <b>空 body</b>，靠服务间令牌证明"我是那台 AI 服务"。三种情形分开处理：
     * <ul>
     *   <li>带了 {@code claimed_by} ⇒ 必须是当前持有者，否则 409（防两个 AI 实例互相续约）；</li>
     *   <li>没带 ⇒ 只要求"这一行仍是 RUNNING 且租约尚未过期"，同样给 409 语义；</li>
     *   <li>租约已过期 / 任务已终态 ⇒ 409（<b>AI 应停止并放回</b>），与带不带 claimed_by 无关。</li>
     * </ul>
     *
     * @throws AiPipelineException 404 run 不存在；409 租约已失效 / 不是本持有者 / 任务已不在 RUNNING
     */
    @Transactional
    public HeartbeatVO heartbeat(String runId, HeartbeatRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        String claimedBy = request == null ? null : trim(request.getClaimedBy());
        int leaseSeconds = leaseSeconds(request == null ? null : request.getLeaseSeconds());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(leaseSeconds);

        LambdaUpdateWrapper<AttachmentAiTask> renew = new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, task.getId())
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .isNotNull(AttachmentAiTask::getClaimExpiresAt)
                .gt(AttachmentAiTask::getClaimExpiresAt, now)
                .set(AttachmentAiTask::getClaimExpiresAt, expiresAt)
                .set(AttachmentAiTask::getUpdateTime, now);
        if (claimedBy != null && !claimedBy.isBlank()) {
            renew.eq(AttachmentAiTask::getClaimedBy, claimedBy);
        }
        int affected = taskMapper.update(null, renew);

        if (affected != 1) {
            // 三种原因分开报：AI 侧的处理动作不同（放回 / 停手 / 别抢别人的）
            if (!AttachmentAiTask.RUNNING.equals(task.getStatus())) {
                throw AiPipelineException.conflict("续约失败：" + runId + " 已不在 RUNNING（当前 "
                        + task.getStatus() + "）。请停止本次执行：若是终态则无需放回，"
                        + "否则调用 POST /runs/" + runId + "/release 放回队列");
            }
            if (claimedBy != null && !claimedBy.isBlank()
                    && task.getClaimedBy() != null && !task.getClaimedBy().equals(claimedBy)) {
                throw AiPipelineException.conflict("续约失败：" + runId + " 当前由 " + task.getClaimedBy()
                        + " 持有，不是 " + claimedBy + "。拒绝续约（避免两个执行者同时写同一份任务）");
            }
            throw AiPipelineException.conflict("租约已失效：" + runId + " 的 claim_expires_at="
                    + AiPipelineRunLookup.format(task.getClaimExpiresAt()) + " 已过期。"
                    + "请立即停止本次执行并调用 POST /runs/" + runId + "/release 放回队列"
                    + "（过期后它可能已被其它节点重新领取）");
        }

        HeartbeatVO vo = new HeartbeatVO();
        vo.setRunId(runId);
        vo.setStatus(AttachmentAiTask.RUNNING);
        vo.setLeaseExpiresAt(AiPipelineRunLookup.format(expiresAt));
        vo.setLeaseSeconds(leaseSeconds);
        // 没带 claimed_by 时回显"库里记的持有者"，让 AI 侧至少能看到是谁领的这一行
        vo.setClaimedBy(claimedBy == null || claimedBy.isBlank() ? task.getClaimedBy() : claimedBy);
        vo.setHeartbeatSeconds(heartbeatSeconds(leaseSeconds));
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════
    // 3) release：放回队列
    // ══════════════════════════════════════════════════════════════════

    /**
     * 放回队列（{@code status='QUEUED'} + 清租约）。
     *
     * <p>幂等：已经是 QUEUED 时直接回成功（不报错），因为 AI 侧在"续约失败 → 放回"这条路上
     * 可能重发；把幂等动作做成 409 只会逼调用方写特例。
     *
     * @throws AiPipelineException 404 run 不存在；400 非法迁移（如终态任务）；409 并发冲突
     */
    @Transactional
    public ReleaseVO release(String runId, ReleaseRequest request) {
        AttachmentAiTask task = lookup.require(runId);
        String claimedBy = request == null ? null : trim(request.getClaimedBy());
        String reason = request == null ? null : trim(request.getReason());

        if (AttachmentAiTask.QUEUED.equals(task.getStatus())) {
            ReleaseVO vo = new ReleaseVO();
            vo.setRunId(runId);
            vo.setStatus(AttachmentAiTask.QUEUED);
            vo.setReason(reason);
            vo.setReleasedBy(claimedBy);
            return vo;
        }
        if (!AttachmentAiTask.RUNNING.equals(task.getStatus())) {
            // 先让状态机表态：CANCELLED/DEAD 是"无出边"的终态，它会给出统一文案
            AiPipelineStateMachine.requireTaskTransition(task.getStatus(), AttachmentAiTask.QUEUED);
            // DONE/PARTIAL/FAILED 在状态机上确实有 → QUEUED 的边，但那条边属于
            // "重试/重建"（新建任务行）而不是"领取者交还"。放回队列只对 RUNNING 有意义，
            // 否则会把"已完成的任务"重新排队、白跑一遍解析。
            throw AiPipelineException.badRequest("放回队列只适用于 RUNNING 的任务（当前 "
                    + task.getStatus() + "）：它表示「领取者主动交还」。要重跑 "
                    + (AttachmentAiTask.DONE.equals(task.getStatus()) ? "已完成" : "已结束")
                    + "的任务请走重新解析/重建（新建任务行，历史保留），而不是放回队列");
        }

        LocalDateTime now = LocalDateTime.now();
        int affected = taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, task.getId())
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .set(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED)
                .set(AttachmentAiTask::getClaimedBy, null)
                .set(AttachmentAiTask::getClaimExpiresAt, null)
                .set(AttachmentAiTask::getUpdateTime, now));
        if (affected != 1) {
            throw AiPipelineException.conflict("放回失败：" + runId
                    + " 的状态刚刚被其它写入者改掉了（可能已进入终态）。请重新查询 run 详情后再决定"
                    + "（不要重试放回，否则会把已完成的解析重新排队）");
        }
        events.record(runId, task.getId(), task.getProjectId(), null,
                AttachmentAiTask.RUNNING, AttachmentAiTask.QUEUED,
                "RELEASE" + (reason == null || reason.isBlank() ? "" : ":" + reason),
                claimedBy == null || claimedBy.isBlank() ? task.getClaimedBy() : claimedBy);
        ReleaseVO vo = new ReleaseVO();
        vo.setRunId(runId);
        vo.setStatus(AttachmentAiTask.QUEUED);
        vo.setReason(reason);
        vo.setReleasedBy(claimedBy);
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════
    // 4) T7：租约过期的 RUNNING 行回收
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把"租约已过期却还挂在 RUNNING"的行放回 QUEUED（§4.4.1 T7，stale claim recovery）。
     *
     * <p>为什么需要它：进程被 kill、容器被重启时没人会来 release，那些行如果一直挂在 RUNNING，
     * 既不会被再次领取，也不会失败——正是设计文档反复要消灭的"永久 QUEUED/RUNNING"。
     *
     * <p><b>它由谁驱动：只由 {@link #claim} 调用</b>（HTTP 领取路径），没有定时任务。
     * 这点必须保持：见 {@link #claim} 的注释——加定时器会把"仍在解析但租约到期"的行提前放回，
     * 导致同一份文件被二次解析。grep 证据：{@code reclaimStaleClaims} 在产品代码里只有一个调用点。
     *
     * @return 实际回收的行数（0 是常态）
     */
    @Transactional
    public int reclaimStaleClaims() {
        LocalDateTime now = LocalDateTime.now();
        List<AttachmentAiTask> stale = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .select(AttachmentAiTask::getId, AttachmentAiTask::getProjectId,
                        AttachmentAiTask::getClaimedBy, AttachmentAiTask::getClaimExpiresAt)
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .isNotNull(AttachmentAiTask::getClaimExpiresAt)
                .lt(AttachmentAiTask::getClaimExpiresAt, now)
                .orderByAsc(AttachmentAiTask::getId));
        int reclaimed = 0;
        for (AttachmentAiTask row : stale) {
            if (reclaimed >= MAX_RECLAIM_PER_CALL) {
                break;
            }
            int affected = taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                    .eq(AttachmentAiTask::getId, row.getId())
                    .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                    .isNotNull(AttachmentAiTask::getClaimExpiresAt)
                    .lt(AttachmentAiTask::getClaimExpiresAt, now)
                    .set(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED)
                    .set(AttachmentAiTask::getClaimedBy, null)
                    .set(AttachmentAiTask::getClaimExpiresAt, null)
                    .set(AttachmentAiTask::getUpdateTime, now));
            if (affected != 1) {
                // 竞争失败：说明这一行刚好被真正的主人续约/完成，属于正常竞争，不是错误
                continue;
            }
            events.record(AiPipelineKeys.runIdOf(row.getId()), row.getId(), row.getProjectId(), null,
                    AttachmentAiTask.RUNNING, AttachmentAiTask.QUEUED,
                    "STALE_CLAIM: 租约过期（claim_expires_at="
                            + AiPipelineRunLookup.format(row.getClaimExpiresAt()) + "）自动放回队列",
                    row.getClaimedBy());
            reclaimed++;
        }
        if (reclaimed > 0) {
            log.warn("[ai-pipeline] 回收了 {} 条租约过期的 RUNNING 任务（已放回 QUEUED）", reclaimed);
        }
        return reclaimed;
    }

    // ══════════════════════════════════════════════════════════════════
    // 5) 推送模式：主系统把任务交给 AI 之后登记"我领了"
    // ══════════════════════════════════════════════════════════════════

    /**
     * 主系统推送路径（{@code AiTaskService.submit} 把附件交给 AI 之后）登记领取。
     *
     * <p>两步都是<b>条件 UPDATE + 受影响行数</b>，与 AI 侧 claim 共用同一把锁：
     * <ol>
     *   <li>正常路径：行还是 QUEUED ⇒ 与 claim 完全一样的语句（QUEUED→RUNNING + 租约）；</li>
     *   <li>AI 提交响应已把它标成 RUNNING（PARSING）⇒ 只补登记租约
     *       （{@code WHERE status='RUNNING' AND claim_expires_at IS NULL}）。
     *       若此时 {@code claim_expires_at} 已有值，说明 AI 侧已经领过它了——
     *       那不是我们要抢的，安静地让给持有者。</li>
     * </ol>
     *
     * @return true = 本节点确实登记到了（或本就不需要登记）；false = 已被别的持有者领走
     */
    @Transactional
    public boolean registerPushedClaim(AttachmentAiTask task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(props.effectiveLeaseSeconds());
        String stageKey = stageKeyFor(task);

        if (tryClaim(task.getId(), nodeId, expiresAt, now, stageKey)) {
            task.setClaimedBy(nodeId);
            task.setClaimExpiresAt(expiresAt);
            events.record(AiPipelineKeys.runIdOf(task.getId()), task.getId(), task.getProjectId(), null,
                    AttachmentAiTask.QUEUED, AttachmentAiTask.RUNNING, "CLAIM:" + nodeId, nodeId);
            return true;
        }
        int leased = taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, task.getId())
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .isNull(AttachmentAiTask::getClaimExpiresAt)
                .set(AttachmentAiTask::getClaimedBy, nodeId)
                .set(AttachmentAiTask::getClaimExpiresAt, expiresAt)
                .set(AttachmentAiTask::getUpdateTime, now));
        if (leased == 1) {
            task.setClaimedBy(nodeId);
            task.setClaimExpiresAt(expiresAt);
            return true;
        }
        log.warn("[ai-pipeline] 任务 {} 的领取登记未生效（已被其它持有者领取或状态已变），"
                + "本节点不再写它", task.getId());
        return false;
    }

    /**
     * 提交任务时记一条 {@code (空) → QUEUED} 的事件（§4.4.1 T1）。
     *
     * <p>它让任务流视图从第一步就有痕迹：用户点了解析 ⇒ 事件流里立刻能看到
     * {@code SUBMIT:PARSE}，而不是等 AI 第一次上报才知道"它被提交过"。
     */
    @Transactional
    public void recordSubmit(AttachmentAiTask task, String action) {
        if (task == null || task.getId() == null) {
            return;
        }
        events.record(AiPipelineKeys.runIdOf(task.getId()), task.getId(), task.getProjectId(), null,
                null, AttachmentAiTask.QUEUED, "SUBMIT:" + action, nodeId);
    }

    /**
     * 给<b>新建</b>的任务行填 V15 的流水线列（分类 / 优先级 / 版本串两半）。
     *
     * <p>为什么这件事在 claim 服务里：领取时要用 {@code priority} 排序、要用
     * {@code content_hash + pipeline_rev} 派生 {@code task_key}——"填"与"用"放两处，
     * 一定会出现"领取按 batch 优先级排、提交却写了 interactive"这种对齐不上。
     *
     * @param action 触发动作（{@code PARSE} 手动 / {@code AUTO_PARSE} 上传后自动 / {@code RETRY} 重试）
     */
    public void decorateNewTask(AttachmentAiTask task, String action) {
        if (task == null) {
            return;
        }
        String act = action == null ? AttachmentAiTask.TYPE_PARSE : action.trim().toUpperCase();
        // RETRY 单独一类；AUTO_PARSE 与手动 PARSE 同为 PARSE 分类，差别在优先级
        task.setTaskType(AttachmentAiTask.TYPE_RETRY.equals(act)
                ? AttachmentAiTask.TYPE_RETRY : AttachmentAiTask.TYPE_PARSE);
        boolean interactive = !"AUTO_PARSE".equals(act);
        task.setPriority(interactive ? props.getInteractivePriority() : props.getBatchPriority());
        task.setPipelineRev(props.getPipelineRev());
        task.setCleanRev(props.getCleanRev());
        task.setAttempts(0);
        task.setClaimedBy(null);
        task.setClaimExpiresAt(null);
    }

    /** 本节点标识（主机名/pid；取不到就退化成 {@code main-unknown}，绝不因此让请求失败）。 */
    private static String localNodeId() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 内网 DNS 不通时 getLocalHost 会抛：这只影响可读性，不影响领取语义
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String id = host + "/" + (pid == null ? "0" : pid);
        return id.length() <= 64 ? id : id.substring(0, 64);
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    /** 单行的条件领取（唯一一处"影响行数 = 1 才算领到"）。 */
    private boolean tryClaim(Long taskId, String claimedBy, LocalDateTime expiresAt,
                             LocalDateTime now, String stageKey) {
        AiPipelineStages.Stage stage = AiPipelineStages.of(stageKey);
        int affected = taskMapper.update(null, new LambdaUpdateWrapper<AttachmentAiTask>()
                .eq(AttachmentAiTask::getId, taskId)
                .eq(AttachmentAiTask::getStatus, AttachmentAiTask.QUEUED)
                .and(w -> w.isNull(AttachmentAiTask::getClaimExpiresAt)
                        .or().lt(AttachmentAiTask::getClaimExpiresAt, now))
                .set(AttachmentAiTask::getStatus, AttachmentAiTask.RUNNING)
                .set(AttachmentAiTask::getClaimedBy, claimedBy)
                .set(AttachmentAiTask::getClaimExpiresAt, expiresAt)
                .set(AttachmentAiTask::getStageKey, stage == null ? null : stage.key())
                .set(AttachmentAiTask::getStage, stage == null ? null : stage.name())
                .setSql("attempts = attempts + 1")
                .set(AttachmentAiTask::getUpdateTime, now));
        return affected == 1;
    }

    /** 解析类任务领取后进入 PARSE 阶段；其它分类不预设阶段（避免"重建索引"显示成"识别"）。 */
    private static String stageKeyFor(AttachmentAiTask task) {
        String type = task.getTaskType();
        if (type == null || AttachmentAiTask.TYPE_PARSE.equals(type) || AttachmentAiTask.TYPE_RETRY.equals(type)) {
            return "PARSE";
        }
        return null;
    }

    /** 建议心跳间隔 = 租约的 1/3（默认 90s ⇒ 30s，与设计文档 §4.3 的"30s 心跳"一致）。 */
    private static int heartbeatSeconds(int leaseSeconds) {
        return Math.max(5, leaseSeconds / 3);
    }

    private int leaseSeconds(Integer requested) {
        int base = requested == null ? props.effectiveLeaseSeconds() : requested;
        if (base < AiPipelineProperties.MIN_LEASE_SECONDS) {
            return AiPipelineProperties.MIN_LEASE_SECONDS;
        }
        return Math.min(base, AiPipelineProperties.MAX_LEASE_SECONDS);
    }

    private static String normalizeTaskType(String taskType) {
        String s = trim(taskType);
        if (s == null || s.isBlank()) {
            return null;
        }
        String upper = s.toUpperCase();
        if (!TASK_TYPES.contains(upper)) {
            throw AiPipelineException.badRequest("task_type 非法：" + taskType
                    + "；合法值：" + String.join("/", TASK_TYPE_LIST));
        }
        return upper;
    }

    private static String requireText(String value, String field) {
        String s = trim(value);
        if (s == null || s.isBlank()) {
            throw AiPipelineException.badRequest(field + " 不能为空：它写进 claimed_by 与事件流，"
                    + "是「这份任务被谁领走了」的唯一线索（建议用 主机名/进程id）");
        }
        return s.length() <= 64 ? s : s.substring(0, 64);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
