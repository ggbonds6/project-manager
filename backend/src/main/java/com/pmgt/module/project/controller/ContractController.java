package com.pmgt.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.dto.ContractSaveRequest;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合同管理（金额按合同由管理员录入）。
 * 一个合同可覆盖任意多个子项目：让这些子项目 project.contract_id 指向同一 contract。
 */
@RestController
@RequestMapping("/api")
public class ContractController {

    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final OperationLogService operationLogService;

    public ContractController(ContractMapper contractMapper, ProjectMapper projectMapper, OperationLogService operationLogService) {
        this.contractMapper = contractMapper;
        this.projectMapper = projectMapper;
        this.operationLogService = operationLogService;
    }

    /** 某项目可见的合同（含父级共享合同） */
    @GetMapping("/projects/{projectId}/contracts")
    public R<List<Contract>> listByProject(@PathVariable Long projectId) {
        Project cur = projectMapper.selectById(projectId);
        if (cur == null) {
            throw new BizException(404, "项目不存在");
        }
        Set<Long> contractIds = new LinkedHashSet<>();
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur.getContractId() != null) {
                contractIds.add(cur.getContractId());
            }
            cur = cur.getParentId() == null ? null : projectMapper.selectById(cur.getParentId());
        }
        if (contractIds.isEmpty()) {
            return R.ok(List.of());
        }
        return R.ok(contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getId, contractIds)));
    }

    @GetMapping("/contracts/{id}")
    public R<Contract> get(@PathVariable Long id) {
        Contract c = contractMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "合同不存在");
        }
        return R.ok(c);
    }

    /** 该合同覆盖的(子)项目（用于编辑回显/展示覆盖范围） */
    @GetMapping("/contracts/{id}/projects")
    public R<List<Map<String, Object>>> coveredProjects(@PathVariable Long id) {
        if (contractMapper.selectById(id) == null) {
            throw new BizException(404, "合同不存在");
        }
        List<Project> list = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getContractId, id)
                .orderByAsc(Project::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project p : list) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("code", p.getCode());
            rows.add(row);
        }
        return R.ok(rows);
    }

    @RequireRole({Role.ADMIN})
    @PostMapping("/contracts")
    public R<Long> create(@Valid @RequestBody ContractSaveRequest req) {
        Contract c = new Contract();
        apply(c, req);
        contractMapper.insert(c);
        assignProjects(c.getId(), req.getProjectIds(), null);
        operationLogService.log("CONTRACT", c.getId(), "CONTRACT_CREATE",
                "创建合同「" + c.getName() + "」金额 " + c.getContractAmount());
        return R.ok(c.getId());
    }

    @RequireRole({Role.ADMIN})
    @PutMapping("/contracts/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ContractSaveRequest req) {
        Contract exist = contractMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "合同不存在");
        }
        apply(exist, req);
        contractMapper.updateById(exist);
        assignProjects(id, req.getProjectIds(), null);
        operationLogService.log("CONTRACT", id, "CONTRACT_UPDATE", "更新合同「" + exist.getName() + "」及覆盖项目");
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/contracts/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Contract exist = contractMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "合同不存在");
        }
        assignProjects(id, List.of(), null);
        contractMapper.deleteById(id);
        operationLogService.log("CONTRACT", id, "CONTRACT_DELETE", "删除合同「" + exist.getName() + "」");
        return R.ok();
    }

    /** 将覆盖项目列表写入 project.contract_id（其余原覆盖项目解除） */
    private void assignProjects(Long contractId, List<Long> projectIds, Long unused) {
        // 解除原关联
        List<Project> old = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getContractId, contractId));
        Set<Long> keep = projectIds == null ? Set.of() : new LinkedHashSet<>(projectIds);
        for (Project p : old) {
            if (!keep.contains(p.getId())) {
                p.setContractId(null);
                projectMapper.updateById(p);
            }
        }
        if (projectIds == null) {
            return;
        }
        for (Long pid : projectIds) {
            Project p = projectMapper.selectById(pid);
            if (p == null) {
                throw new BizException(404, "覆盖项目不存在: " + pid);
            }
            p.setContractId(contractId);
            projectMapper.updateById(p);
        }
    }

    private void apply(Contract c, ContractSaveRequest req) {
        c.setName(req.getName());
        c.setContractNo(req.getContractNo());
        c.setVendorName(req.getVendorName());
        c.setVendorContact(req.getVendorContact());
        c.setBidType(req.getBidType());
        c.setBidAmount(req.getBidAmount());
        c.setContractAmount(req.getContractAmount());
        c.setChangeAmount(req.getChangeAmount() == null ? java.math.BigDecimal.ZERO : req.getChangeAmount());
        c.setPlanAmount(req.getPlanAmount());
        c.setScopeRemark(req.getScopeRemark());
        c.setRemark(req.getRemark());
    }

    private List<Long> normalize(List<Long> ids) {
        return ids == null ? new ArrayList<>() : ids;
    }
}
