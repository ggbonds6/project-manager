package com.pmgt.module.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.module.project.entity.Contract;
import com.pmgt.module.project.entity.Project;
import com.pmgt.module.project.entity.ProjectContract;
import com.pmgt.module.project.mapper.ContractMapper;
import com.pmgt.module.project.mapper.ProjectContractMapper;
import com.pmgt.module.project.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 项目 ↔ 合同 关联（V12）。
 *
 * <p>三件事集中在这里，避免各调用方各写一份：</p>
 * <ul>
 *   <li><b>查</b>：某项目（及父级）关联了哪些合同；某合同覆盖了哪些项目</li>
 *   <li><b>改</b>：保存合同时重建关联；删除合同时解除关联</li>
 *   <li><b>维护主合同指针</b>：把 {@code project.contract_id} 同步为 MAIN 类型合同
 *       （无 MAIN 取最早一份），使"项目卡片合同金额/统计"等既有口径继续表示主合同</li>
 * </ul>
 */
@Service
public class ContractLinkService {

    /** 沿项目向上追溯的层数上限（防脏数据造成死循环） */
    private static final int MAX_DEPTH = 8;

    private final ProjectContractMapper linkMapper;
    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;

    public ContractLinkService(ProjectContractMapper linkMapper,
                               ProjectMapper projectMapper,
                               ContractMapper contractMapper) {
        this.linkMapper = linkMapper;
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
    }

    /** 某项目自身关联的合同 id（不含父级） */
    public List<Long> contractIdsOfProject(Long projectId) {
        return linkMapper.selectList(new LambdaQueryWrapper<ProjectContract>()
                        .eq(ProjectContract::getProjectId, projectId))
                .stream().map(ProjectContract::getContractId).distinct().toList();
    }

    /**
     * 某项目**可见**的合同：自身 + 各级父项目共享的（总项目挂的合同对子项目可见）。
     * 与原有"沿 project.contract_id 向上收集"的行为保持一致的可见范围。
     */
    public Set<Long> visibleContractIds(Long projectId) {
        Set<Long> ids = new LinkedHashSet<>();
        Project cur = projectMapper.selectById(projectId);
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            ids.addAll(contractIdsOfProject(cur.getId()));
            cur = cur.getParentId() == null ? null : projectMapper.selectById(cur.getParentId());
        }
        return ids;
    }

    /** 某合同覆盖的项目 id（业务规则：一份合同只挂一个（子）项目） */
    public List<Long> projectIdsOfContract(Long contractId) {
        return linkMapper.selectList(new LambdaQueryWrapper<ProjectContract>()
                        .eq(ProjectContract::getContractId, contractId)
                        .orderByAsc(ProjectContract::getProjectId))
                .stream().map(ProjectContract::getProjectId).toList();
    }

    /** 重建某合同的关联（先解除旧关联，再写入新关联），并同步受影响项目的主合同指针 */
    public void replaceContractProjects(Long contractId, List<Long> projectIds) {
        List<Long> affected = new ArrayList<>(projectIdsOfContract(contractId));
        linkMapper.delete(new LambdaQueryWrapper<ProjectContract>()
                .eq(ProjectContract::getContractId, contractId));
        if (projectIds != null) {
            for (Long pid : projectIds) {
                ProjectContract link = new ProjectContract();
                link.setProjectId(pid);
                link.setContractId(contractId);
                link.setCreateTime(LocalDateTime.now());
                linkMapper.insert(link);
                if (!affected.contains(pid)) {
                    affected.add(pid);
                }
            }
        }
        affected.stream().filter(Objects::nonNull).distinct().forEach(this::syncPrimaryContract);
    }

    /** 解除某合同的所有关联 */
    public void unlinkContract(Long contractId) {
        List<Long> affected = projectIdsOfContract(contractId);
        linkMapper.delete(new LambdaQueryWrapper<ProjectContract>()
                .eq(ProjectContract::getContractId, contractId));
        affected.forEach(this::syncPrimaryContract);
    }

    /** 解除某项目的所有关联（项目删除时调用） */
    public void unlinkProject(Long projectId) {
        linkMapper.delete(new LambdaQueryWrapper<ProjectContract>()
                .eq(ProjectContract::getProjectId, projectId));
    }

    /**
     * 同步"主合同"指针：{@code project.contract_id} = 该项目关联的 MAIN 类型合同；
     * 没有 MAIN 时取最早关联的一份；都没有则置空。
     */
    public void syncPrimaryContract(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            return;
        }
        List<Long> ids = contractIdsOfProject(projectId);
        Long primary = null;
        if (!ids.isEmpty()) {
            List<Contract> cs = contractMapper.selectBatchIds(ids).stream()
                    .sorted(Comparator.comparing(Contract::getId))
                    .toList();
            primary = cs.stream()
                    .filter(c -> "MAIN".equals(c.getContractType()))
                    .map(Contract::getId)
                    .findFirst()
                    .orElseGet(() -> cs.isEmpty() ? null : cs.get(0).getId());
        }
        if (!Objects.equals(p.getContractId(), primary)) {
            p.setContractId(primary);
            projectMapper.updateById(p);
        }
        // 项目自身的编号/金额字段保持与主合同一致（既有展示口径依赖它们）
        if (primary != null) {
            Contract c = contractMapper.selectById(primary);
            if (c != null) {
                p.setContractNo(c.getContractNo());
                p.setContractAmount(c.getContractAmount());
                p.setChangeAmount(c.getChangeAmount());
                p.setVendorName(c.getVendorName());
                p.setVendorContact(c.getVendorContact());
                p.setBidType(c.getBidType());
                p.setBidAmount(c.getBidAmount());
                projectMapper.updateById(p);
            }
        }
    }

    /** 关联是否已存在 */
    public boolean isLinked(Long projectId, Long contractId) {
        return linkMapper.selectCount(new LambdaQueryWrapper<ProjectContract>()
                .eq(ProjectContract::getProjectId, projectId)
                .eq(ProjectContract::getContractId, contractId)) > 0;
    }

    /**
     * 全部被**在用项目**引用的合同 id（用于清理"没有任何在用项目引用"的孤儿合同）。
     *
     * <p>注意要先过滤掉指向已逻辑删除项目的关联：库里可能残留历史回填/删除产生的关系，
     * 若一并算作"被引用"，孤儿合同就永远清不掉。</p>
     */
    public Set<Long> allLinkedContractIds() {
        List<ProjectContract> links = linkMapper.selectList(null);
        Set<Long> liveProjectIds = projectMapper
                .selectList(new LambdaQueryWrapper<Project>()
                        .select(Project::getId)
                        .eq(Project::getDeleted, 0))
                .stream().map(Project::getId).collect(java.util.stream.Collectors.toSet());
        return links.stream()
                .filter(l -> liveProjectIds.contains(l.getProjectId()))
                .map(ProjectContract::getContractId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
