package com.pmgt.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.exception.BizException;
import com.pmgt.module.ai.entity.AttachmentAiTask;
import com.pmgt.module.ai.mapper.AttachmentAiTaskMapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.service.ContractLinkService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作用域与权限解析——<b>本次集成里安全上最关键的一个类</b>。
 *
 * <h2>为什么不许前端传 doc_id</h2>
 * doc_id 是 AI 服务的概念。如果让前端直接传 doc_id，就等于让浏览器自己决定检索范围，
 * 而 AI 服务不做任何权限判断（方案 §4.1 第 3 条边界），结果就是「谁知道 doc_id 谁就能读全文」。
 * 所以：前端只说「哪些附件 / 哪个项目」→ 主系统把它换算成 doc_id → 再交给 AI 服务。
 *
 * <h2>可访问性怎么定义</h2>
 * 与主系统附件接口（{@code AttachmentController}）保持同一条口径：<b>登录用户即可见全部
 * 未删除项目</b>——本系统的角色模型（ADMIN / MANAGER / VIEWER）不按项目分权，
 * 项目级数据权限本轮不存在。所以这里校验的是「这个 id 确实存在且没被逻辑删除」，
 * 而不是「这个人有没有这个项目」。这一点必须写清楚：它意味着**本类不是数据权限的兜底**，
 * 将来若引入项目级权限，只需要在这里加一处判断，AI 侧不用动。
 *
 * <h2>为什么「不可检索」也当拒绝</h2>
 * 已请求但未解析入库的附件，如果静默丢掉，用户会觉得「AI 装看不见这份文件」；
 * 直接拒绝并说明「该附件尚未解析完成」才是可操作的（先去点解析）。
 */
@Component
public class AiScopeResolver {

    /** §9 的附件索引状态枚举。 */
    public static final String STATUS_NOT_PARSED = "NOT_PARSED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private final AttachmentMapper attachmentMapper;
    private final AttachmentAiTaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;
    private final ContractLinkService contractLinkService;

    public AiScopeResolver(AttachmentMapper attachmentMapper,
                           AttachmentAiTaskMapper taskMapper,
                           ProjectMapper projectMapper,
                           ProjectPhaseMapper phaseMapper,
                           PaymentMapper paymentMapper,
                           ContractLinkService contractLinkService) {
        this.attachmentMapper = attachmentMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
        this.contractLinkService = contractLinkService;
    }

    /**
     * 问答作用域（§9 #9）。
     *
     * @param projectId     项目作用域；null 表示不限项目
     * @param attachmentIds 前端指定的附件；null/空表示「该项目下全部已可检索附件」
     */
    public ChatScope resolveForChat(Long projectId, List<Long> attachmentIds) {
        Project project = projectId == null ? null : requireProject(projectId);

        // 1) 候选集：先按「用户声明的范围」取，绝不在这一步偷偷放宽
        List<Attachment> candidates = attachmentIds == null || attachmentIds.isEmpty()
                ? listByProject(projectId, null)
                : selectByIds(attachmentIds);

        // 2) 逐项校验「存在 + 没被删 + 在该项目内」，不可访问的直接拒绝（不静默过滤）
        List<String> problems = new ArrayList<>();
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            Map<Long, Attachment> found = new LinkedHashMap<>();
            for (Attachment a : candidates) {
                found.put(a.getId(), a);
            }
            for (Long id : new LinkedHashSet<>(attachmentIds)) {
                Attachment a = found.get(id);
                if (a == null) {
                    problems.add("附件 " + id + " 不存在或已删除");
                } else if (projectId != null && !projectId.equals(projectIdOf(a))) {
                    problems.add("附件 " + id + " 不属于项目 " + projectId);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw AiScopeDeniedException.of(problems);
        }

        // 3) 再校验「能不能问答」：只有已可检索且拿到 doc_id 的附件能进检索范围
        List<Attachment> usable = new ArrayList<>();
        List<String> notReady = new ArrayList<>();
        for (Attachment a : candidates) {
            String status = a.getAiIndexStatus();
            if (STATUS_READY.equals(status) && StringUtils.hasText(a.getAiDocId())) {
                usable.add(a);
            } else if (attachmentIds != null && !attachmentIds.isEmpty()) {
                // 仅在用户「点名」了这些附件时才报错；全项目范围下静默跳过是合理的
                // （项目里总有没解析的附件，不能因此让整个项目问不了）
                notReady.add("附件 " + a.getId() + "（" + a.getFileName() + "）" + notReadyReason(a));
            }
        }
        if (!notReady.isEmpty()) {
            throw new AiScopeDeniedException("以下附件当前无法参与问答：" + String.join("；", notReady)
                    + "。请在附件列表触发解析，待状态变为「已可检索」后重试");
        }

        List<Long> allowedIds = usable.stream().map(Attachment::getId).distinct().toList();
        List<String> docIds = usable.stream().map(Attachment::getAiDocId)
                .filter(StringUtils::hasText).distinct().toList();

        // docIds 空 → 上层给「没有可问答的文档」的明确答复，不回落到「检索全部」
        return new ChatScope(projectId, project == null ? null : project.getName(),
                List.copyOf(new LinkedHashSet<>(attachmentIds == null ? List.of() : attachmentIds)),
                allowedIds, docIds);
    }

    /**
     * 解析一个附件的可访问性（解析触发 / 重试 用）。
     *
     * @throws BizException 404 附件不存在
     */
    public Attachment requireAccessible(Long attachmentId) {
        Attachment a = attachmentMapper.selectById(attachmentId);
        if (a == null) {
            throw new BizException(404, "附件不存在");
        }
        return a;
    }

    /**
     * 按 id 批量取附件并校验全部存在（§9 #8 附件状态用）。
     *
     * <p>同样不静默过滤：传入的 id 有一个不存在就整体拒绝，
     * 因为「状态列表少了一条」会让前端标签永远停在初始态而不是报错，更难排查。
     */
    public List<Attachment> requireAccessibleAll(Collection<Long> attachmentIds) {
        List<Long> ids = attachmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException(400, "attachmentIds 不能为空");
        }
        Map<Long, Attachment> found = new LinkedHashMap<>();
        for (Attachment a : selectByIds(ids)) {
            found.put(a.getId(), a);
        }
        List<String> missing = ids.stream().filter(id -> !found.containsKey(id)).map(id -> "附件 " + id).toList();
        if (!missing.isEmpty()) {
            throw AiScopeDeniedException.of(missing.stream().map(s -> s + " 不存在或已删除").toList());
        }
        return ids.stream().map(found::get).toList();
    }

    /** 项目下的全部附件（不限状态）。 */
    public List<Attachment> listByProject(Long projectId, List<String> statuses) {
        LambdaQueryWrapper<Attachment> qw = new LambdaQueryWrapper<Attachment>()
                .orderByDesc(Attachment::getUploadTime)
                .orderByDesc(Attachment::getId);
        if (projectId == null) {
            // 不限项目：全部未删除附件（调用方多为「按项目过滤」，这里只作为兜底）
            return attachmentMapper.selectList(qw);
        }
        // 项目附件的归属：项目级 + 阶段级 + 付款凭证级 + 合同级（与 AttachmentController 同口径）
        List<Long> phaseIds = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                        .eq(ProjectPhase::getProjectId, projectId))
                .stream().map(ProjectPhase::getId).toList();
        List<Long> paymentIds = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getProjectId, projectId))
                .stream().map(Payment::getId).toList();
        Set<Long> contractIds = visibleContractIds(projectId);

        qw.and(w -> {
            w.eq(Attachment::getBizType, "PROJECT").eq(Attachment::getBizId, projectId);
            if (!phaseIds.isEmpty()) {
                w.or(o -> o.eq(Attachment::getBizType, "PROJECT_PHASE").in(Attachment::getBizId, phaseIds));
            }
            if (!paymentIds.isEmpty()) {
                w.or(o -> o.eq(Attachment::getBizType, "PAYMENT").in(Attachment::getBizId, paymentIds));
            }
            if (!contractIds.isEmpty()) {
                w.or(o -> o.eq(Attachment::getBizType, "CONTRACT").in(Attachment::getBizId, contractIds));
            }
        });
        return attachmentMapper.selectList(qw);
    }

    /** 附件所属项目 id（与 AttachmentController.projectIdOf 同口径，另补 CONTRACT）。 */
    public Long projectIdOf(Attachment att) {
        if (att == null || att.getBizType() == null || att.getBizId() == null) {
            return null;
        }
        return switch (att.getBizType()) {
            case "PROJECT" -> att.getBizId();
            case "PROJECT_PHASE" -> {
                ProjectPhase ph = phaseMapper.selectById(att.getBizId());
                yield ph == null ? null : ph.getProjectId();
            }
            case "PAYMENT" -> {
                Payment pay = paymentMapper.selectById(att.getBizId());
                yield pay == null ? null : pay.getProjectId();
            }
            case "CONTRACT" -> {
                // 合同附件的归属：交给 ContractLinkService（V12 的关联表是权威，project.contract_id 只是主合同指针）
                List<Long> pids = contractLinkService.projectIdsOfContract(att.getBizId());
                yield pids.isEmpty() ? null : pids.get(0);
            }
            default -> null;
        };
    }

    /** 批量取附件（空集合直接返回空列表，避免无效查询与 MyBatis 空 IN 的方言问题）。 */
    public List<Attachment> selectByIds(Collection<Long> ids) {
        List<Long> list = ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return list.isEmpty() ? List.of() : attachmentMapper.selectBatchIds(list);
    }

    /** 附件 → 项目名（批量，一次查库）。 */
    public Map<Long, String> projectNames(Collection<Long> projectIds) {
        List<Long> ids = projectIds == null ? List.of()
                : projectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Project p : projectMapper.selectBatchIds(ids)) {
            names.put(p.getId(), p.getName());
        }
        return names;
    }

    /** 项目是否存在（不存在即 404，与其他模块一致）。 */
    public Project requireProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BizException(404, "项目不存在");
        }
        return p;
    }

    /**
     * 当前用户可访问的项目 id 清单（P2 受控查询的作用域令牌用它，§11.2）。
     *
     * <p><b>口径与可访问性定义</b>：本系统的角色模型（ADMIN / MANAGER / VIEWER）不按项目分权，
     * 登录用户即可见全部<b>未删除</b>项目——与本类 {@link #resolveForChat} 的判定同一条口径
     * （那里的注释也写明了"这不是数据权限兜底"）。逻辑删除由 {@code @TableLogic} 在 SQL 层排除。
     *
     * <p>将来若引入项目级数据权限，只要改这一个方法（以及 {@code resolveForChat} 那一处判断），
     * AI 侧与受控查询接口都不用动——scope_token 里的 projects 自然跟着变窄。
     */
    public List<Long> accessibleProjectIds() {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .select(Project::getId)
                        .orderByAsc(Project::getId))
                .stream().map(Project::getId).toList();
    }

    /** 项目维度的最新解析任务（批量），供附件状态与文档列表补 error/进度。 */
    public Map<Long, AttachmentAiTask> latestTaskByAttachment(Collection<Long> attachmentIds) {
        List<Long> ids = attachmentIds == null ? List.of()
                : attachmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<AttachmentAiTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AttachmentAiTask>()
                .in(AttachmentAiTask::getAttachmentId, ids)
                .orderByDesc(AttachmentAiTask::getId));
        Map<Long, AttachmentAiTask> out = new LinkedHashMap<>();
        for (AttachmentAiTask t : tasks) {
            // 列表已按 id 倒序：第一次出现的就是最新一条
            out.putIfAbsent(t.getAttachmentId(), t);
        }
        return out;
    }

    private String notReadyReason(Attachment a) {
        return switch (a.getAiIndexStatus()) {
            case STATUS_PARSING -> "正在解析中，请稍后再试";
            case STATUS_FAILED -> "上次解析失败，请先重试解析";
            default -> "尚未解析入库";
        };
    }

    /**
     * 项目可见的合同链（本项目 + 各级父项目共享的合同）。
     *
     * <p>直接复用 {@link ContractLinkService}：V12 起「项目挂哪些合同」以关联表为准，
     * 各调用方自己沿 {@code project.contract_id} 向上爬是与 V12 语义冲突的老写法
     * （一个项目可挂多份合同，只认主合同指针会漏掉监理/测评等副合同附件）。
     */
    private Set<Long> visibleContractIds(Long projectId) {
        return contractLinkService.visibleContractIds(projectId);
    }

    /**
     * 解析后的问答作用域。
     *
     * @param projectId          项目 id（可空）
     * @param projectName        项目名（可空，仅供留痕与提示）
     * @param requestedAttachmentIds 用户显式指定的附件 id（用于留痕：记录「他当时要问什么」）
     * @param allowedAttachmentIds   实际允许并参与问答的附件 id
     * @param docIds             换算给 AI 服务的文档 id（<b>可能为空</b>，上层需处理）
     */
    public record ChatScope(
            Long projectId,
            String projectName,
            List<Long> requestedAttachmentIds,
            List<Long> allowedAttachmentIds,
            List<String> docIds) {
    }
}
