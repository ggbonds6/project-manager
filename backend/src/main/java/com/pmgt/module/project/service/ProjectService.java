package com.pmgt.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.AuthContext;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.dto.PhaseUpdateRequest;
import com.pmgt.module.project.dto.PhaseVO;
import com.pmgt.module.project.dto.ProjectDetailVO;
import com.pmgt.module.project.dto.ProjectQuery;
import com.pmgt.module.project.dto.ProjectSaveRequest;
import com.pmgt.module.project.dto.ProjectVO;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectPhase;
import com.pmgt.module.project.entity.Payment;
import com.pmgt.module.project.mapper.ProjectMapper;
import com.pmgt.module.project.mapper.ProjectPhaseMapper;
import com.pmgt.module.project.mapper.PaymentMapper;
import com.pmgt.module.system.entity.PhaseTemplate;
import com.pmgt.module.system.entity.SysDept;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.PhaseTemplateMapper;
import com.pmgt.module.system.mapper.SysDeptMapper;
import com.pmgt.module.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Set<String> PHASE_STATUS = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "SKIPPED");
    private static final Set<String> PROJECT_STATUS = Set.of("RUN", "DONE", "PAUSE", "STOP");
    private static final Set<String> TYPES = Set.of("HW", "SW");

    private final ProjectMapper projectMapper;
    private final ProjectPhaseMapper phaseMapper;
    private final PaymentMapper paymentMapper;
    private final PhaseTemplateMapper templateMapper;
    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public ProjectService(ProjectMapper projectMapper,
                          ProjectPhaseMapper phaseMapper,
                          PaymentMapper paymentMapper,
                          PhaseTemplateMapper templateMapper,
                          SysUserMapper userMapper,
                          SysDeptMapper deptMapper,
                          OperationLogService operationLogService,
                          ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.phaseMapper = phaseMapper;
        this.paymentMapper = paymentMapper;
        this.templateMapper = templateMapper;
        this.userMapper = userMapper;
        this.deptMapper = deptMapper;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    public Page<ProjectVO> page(ProjectQuery q) {
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null ? 20 : Math.min(Math.max(q.getSize(), 1), 100);

        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>()
                .orderByDesc(Project::getUpdateTime);
        if (StringUtils.hasText(q.getKeyword())) {
            String kw = q.getKeyword().trim();
            qw.and(w -> w.like(Project::getName, kw)
                    .or().like(Project::getCode, kw)
                    .or().like(Project::getVendorName, kw));
        }
        if (StringUtils.hasText(q.getType())) {
            qw.eq(Project::getType, q.getType());
        }
        if (StringUtils.hasText(q.getStatus())) {
            qw.eq(Project::getStatus, q.getStatus());
        }
        if (StringUtils.hasText(q.getOwnerUnit())) {
            qw.eq(Project::getOwnerUnit, q.getOwnerUnit());
        }
        if (q.getManagerUserId() != null) {
            qw.eq(Project::getManagerUserId, q.getManagerUserId());
        }
        if (q.getYear() != null) {
            qw.apply("YEAR(approve_date) = {0}", q.getYear());
        }

        Page<Project> p = projectMapper.selectPage(new Page<>(page, size), qw);
        Page<ProjectVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());

        List<Project> records = p.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Map<Long, String> userNames = userNameMap(records.stream().map(Project::getManagerUserId).collect(Collectors.toSet()));
        Map<Long, String> deptNames = deptNameMap(records.stream().map(Project::getOwnerDeptId).collect(Collectors.toSet()));
        Map<Long, List<ProjectPhase>> phasesByProject = phasesByProjects(records.stream().map(Project::getId).toList());
        Map<Long, BigDecimal> paidByProject = paidByProjects(records.stream().map(Project::getId).toList());

        List<ProjectVO> vos = records.stream().map(pj -> {
            ProjectVO vo = new ProjectVO();
            vo.setId(pj.getId());
            vo.setCode(pj.getCode());
            vo.setName(pj.getName());
            vo.setType(pj.getType());
            vo.setStatus(pj.getStatus());
            vo.setOwnerUnit(pj.getOwnerUnit());
            vo.setOwnerDeptId(pj.getOwnerDeptId());
            vo.setOwnerDeptName(optionalName(deptNames, pj.getOwnerDeptId()));
            vo.setManagerUserId(pj.getManagerUserId());
            vo.setManagerName(optionalName(userNames, pj.getManagerUserId()));
            vo.setVendorName(pj.getVendorName());
            vo.setBudgetAmount(pj.getBudgetAmount());
            vo.setContractAmount(pj.getContractAmount());
            vo.setApproveDate(pj.getApproveDate());
            vo.setPlanFinishDate(pj.getPlanFinishDate());
            vo.setActualFinishDate(pj.getActualFinishDate());
            vo.setUpdateTime(pj.getUpdateTime());
            List<ProjectPhase> phases = phasesByProject.getOrDefault(pj.getId(), List.of());
            vo.setCurrentPhaseName(currentPhaseName(phases));
            vo.setOverallProgress(overallProgress(phases));
            vo.setPaidAmount(paidByProject.getOrDefault(pj.getId(), BigDecimal.ZERO));
            return vo;
        }).toList();
        voPage.setRecords(vos);
        return voPage;
    }

    public ProjectDetailVO detail(Long id) {
        Project pj = projectMapper.selectById(id);
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        ProjectDetailVO vo = new ProjectDetailVO();
        vo.setId(pj.getId());
        vo.setCode(pj.getCode());
        vo.setName(pj.getName());
        vo.setType(pj.getType());
        vo.setStatus(pj.getStatus());
        vo.setOwnerUnit(pj.getOwnerUnit());
        vo.setOwnerDeptId(pj.getOwnerDeptId());
        vo.setManagerUserId(pj.getManagerUserId());
        vo.setVendorName(pj.getVendorName());
        vo.setVendorContact(pj.getVendorContact());
        vo.setApproveNo(pj.getApproveNo());
        vo.setBudgetAmount(pj.getBudgetAmount());
        vo.setFundSource(pj.getFundSource());
        vo.setBidType(pj.getBidType());
        vo.setBidAmount(pj.getBidAmount());
        vo.setContractNo(pj.getContractNo());
        vo.setContractAmount(pj.getContractAmount());
        vo.setChangeAmount(pj.getChangeAmount() == null ? BigDecimal.ZERO : pj.getChangeAmount());
        vo.setContractTotal(contractTotal(pj));
        vo.setApproveDate(pj.getApproveDate());
        vo.setPlanStartDate(pj.getPlanStartDate());
        vo.setPlanFinishDate(pj.getPlanFinishDate());
        vo.setActualFinishDate(pj.getActualFinishDate());
        vo.setContentSummary(pj.getContentSummary());
        vo.setProjectSource(pj.getProjectSource());
        vo.setRemark(pj.getRemark());
        vo.setCreateTime(pj.getCreateTime());
        vo.setUpdateTime(pj.getUpdateTime());

        Map<Long, String> userNames = userNameMap(ids(pj.getManagerUserId()));
        vo.setManagerName(optionalName(userNames, pj.getManagerUserId()));
        Map<Long, String> deptNames = deptNameMap(ids(pj.getOwnerDeptId()));
        vo.setOwnerDeptName(optionalName(deptNames, pj.getOwnerDeptId()));

        List<Long> memberIds = parseMemberIds(pj.getMemberIds());
        vo.setMemberIds(memberIds);
        if (!memberIds.isEmpty()) {
            Map<Long, String> memberMap = userNameMap(memberIds.stream().collect(Collectors.toSet()));
            vo.setMemberNames(memberIds.stream().map(mid -> memberMap.getOrDefault(mid, "")).filter(StringUtils::hasText).toList());
        } else {
            vo.setMemberNames(List.of());
        }

        List<ProjectPhase> phases = phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                .eq(ProjectPhase::getProjectId, id)
                .orderByAsc(ProjectPhase::getSortNo));
        vo.setPhases(phases.stream().map(this::toPhaseVO).toList());
        vo.setCurrentPhaseName(currentPhaseName(phases));
        vo.setOverallProgress(overallProgress(phases));
        return vo;
    }

    // ==================== 写操作 ====================

    @Transactional
    public Long create(ProjectSaveRequest req) {
        validateSave(req, null);
        Project pj = new Project();
        applySave(pj, req);
        pj.setStatus(StringUtils.hasText(req.getStatus()) ? req.getStatus() : "RUN");
        if (!StringUtils.hasText(pj.getCode())) {
            pj.setCode(nextCode(pj.getType()));
        }
        pj.setCreateBy(AuthContext.userId().orElse(null));
        projectMapper.insert(pj);

        // 按模板生成阶段实例
        List<PhaseTemplate> templates = templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                .eq(PhaseTemplate::getProjectType, pj.getType())
                .orderByAsc(PhaseTemplate::getSortNo));
        if (templates.isEmpty()) {
            throw new BizException(400, "未配置项目类型对应的阶段模板");
        }
        for (PhaseTemplate t : templates) {
            ProjectPhase ph = new ProjectPhase();
            ph.setProjectId(pj.getId());
            ph.setPhaseName(t.getPhaseName());
            ph.setSortNo(t.getSortNo());
            ph.setWeight(t.getWeight());
            ph.setPayNode(t.getPayNode());
            ph.setStatus("NOT_STARTED");
            ph.setPercent(0);
            ph.setManagerUserId(pj.getManagerUserId());
            phaseMapper.insert(ph);
        }
        operationLogService.log("PROJECT", pj.getId(), "CREATE",
                "新建项目「" + pj.getName() + "」(" + pj.getCode() + ")，按" + pj.getType() + "模板生成" + templates.size() + "个阶段");
        return pj.getId();
    }

    @Transactional
    public void update(Long id, ProjectSaveRequest req) {
        Project exist = projectMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "项目不存在");
        }
        validateSave(req, id);
        // code 不允许通过更新接口修改（保持稳定），如传入且与现有一致则忽略
        applySave(exist, req);
        if (StringUtils.hasText(req.getStatus())) {
            exist.setStatus(req.getStatus());
        }
        projectMapper.updateById(exist);
        operationLogService.log("PROJECT", id, "UPDATE", "更新项目基本信息「" + exist.getName() + "」");
    }

    @Transactional
    public void delete(Long id) {
        Project exist = projectMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "项目不存在");
        }
        // 逻辑删除项目及其阶段（附件/付款由各自逻辑保留，便于审计追溯）
        projectMapper.deleteById(id);
        phaseMapper.delete(new LambdaQueryWrapper<ProjectPhase>().eq(ProjectPhase::getProjectId, id));
        operationLogService.log("PROJECT", id, "DELETE", "删除项目「" + exist.getName() + "」(" + exist.getCode() + ")");
    }

    @Transactional
    public void updatePhase(Long projectId, Long phaseId, PhaseUpdateRequest req) {
        Project pj = projectMapper.selectById(projectId);
        if (pj == null) {
            throw new BizException(404, "项目不存在");
        }
        ProjectPhase ph = phaseMapper.selectById(phaseId);
        if (ph == null || !ph.getProjectId().equals(projectId)) {
            throw new BizException(404, "阶段不存在");
        }

        if (StringUtils.hasText(req.getStatus())) {
            String status = req.getStatus();
            if (!PHASE_STATUS.contains(status)) {
                throw new BizException(400, "非法阶段状态: " + status);
            }
            ph.setStatus(status);
            LocalDate today = LocalDate.now();
            if ("IN_PROGRESS".equals(status) && ph.getActualStartDate() == null) {
                ph.setActualStartDate(today);
            }
            if ("DONE".equals(status)) {
                ph.setPercent(100);
                if (ph.getActualFinishDate() == null) {
                    ph.setActualFinishDate(today);
                }
            }
            if ("NOT_STARTED".equals(status)) {
                ph.setActualStartDate(null);
                ph.setActualFinishDate(null);
            }
            if ("SKIPPED".equals(status)) {
                ph.setPercent(0);
                ph.setActualFinishDate(today);
            }
        }
        if (req.getPercent() != null) {
            int pct = req.getPercent();
            if (pct < 0 || pct > 100) {
                throw new BizException(400, "完成比例须在 0-100");
            }
            ph.setPercent(pct);
        }
        if (req.getPlanStartDate() != null) {
            ph.setPlanStartDate(req.getPlanStartDate());
        }
        if (req.getPlanFinishDate() != null) {
            ph.setPlanFinishDate(req.getPlanFinishDate());
        }
        if (req.getActualStartDate() != null) {
            ph.setActualStartDate(req.getActualStartDate());
        }
        if (req.getActualFinishDate() != null) {
            ph.setActualFinishDate(req.getActualFinishDate());
        }
        if (req.getManagerUserId() != null) {
            ph.setManagerUserId(req.getManagerUserId());
        }
        if (req.getNote() != null) {
            ph.setNote(req.getNote());
        }
        if (req.getResultFields() != null) {
            try {
                ph.setResultFields(objectMapper.writeValueAsString(req.getResultFields()));
            } catch (Exception e) {
                throw new BizException(400, "结果字段序列化失败");
            }
        }
        if (ph.getActualStartDate() != null && ph.getActualFinishDate() != null
                && ph.getActualFinishDate().isBefore(ph.getActualStartDate())) {
            throw new BizException(400, "实际完成日期不能早于实际开始日期");
        }
        phaseMapper.updateById(ph);
        operationLogService.log("PROJECT", projectId, "UPDATE_PHASE",
                "阶段「" + ph.getPhaseName() + "」更新为 " + ph.getStatus() + " (进度 " + ph.getPercent() + "%)");
    }

    // ==================== 内部工具 ====================

    private void validateSave(ProjectSaveRequest req, Long selfId) {
        String type = req.getType();
        if (!StringUtils.hasText(type) || !TYPES.contains(type)) {
            throw new BizException(400, "项目类型仅支持 HW(硬件)/SW(软件)");
        }
        if (StringUtils.hasText(req.getCode())) {
            boolean dup = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .eq(Project::getCode, req.getCode())
                    .ne(selfId != null, Project::getId, selfId)) > 0;
            if (dup) {
                throw new BizException(400, "项目编号已存在: " + req.getCode());
            }
        }
        if (req.getBudgetAmount() != null && req.getBudgetAmount().signum() < 0) {
            throw new BizException(400, "预算金额不能为负");
        }
        if (req.getPlanStartDate() != null && req.getPlanFinishDate() != null
                && req.getPlanFinishDate().isBefore(req.getPlanStartDate())) {
            throw new BizException(400, "计划完成日期不能早于计划开始日期");
        }
    }

    private void applySave(Project pj, ProjectSaveRequest req) {
        pj.setCode(req.getCode());
        pj.setName(req.getName());
        pj.setType(req.getType());
        pj.setOwnerUnit(req.getOwnerUnit());
        pj.setOwnerDeptId(req.getOwnerDeptId());
        pj.setManagerUserId(req.getManagerUserId());
        pj.setMemberIds(req.getMemberIds() == null ? null
                : req.getMemberIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        pj.setVendorName(req.getVendorName());
        pj.setVendorContact(req.getVendorContact());
        pj.setApproveNo(req.getApproveNo());
        pj.setBudgetAmount(req.getBudgetAmount());
        pj.setFundSource(req.getFundSource());
        pj.setBidType(req.getBidType());
        pj.setBidAmount(req.getBidAmount());
        pj.setContractNo(req.getContractNo());
        pj.setContractAmount(req.getContractAmount());
        pj.setChangeAmount(req.getChangeAmount() == null ? BigDecimal.ZERO : req.getChangeAmount());
        pj.setApproveDate(req.getApproveDate());
        pj.setPlanStartDate(req.getPlanStartDate());
        pj.setPlanFinishDate(req.getPlanFinishDate());
        pj.setContentSummary(req.getContentSummary());
        pj.setProjectSource(req.getProjectSource());
        pj.setRemark(req.getRemark());
    }

    private String nextCode(String type) {
        String prefix = "SW".equals(type) ? "RJ" : "YJ";
        int year = LocalDate.now().getYear();
        String like = prefix + "-" + year + "-";
        // 物理计数（含逻辑删除行），避免与已删除记录的唯一编号冲突
        Long count = projectMapper.countAllByCodeLike(like);
        for (int i = 1; i <= 100; i++) {
            String code = String.format("%s-%d-%03d", prefix, year, count + i);
            if (projectMapper.selectCount(new LambdaQueryWrapper<Project>().eq(Project::getCode, code)) == 0) {
                return code;
            }
        }
        throw new BizException(500, "项目编号生成失败，请手动指定编号");
    }

    private BigDecimal contractTotal(Project pj) {
        BigDecimal base = pj.getContractAmount() == null ? BigDecimal.ZERO : pj.getContractAmount();
        BigDecimal change = pj.getChangeAmount() == null ? BigDecimal.ZERO : pj.getChangeAmount();
        return base.add(change);
    }

    private List<Long> parseMemberIds(String memberIds) {
        if (!StringUtils.hasText(memberIds)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String s : memberIds.split(",")) {
            try {
                ids.add(Long.valueOf(s.trim()));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return ids;
    }

    private Map<Long, List<ProjectPhase>> phasesByProjects(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return phaseMapper.selectList(new LambdaQueryWrapper<ProjectPhase>()
                        .in(ProjectPhase::getProjectId, projectIds)
                        .orderByAsc(ProjectPhase::getSortNo))
                .stream()
                .collect(Collectors.groupingBy(ProjectPhase::getProjectId));
    }

    private Map<Long, BigDecimal> paidByProjects(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<Payment> payments = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .in(Payment::getProjectId, projectIds));
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Payment pay : payments) {
            BigDecimal paid = pay.getPaidAmount() == null ? BigDecimal.ZERO : pay.getPaidAmount();
            map.merge(pay.getProjectId(), paid, BigDecimal::add);
        }
        return map;
    }

    private Set<Long> ids(Long... values) {
        Set<Long> set = new HashSet<>();
        for (Long v : values) {
            if (v != null) {
                set.add(v);
            }
        }
        return set;
    }

    private Map<Long, String> userNameMap(Set<Long> rawIds) {
        Set<Long> ids = ids(rawIds == null ? null : rawIds.toArray(new Long[0]));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getName));
    }

    private Map<Long, String> deptNameMap(Set<Long> rawIds) {
        Set<Long> ids = ids(rawIds == null ? null : rawIds.toArray(new Long[0]));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return deptMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName));
    }

    private String optionalName(Map<Long, String> map, Long id) {
        return id == null ? null : map.get(id);
    }

    /** 整体进度 = Σ(已完成权重×100 + 进行中权重×比例) / Σ(未跳过权重)，取整 */
    private Integer overallProgress(List<ProjectPhase> phases) {
        int total = 0;
        int got = 0;
        for (ProjectPhase p : phases) {
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

    /** 当前阶段：第一个进行中或未开始的阶段；全部完成/跳过则返回空 */
    private String currentPhaseName(List<ProjectPhase> phases) {
        for (ProjectPhase p : phases) {
            if ("NOT_STARTED".equals(p.getStatus()) || "IN_PROGRESS".equals(p.getStatus())) {
                return p.getPhaseName();
            }
        }
        return phases.isEmpty() ? null : "已完结";
    }

    private PhaseVO toPhaseVO(ProjectPhase ph) {
        PhaseVO vo = new PhaseVO();
        vo.setId(ph.getId());
        vo.setProjectId(ph.getProjectId());
        vo.setPhaseName(ph.getPhaseName());
        vo.setSortNo(ph.getSortNo());
        vo.setWeight(ph.getWeight());
        vo.setPayNode(ph.getPayNode());
        vo.setStatus(ph.getStatus());
        vo.setPercent(ph.getPercent());
        vo.setPlanStartDate(ph.getPlanStartDate());
        vo.setPlanFinishDate(ph.getPlanFinishDate());
        vo.setActualStartDate(ph.getActualStartDate());
        vo.setActualFinishDate(ph.getActualFinishDate());
        vo.setManagerUserId(ph.getManagerUserId());
        if (ph.getManagerUserId() != null) {
            SysUser u = userMapper.selectById(ph.getManagerUserId());
            vo.setManagerName(u == null ? null : u.getName());
        }
        vo.setNote(ph.getNote());
        vo.setUpdateTime(ph.getUpdateTime());
        if (StringUtils.hasText(ph.getResultFields())) {
            try {
                vo.setResultFields(objectMapper.readValue(ph.getResultFields(), new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                vo.setResultFields(ph.getResultFields());
            }
        }
        return vo;
    }
}
