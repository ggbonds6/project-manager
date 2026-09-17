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
import com.pmgt.module.project.service.ContractLinkService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合同管理（金额按合同由管理员录入）。
 *
 * <p>关联模型（V12 起）：一个（子）项目可签**多份合同**（施工主合同 / 监理服务 /
 * 第三方测评 / 预算编制 / 方案评估…），由 {@code project_contract} 关联表承载；
 * 一份合同仍只挂一个（子）项目（子项目独立签订，同一供应商也分开登记）。
 * {@code project.contract_id} 由 {@link ContractLinkService} 自动维护为「主合同」指针。</p>
 */
@RestController
@RequestMapping("/api")
public class ContractController {

    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final ContractLinkService contractLinkService;
    private final OperationLogService operationLogService;

    public ContractController(ContractMapper contractMapper, ProjectMapper projectMapper,
                              ContractLinkService contractLinkService,
                              OperationLogService operationLogService) {
        this.contractMapper = contractMapper;
        this.projectMapper = projectMapper;
        this.contractLinkService = contractLinkService;
        this.operationLogService = operationLogService;
    }

    /** 某项目可见的合同（自身登记的多份 + 父级总项目共享的） */
    @GetMapping("/projects/{projectId}/contracts")
    public R<List<Contract>> listByProject(@PathVariable Long projectId) {
        Project cur = projectMapper.selectById(projectId);
        if (cur == null) {
            throw new BizException(404, "项目不存在");
        }
        Set<Long> contractIds = contractLinkService.visibleContractIds(projectId);
        if (contractIds.isEmpty()) {
            return R.ok(List.of());
        }
        // 主合同（MAIN）排最前，其余按 id——列表默认第一条即主合同
        List<Contract> list = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getId, contractIds));
        list.sort(java.util.Comparator
                .comparing((Contract c) -> "MAIN".equals(c.getContractType()) ? 0 : 1)
                .thenComparing(Contract::getId));
        return R.ok(list);
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
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long pid : contractLinkService.projectIdsOfContract(id)) {
            Project p = projectMapper.selectById(pid);
            if (p == null) {
                continue;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("code", p.getCode());
            rows.add(row);
        }
        return R.ok(rows);
    }

    /** 业务规则（v1.4）：分子项目后每个（子）项目单独签订合同（同一供应商也各自合同）；
     *  合同仅与单个项目关联。一个项目可挂多份合同，但一份合同只挂一个项目。 */
    private void requireSingleProject(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new BizException(400, "合同必须关联一个项目");
        }
        if (projectIds.size() > 1) {
            throw new BizException(400, "合同仅关联单个（子）项目：子项目独立签订合同，请分别登记");
        }
    }

    @RequireRole({Role.ADMIN})
    @PostMapping("/contracts")
    public R<Long> create(@Valid @RequestBody ContractSaveRequest req) {
        requireSingleProject(req.getProjectIds());
        Contract c = new Contract();
        apply(c, req);
        contractMapper.insert(c);
        contractLinkService.replaceContractProjects(c.getId(), req.getProjectIds());
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
        requireSingleProject(req.getProjectIds());
        apply(exist, req);
        contractMapper.updateById(exist);
        contractLinkService.replaceContractProjects(id, req.getProjectIds());
        operationLogService.log("CONTRACT", id, "CONTRACT_UPDATE", "更新合同「" + exist.getName() + "」");
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/contracts/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Contract exist = contractMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "合同不存在");
        }
        contractLinkService.unlinkContract(id);
        contractMapper.deleteById(id);
        operationLogService.log("CONTRACT", id, "CONTRACT_DELETE", "删除合同「" + exist.getName() + "」");
        return R.ok();
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
        // ── V10：政府合同常见字段 ──
        c.setContractType(req.getContractType());
        c.setPartyA(req.getPartyA());
        c.setSignDate(req.getSignDate());
        c.setEffectiveDate(req.getEffectiveDate());
        c.setStartDate(req.getStartDate());
        c.setEndDate(req.getEndDate());
        c.setContractStatus(req.getContractStatus());
        c.setPayeeName(req.getPayeeName());
        c.setPayeeBank(req.getPayeeBank());
        c.setPayeeAccount(req.getPayeeAccount());
        c.setAcceptanceStandard(req.getAcceptanceStandard());
        c.setWarrantyMonths(req.getWarrantyMonths());
        c.setWarrantyAmount(req.getWarrantyAmount());
        c.setSettleAmount(req.getSettleAmount());
    }
}
