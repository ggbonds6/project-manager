package com.pmgt.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目级口径计算（合同金额 / 已付 / 当前阶段 / 整体进度 / 阶段附件数）。
 *
 * <h2>为什么要把它们从 {@link ProjectService} 里搬出来</h2>
 * <p>这些算法原先都是 {@code ProjectService} 的私有方法：项目列表与项目详情在用，
 * 但对外没有入口。P2 的受控查询（§11.4 的 {@code projects} 与
 * {@code stats.kind=phase_attachment_count}）问的是<b>同一批数字</b>，
 * 契约 §11.7 的验收口径是"与项目详情页、统计页<b>逐位一致</b>"。
 *
 * <p>只有两种做法能保证逐位一致：① 复制一份算法（迟早漂移）；② 抽成公共入口、两边都调它。
 * 这里选 ②。<b>算法一行未改</b>，只是从 {@code private} 变成可复用的公开方法，
 * 既有接口的输出因此完全不变。
 *
 * <h2>口径备忘</h2>
 * <ul>
 *   <li><b>合同金额</b>：叶子项目 = 所挂合同金额（{@code inclChange=true} 时加变更）；
 *       容器项目（有子项目）= 子项目之和，与统计口径一致；</li>
 *   <li><b>已付</b>：叶子 = 自身付款实付合计；容器 = 子项目之和；</li>
 *   <li><b>整体进度</b>：Σ(完成权重×100 + 进行中权重×比例) / Σ(未跳过权重)，取整；</li>
 *   <li><b>阶段附件数</b>：{@code attachment.biz_type='PROJECT_PHASE' 且 biz_id=project_phase.id}，
 *       逻辑删除的附件由 {@code @TableLogic} 在 SQL 层排除（见 {@link #attachmentCountByPhase}）。</li>
 * </ul>
 */
@Service
public class ProjectMetricsService {

    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;
    private final ContractMapper contractMapper;
    private final AttachmentMapper attachmentMapper;
    private final ContractLinkService contractLinkService;

    public ProjectMetricsService(ProjectMapper projectMapper,
                                 ProjectPhaseMapper phaseMapper,
                                 PaymentMapper paymentMapper,
                                 ContractMapper contractMapper,
                                 AttachmentMapper attachmentMapper,
                                 ContractLinkService contractLinkService) {
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
        this.contractMapper = contractMapper;
        this.attachmentMapper = attachmentMapper;
        this.contractLinkService = contractLinkService;
    }

    // ── 阶段 ────────────────────────────────────────────────────────

    /**
     * 批量取各项目的阶段（按 sort_no 升序）。
     *
     * <p>一次查库、不在循环里查：项目列表/受控查询都会一次要几十个项目的阶段。
     */
    public Map<Long, List<ProjectPhase>> phasesByProjects(Collection<Long> projectIds) {
        List<Long> ids = distinct(projectIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                        .in(ProjectPhase::getProjectId, ids)
                        .orderByAsc(ProjectPhase::getSortNo))
                .stream()
                .collect(Collectors.groupingBy(ProjectPhase::getProjectId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** 单个项目的阶段（按 sort_no 升序）。 */
    public List<ProjectPhase> phasesOf(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                .eq(ProjectPhase::getProjectId, projectId)
                .orderByAsc(ProjectPhase::getSortNo));
    }

    /** 当前阶段：第一个"进行中"或"未开始"的阶段；全部完成/跳过时返回"已完结"，无阶段返回 null。 */
    public String currentPhaseName(List<ProjectPhase> phases) {
        List<ProjectPhase> list = phases == null ? List.of() : phases;
        for (ProjectPhase p : list) {
            if ("NOT_STARTED".equals(p.getStatus()) || "IN_PROGRESS".equals(p.getStatus())) {
                return p.getPhaseName();
            }
        }
        return list.isEmpty() ? null : "已完结";
    }

    /** 整体进度 = Σ(已完成权重×100 + 进行中权重×比例) / Σ(未跳过权重)，取整。 */
    public Integer overallProgress(List<ProjectPhase> phases) {
        int total = 0;
        int got = 0;
        for (ProjectPhase p : phases == null ? List.<ProjectPhase>of() : phases) {
            if ("SKIPPED".equals(p.getStatus())) {
                continue;
            }
            int w = p.getWeight() == null ? 0 : p.getWeight();
            total += w;
            if ("DONE".equals(p.getStatus())) {
                got += w * 100;
            } else if ("IN_PROGRESS".equals(p.getStatus())) {
                int pct = p.getPercent() == null ? 0 : p.getPercent();
                got += (int) Math.round(w * pct / 100.0 * 100);
            }
        }
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(got * 100.0 / total / 100);
    }

    /**
     * 各阶段的附件数（§11.4 {@code phase_attachment_count} 的计数口径）。
     *
     * <p><b>口径</b>：{@code attachment.biz_type = 'PROJECT_PHASE'} 且
     * {@code attachment.biz_id = project_phase.id}；<b>不含</b>合同/付款/项目级附件。
     *
     * <p><b>逻辑删除</b>：{@code Attachment.deleted} 上有 {@code @TableLogic}，
     * MyBatis-Plus 会在生成的 SQL 里追加 {@code deleted = 0}，因此已删附件天然不计入
     * ——这一点很关键（"删了附件数字还不变"会被当成统计坏了），所以它由框架保证、
     * 而不是靠这里手写条件（手写反而容易被后续重构漏掉）。
     *
     * @return phaseId → 附件数；<b>没有附件的阶段不会出现在 Map 里</b>（调用方用
     *         {@code getOrDefault(id, 0L)} 取值，保证"0 个"与"没查"不会混淆）
     */
    public Map<Long, Long> attachmentCountByPhase(Collection<Long> phaseIds) {
        List<Long> ids = distinct(phaseIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, "PROJECT_PHASE")
                .in(Attachment::getBizId, ids));
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Attachment a : attachments) {
            counts.merge(a.getBizId(), 1L, Long::sum);
        }
        return counts;
    }

    // ── 金额 ────────────────────────────────────────────────────────

    /**
     * 项目合同金额（查询时实时汇总）。
     *
     * <p>叶子项目 = 所挂合同金额（{@code inclChange} 时加变更）；未挂合同时取项目遗留合同列；
     * 总项目容器 = 子项目之和（与统计口径一致）。
     */
    public BigDecimal contractAmount(Project pj, boolean inclChange) {
        List<Project> children = directChildren(pj.getId());
        if (!children.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Project ch : children) {
                sum = sum.add(contractAmount(ch, inclChange));
            }
            return sum;
        }
        if (pj.getContractId() != null) {
            Contract c = contractMapper.selectById(pj.getContractId());
            if (c != null) {
                BigDecimal total = zero(c.getContractAmount());
                if (inclChange) {
                    total = total.add(zero(c.getChangeAmount()));
                }
                return total;
            }
        }
        BigDecimal total = zero(pj.getContractAmount());
        if (inclChange) {
            total = total.add(zero(pj.getChangeAmount()));
        }
        return total;
    }

    /** 已付金额（查询时实时汇总）：叶子 = 自身付款实付合计；容器 = 子项目之和。 */
    public BigDecimal paidAmount(Project pj) {
        List<Project> children = directChildren(pj.getId());
        BigDecimal sum = BigDecimal.ZERO;
        if (children.isEmpty()) {
            sum = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                            .eq(Payment::getProjectId, pj.getId()))
                    .stream().map(p -> zero(p.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            for (Project ch : children) {
                sum = sum.add(paidAmount(ch));
            }
        }
        return sum;
    }

    /**
     * 项目可见的合同数（含父级总项目共享的合同）。
     *
     * <p>与项目详情页「合同」面板同口径：V12 起以 {@code project_contract} 关联表为准，
     * 因此监理/测评等副合同都会被计入（只认 {@code project.contract_id} 会漏）。
     */
    public int contractCount(Long projectId) {
        return projectId == null ? 0 : contractLinkService.visibleContractIds(projectId).size();
    }

    /** 直取子项目。 */
    private List<Project> directChildren(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return projectMapper.selectList(new LambdaQueryWrapper<Project>().eq(Project::getParentId, parentId));
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    public static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
