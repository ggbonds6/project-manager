package com.pmgt.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作台 / 项目统计聚合服务（父子项目口径 v1.2）：
 * - “核算单元” = 叶子项目（子项目，或无子项目的顶层项目）；有子的顶层仅作容器，不计入计数/漏斗；
 * - 金额按合同(contract)口径：合同总额=各合同去重求和（含变更）；已付=付款去重求和；预算=核算单元预算合计；
 * - 年度资金：预算按核算单元立项年归集；合同金额/已付按合同覆盖的首个子项目立项年归集一次（不重复）。
 */
@Service
public class StatsService {

    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;
    private final ContractMapper contractMapper;

    public StatsService(ProjectMapper projectMapper,
                        ProjectPhaseMapper phaseMapper,
                        PaymentMapper paymentMapper,
                        ContractMapper contractMapper) {
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
        this.contractMapper = contractMapper;
    }

    // ==================== 概览卡 ====================

    public Map<String, Object> summary(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        List<Project> leaves = leaves(projects);
        Common c = commonOf(projects, leaves);
        int nowYear = LocalDate.now().getYear();

        BigDecimal contractTotal = c.contracts().stream()
                .map(x -> zero(x.getContractAmount()).add(zero(x.getChangeAmount())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectTotal", leaves.size());
        m.put("running", countBy(leaves, "RUN"));
        m.put("done", countBy(leaves, "DONE"));
        m.put("pause", countBy(leaves, "PAUSE"));
        m.put("stop", countBy(leaves, "STOP"));
        m.put("newThisYear", (int) leaves.stream().filter(p -> {
            if (p.getApproveDate() != null) return p.getApproveDate().getYear() == nowYear;
            return p.getCreateTime() != null && p.getCreateTime().getYear() == nowYear;
        }).count());
        m.put("overdueCount", c.overdueProjectIds().size());
        m.put("budgetTotal", sum(p -> p.getBudgetAmount(), leaves));
        m.put("contractTotal", contractTotal);
        m.put("paidTotal", c.paidTotal());
        m.put("execRate", contractTotal.signum() == 0 ? 0
                : c.paidTotal().multiply(BigDecimal.valueOf(100)).divide(contractTotal, 1, RoundingMode.HALF_UP));
        return m;
    }

    /** 构成分布：状态 / 类型 / 流程阶段漏斗（均以核算单元计） */
    public Map<String, Object> distributions(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        List<Project> leaves = leaves(projects);
        Common c = commonOf(projects, leaves);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", countGroup(leaves, Project::getStatus, List.of("RUN", "DONE", "PAUSE", "STOP")));
        m.put("type", countGroup(leaves, Project::getType, List.of("HW", "SW")));

        Map<String, Long> funnel = new LinkedHashMap<>();
        for (Project p : leaves) {
            String cur = currentPhaseName(c.phasesByProject().getOrDefault(p.getId(), List.of()));
            funnel.merge(cur == null ? "已完结" : cur, 1L, Long::sum);
        }
        m.put("funnel", funnel.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("value", e.getValue());
                    return row;
                }).toList());
        m.put("overdueProjects", c.overdueProjectIds().size());
        return m;
    }

    public Map<String, Object> yearMoney(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        List<Project> leaves = leaves(projects);
        Map<Integer, BigDecimal[]> acc = new LinkedHashMap<>();
        Function<Integer, BigDecimal[]> bucket = k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};

        // 预算按核算单元立项年
        for (Project p : leaves) {
            int y = p.getApproveDate() != null ? p.getApproveDate().getYear() : Year.now().getValue();
            acc.computeIfAbsent(y, bucket)[0] = acc.get(y)[0].add(zero(p.getBudgetAmount()));
        }
        // 合同金额/已付：按合同覆盖的首个子项目立项年归集一次
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .orderByAsc(Contract::getId));
        for (Contract c : contracts) {
            Integer year = leaves.stream()
                    .filter(p -> p.getContractId() != null && p.getContractId().equals(c.getId()))
                    .filter(p -> p.getApproveDate() != null)
                    .map(p -> p.getApproveDate().getYear())
                    .min(Integer::compareTo)
                    .orElseGet(() -> c.getCreateTime() != null ? c.getCreateTime().getYear() : Year.now().getValue());
            BigDecimal[] v = acc.computeIfAbsent(year, bucket);
            v[1] = v[1].add(zero(c.getContractAmount()).add(zero(c.getChangeAmount())));
            BigDecimal paid = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                            .eq(Payment::getContractId, c.getId()))
                    .stream().map(pr -> zero(pr.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            v[2] = v[2].add(paid);
        }

        List<Map<String, Object>> rows = acc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    BigDecimal[] v = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("year", e.getKey());
                    row.put("budget", v[0]);
                    row.put("contract", v[1]);
                    row.put("paid", v[2]);
                    return row;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        return out;
    }

    // ==================== 工作台 ====================

    public Map<String, Object> dashboard(Long userId) {
        List<Project> projects = loadProjects(null);
        List<Project> leaves = leaves(projects);
        Common c = commonOf(projects, leaves);
        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(60);

        Map<String, Object> cards = summary(null);
        Set<Long> leafIds = leaves.stream().map(Project::getId).collect(Collectors.toSet());
        Map<Long, String> nameOf = projects.stream().collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));

        // 我的待办（进行中且指派给我，仅核算单元）
        List<Map<String, Object>> myTodos = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                        .eq(ProjectPhase::getManagerUserId, userId)
                        .in(ProjectPhase::getProjectId, leafIds)
                        .eq(ProjectPhase::getStatus, "IN_PROGRESS")
                        .orderByAsc(ProjectPhase::getPlanFinishDate))
                .stream()
                .map(ph -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", ph.getProjectId());
                    row.put("projectName", nameOf.getOrDefault(ph.getProjectId(), "项目已删除"));
                    row.put("phaseName", ph.getPhaseName());
                    row.put("percent", ph.getPercent());
                    row.put("planFinishDate", ph.getPlanFinishDate());
                    row.put("overdue", ph.getPlanFinishDate() != null && ph.getPlanFinishDate().isBefore(today));
                    return row;
                }).toList();

        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (Project p : leaves) {
            for (ProjectPhase ph : c.phasesByProject().getOrDefault(p.getId(), List.of())) {
                if (ph.getPlanFinishDate() == null) continue;
                boolean acceptance = ph.getPhaseName().contains("初验") || ph.getPhaseName().contains("终验");
                boolean within = !ph.getPlanFinishDate().isBefore(today) && !ph.getPlanFinishDate().isAfter(soon);
                if (acceptance && within) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", p.getId());
                    row.put("projectName", p.getName());
                    row.put("phaseName", ph.getPhaseName());
                    row.put("planDate", ph.getPlanFinishDate());
                    row.put("status", ph.getStatus());
                    upcoming.add(row);
                }
            }
        }
        upcoming.sort(Comparator.comparing(r -> (LocalDate) r.get("planDate")));

        List<Map<String, Object>> overdueList = new ArrayList<>();
        for (Long pid : c.overdueProjectIds()) {
            String name = nameOf.getOrDefault(pid, "项目已删除");
            for (ProjectPhase ph : c.phasesByProject().getOrDefault(pid, List.of())) {
                if (ph.getPlanFinishDate() == null) continue;
                boolean open = ph.getStatus().equals("IN_PROGRESS") || ph.getStatus().equals("NOT_STARTED");
                if (open && ph.getPlanFinishDate().isBefore(today)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", pid);
                    row.put("projectName", name);
                    row.put("phaseName", ph.getPhaseName());
                    row.put("planDate", ph.getPlanFinishDate());
                    row.put("days", (int) (today.toEpochDay() - ph.getPlanFinishDate().toEpochDay()));
                    overdueList.add(row);
                }
            }
        }
        overdueList.sort(Comparator.comparing(r -> (Integer) r.get("days"), Comparator.reverseOrder()));

        List<Map<String, Object>> recent = leaves.stream()
                .sorted(Comparator.comparing(Project::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .map(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", p.getId());
                    row.put("code", p.getCode());
                    row.put("name", p.getName());
                    row.put("status", p.getStatus());
                    row.put("currentPhaseName", currentPhaseName(c.phasesByProject().getOrDefault(p.getId(), List.of())));
                    row.put("updateTime", p.getUpdateTime());
                    return row;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("myTodos", myTodos);
        out.put("upcoming", upcoming.stream().limit(8).toList());
        out.put("overdue", overdueList.stream().limit(8).toList());
        out.put("recent", recent);
        return out;
    }

    // ==================== 内部实现 ====================

    private record Common(Map<Long, List<ProjectPhase>> phasesByProject,
                           BigDecimal paidTotal,
                           List<Contract> contracts,
                           Set<Long> overdueProjectIds) {
    }

    private List<Project> loadProjects(StatsQuery q) {
        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<>();
        if (q != null) {
            if (q.getYear() != null) {
                qw.apply("YEAR(approve_date) = {0}", q.getYear());
            }
            if (StringUtils.hasText(q.getType())) qw.eq(Project::getType, q.getType());
            if (StringUtils.hasText(q.getStatus())) qw.eq(Project::getStatus, q.getStatus());
            if (StringUtils.hasText(q.getOwnerUnit())) qw.eq(Project::getOwnerUnit, q.getOwnerUnit());
            if (q.getManagerUserId() != null) qw.eq(Project::getManagerUserId, q.getManagerUserId());
        }
        return projectMapper.selectList(qw.orderByAsc(Project::getId));
    }

    /** 核算单元：子项目 或 无子项目的顶层项目 */
    private List<Project> leaves(List<Project> all) {
        return all.stream().filter(p -> p.getParentId() != null
                || all.stream().noneMatch(o -> p.getId().equals(o.getParentId()))).toList();
    }

    private Common commonOf(List<Project> projects, List<Project> leaves) {
        List<Long> leafIds = leaves.stream().map(Project::getId).toList();
        Map<Long, List<ProjectPhase>> byProject = new HashMap<>();
        Set<Long> overdueProjects = new java.util.HashSet<>();
        LocalDate today = LocalDate.now();
        if (!leafIds.isEmpty()) {
            List<ProjectPhase> phases = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                    .in(ProjectPhase::getProjectId, leafIds)
                    .orderByAsc(ProjectPhase::getSortNo));
            byProject = phases.stream().collect(Collectors.groupingBy(ProjectPhase::getProjectId));
            for (Map.Entry<Long, List<ProjectPhase>> e : byProject.entrySet()) {
                for (ProjectPhase ph : e.getValue()) {
                    boolean open = ph.getStatus().equals("IN_PROGRESS") || ph.getStatus().equals("NOT_STARTED");
                    if (open && ph.getPlanFinishDate() != null && ph.getPlanFinishDate().isBefore(today)) {
                        overdueProjects.add(e.getKey());
                    }
                }
            }
        }

        BigDecimal paidTotal = BigDecimal.ZERO;
        if (!leafIds.isEmpty()) {
            List<Long> contractIds = projects.stream().map(Project::getContractId).filter(Objects::nonNull).distinct().toList();
            LambdaQueryWrapper<Payment> qw = new LambdaQueryWrapper<>();
            qw.and(w -> {
                w.in(Payment::getProjectId, leafIds);
                if (!contractIds.isEmpty()) w.or(o -> o.in(Payment::getContractId, contractIds));
            });
            paidTotal = paymentMapper.selectList(qw).stream()
                    .map(pay -> zero(pay.getPaidAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>().orderByAsc(Contract::getId));
        return new Common(byProject, paidTotal, contracts, overdueProjects);
    }

    private static long countBy(List<Project> projects, String status) {
        return projects.stream().filter(p -> status.equals(p.getStatus())).count();
    }

    private static List<Map<String, Object>> countGroup(List<Project> projects,
                                                        Function<Project, String> getter,
                                                        List<String> order) {
        Map<String, Long> raw = projects.stream().collect(Collectors.groupingBy(getter, Collectors.counting()));
        return order.stream().filter(raw::containsKey).map(k -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", k);
            item.put("value", raw.get(k));
            return item;
        }).toList();
    }

    private static BigDecimal sum(Function<Project, BigDecimal> getter, List<Project> projects) {
        return projects.stream().map(p -> zero(getter.apply(p))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String currentPhaseName(List<ProjectPhase> phases) {
        for (ProjectPhase p : phases) {
            if ("NOT_STARTED".equals(p.getStatus()) || "IN_PROGRESS".equals(p.getStatus())) {
                return p.getPhaseName();
            }
        }
        return phases.isEmpty() ? null : "已完结";
    }
}
