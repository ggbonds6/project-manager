package com.pmgt.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作台 / 项目统计 聚合服务。
 * 数据规模为内部管理系统量级（百~千级），直接在内存聚合，避免复杂 SQL。
 */
@Service
public class StatsService {

    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;

    public StatsService(ProjectMapper projectMapper,
                        ProjectPhaseMapper phaseMapper,
                        PaymentMapper paymentMapper) {
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
    }

    // ==================== 对外聚合 ====================

    /** 概览卡：项目数与资金口径 */
    public Map<String, Object> summary(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        Common c = commonOf(projects);
        Map<String, Object> m = new LinkedHashMap<>();
        int nowYear = LocalDate.now().getYear();
        m.put("projectTotal", projects.size());
        m.put("running", countBy(projects, "RUN"));
        m.put("done", countBy(projects, "DONE"));
        m.put("pause", countBy(projects, "PAUSE"));
        m.put("stop", countBy(projects, "STOP"));
        m.put("newThisYear", (int) projects.stream().filter(p -> {
            if (p.getApproveDate() != null) return p.getApproveDate().getYear() == nowYear;
            return p.getCreateTime() != null && p.getCreateTime().getYear() == nowYear;
        }).count());
        m.put("overdueCount", c.overdueProjectIds().size());
        m.put("budgetTotal", sum(p -> p.getBudgetAmount(), projects));
        m.put("contractTotal", sum(p -> p.getContractAmount(), projects));
        BigDecimal paid = c.paidMap().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        m.put("paidTotal", paid);
        BigDecimal contract = asZero(m.get("contractTotal"));
        m.put("execRate", contract.signum() == 0 ? 0 : paid.multiply(BigDecimal.valueOf(100)).divide(contract, 1, RoundingMode.HALF_UP));
        return m;
    }

    /** 构成分布：状态 / 类型 / 流程阶段漏斗 */
    public Map<String, Object> distributions(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        Common c = commonOf(projects);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", countGroup(projects, Project::getStatus,
                List.of("RUN", "DONE", "PAUSE", "STOP")));
        m.put("type", countGroup(projects, Project::getType, List.of("HW", "SW")));
        // 流程漏斗：每项目仅计入当前阶段一次
        Map<String, Long> funnel = new LinkedHashMap<>();
        for (Project p : projects) {
            List<ProjectPhase> phases = c.phasesByProject().getOrDefault(p.getId(), List.of());
            String cur = currentPhaseName(phases);
            String label = cur == null ? "已完结" : cur;
            funnel.merge(label, 1L, Long::sum);
        }
        List<Map<String, Object>> funnelList = funnel.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue());
                    return item;
                })
                .toList();
        m.put("funnel", funnelList);
        m.put("overdueProjects", c.overdueProjectIds().size());
        return m;
    }

    /** 年度资金对比：按立项(批复)年度 预算 vs 合同 vs 实付 */
    public Map<String, Object> yearMoney(StatsQuery q) {
        List<Project> projects = loadProjects(q);
        Common c = commonOf(projects);
        Map<Integer, BigDecimal[]> acc = new LinkedHashMap<>();
        for (Project p : projects) {
            int y = p.getApproveDate() != null ? p.getApproveDate().getYear() : Year.now().getValue();
            BigDecimal[] v = acc.computeIfAbsent(y, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(zero(p.getBudgetAmount()));
            v[1] = v[1].add(zero(p.getContractAmount()));
            v[2] = v[2].add(zero(c.paidMap().get(p.getId())));
        }
        List<Integer> years = acc.keySet().stream().sorted().toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int y : years) {
            BigDecimal[] v = acc.get(y);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", y);
            row.put("budget", v[0]);
            row.put("contract", v[1]);
            row.put("paid", v[2]);
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        return m;
    }

    // ==================== 工作台 ====================

    public Map<String, Object> dashboard(Long userId) {
        List<Project> projects = loadProjects(null);
        Common c = commonOf(projects);
        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(60);

        Map<String, Object> cards = summary(null);
        List<ProjectPhase> myPhases = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                .eq(ProjectPhase::getManagerUserId, userId)
                .eq(ProjectPhase::getStatus, "IN_PROGRESS")
                .orderByAsc(ProjectPhase::getPlanFinishDate));
        List<Map<String, Object>> myTodos = myPhases.stream().map(ph -> {
            Project pj = projects.stream().filter(p -> p.getId().equals(ph.getProjectId())).findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", ph.getProjectId());
            row.put("projectName", pj == null ? "项目已删除" : pj.getName());
            row.put("phaseName", ph.getPhaseName());
            row.put("percent", ph.getPercent());
            row.put("planFinishDate", ph.getPlanFinishDate());
            row.put("overdue", ph.getPlanFinishDate() != null && ph.getPlanFinishDate().isBefore(today));
            return row;
        }).toList();

        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (Project p : projects) {
            List<ProjectPhase> phases = c.phasesByProject().getOrDefault(p.getId(), List.of());
            for (ProjectPhase ph : phases) {
                if (ph.getPlanFinishDate() == null) continue;
                String nm = ph.getPhaseName();
                boolean acceptance = nm.contains("初验") || nm.contains("终验");
                boolean within = !ph.getPlanFinishDate().isBefore(today) && !ph.getPlanFinishDate().isAfter(soon);
                if (acceptance && within) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", p.getId());
                    row.put("projectName", p.getName());
                    row.put("phaseName", nm);
                    row.put("planDate", ph.getPlanFinishDate());
                    row.put("status", ph.getStatus());
                    upcoming.add(row);
                }
            }
        }
        upcoming.sort(Comparator.comparing(r -> (LocalDate) r.get("planDate")));

        List<Map<String, Object>> overdueList = new ArrayList<>();
        for (Map.Entry<Long, List<ProjectPhase>> e : c.phasesByProject().entrySet()) {
            Project pj = projects.stream().filter(p -> p.getId().equals(e.getKey())).findFirst().orElse(null);
            if (pj == null) continue;
            for (ProjectPhase ph : e.getValue()) {
                if (ph.getPlanFinishDate() == null) continue;
                boolean open = ph.getStatus().equals("IN_PROGRESS") || ph.getStatus().equals("NOT_STARTED");
                if (open && ph.getPlanFinishDate().isBefore(today)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", pj.getId());
                    row.put("projectName", pj.getName());
                    row.put("phaseName", ph.getPhaseName());
                    row.put("planDate", ph.getPlanFinishDate());
                    row.put("days", (int) (today.toEpochDay() - ph.getPlanFinishDate().toEpochDay()));
                    overdueList.add(row);
                }
            }
        }
        overdueList.sort(Comparator.comparing(r -> (Integer) r.get("days"), Comparator.reverseOrder()));

        List<Map<String, Object>> recent = projects.stream()
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
                           Map<Long, BigDecimal> paidMap,
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

    private Common commonOf(List<Project> projects) {
        List<Long> ids = projects.stream().map(Project::getId).toList();
        Map<Long, List<ProjectPhase>> byProject = new HashMap<>();
        Set<Long> overdueProjects = new java.util.HashSet<>();
        LocalDate today = LocalDate.now();
        if (!ids.isEmpty()) {
            List<ProjectPhase> phases = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                    .in(ProjectPhase::getProjectId, ids)
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
        Map<Long, BigDecimal> paidMap = paidByProjects(ids);
        return new Common(byProject, paidMap, overdueProjects);
    }

    private Map<Long, BigDecimal> paidByProjects(List<Long> projectIds) {
        if (projectIds.isEmpty()) return Map.of();
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Payment pay : paymentMapper.selectList(new LambdaQueryWrapper<Payment>().in(Payment::getProjectId, projectIds))) {
            map.merge(pay.getProjectId(), zero(pay.getPaidAmount()), BigDecimal::add);
        }
        return map;
    }

    private static long countBy(List<Project> projects, String status) {
        return projects.stream().filter(p -> status.equals(p.getStatus())).count();
    }

    private static List<Map<String, Object>> countGroup(List<Project> projects,
                                                        java.util.function.Function<Project, String> getter,
                                                        List<String> order) {
        Map<String, Long> raw = projects.stream().collect(Collectors.groupingBy(getter, Collectors.counting()));
        return order.stream().filter(raw::containsKey).map(k -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", k);
            item.put("value", raw.get(k));
            return item;
        }).toList();
    }

    private static BigDecimal sum(java.util.function.Function<Project, BigDecimal> getter, List<Project> projects) {
        return projects.stream().map(p -> zero(getter.apply(p))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal asZero(Object v) {
        return v instanceof BigDecimal b ? b : BigDecimal.ZERO;
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
