package com.pmgt.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.project.dto.DivisionSaveRequest;
import com.pmgt.module.project.entity.ProjectDivision;
import com.pmgt.module.project.mapper.ProjectDivisionMapper;
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

import java.util.List;

/**
 * 项目分工：模块 / 子模块的负责方、负责人、计划时间与进度。
 *
 * <p>列表返回**扁平结构**（带 parentId），由前端组装层级——
 * 后端不做树形拼装，前端可一次性拿到全部数据、展开/收起都无需再请求。</p>
 */
@RestController
@RequestMapping("/api")
public class ProjectDivisionController {

    private final ProjectDivisionMapper divisionMapper;
    private final ProjectMapper projectMapper;
    private final OperationLogService operationLogService;

    public ProjectDivisionController(ProjectDivisionMapper divisionMapper,
                                     ProjectMapper projectMapper,
                                     OperationLogService operationLogService) {
        this.divisionMapper = divisionMapper;
        this.projectMapper = projectMapper;
        this.operationLogService = operationLogService;
    }

    /** 某项目的全部分工 */
    @GetMapping("/projects/{projectId}/divisions")
    public R<List<ProjectDivision>> listByProject(@PathVariable Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BizException(404, "项目不存在");
        }
        return R.ok(divisionMapper.selectList(new LambdaQueryWrapper<ProjectDivision>()
                .eq(ProjectDivision::getProjectId, projectId)
                .orderByAsc(ProjectDivision::getSortNo)
                .orderByAsc(ProjectDivision::getId)));
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PostMapping("/divisions")
    public R<Long> create(@Valid @RequestBody DivisionSaveRequest req) {
        checkProject(req.getProjectId());
        checkParent(req.getParentId(), req.getProjectId(), null);
        ProjectDivision d = new ProjectDivision();
        apply(d, req);
        divisionMapper.insert(d);
        operationLogService.log("PROJECT", req.getProjectId(), "DIVISION_CREATE",
                "新增分工「" + d.getName() + "」");
        return R.ok(d.getId());
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PutMapping("/divisions/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody DivisionSaveRequest req) {
        ProjectDivision exist = divisionMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "分工不存在");
        }
        checkProject(req.getProjectId());
        checkParent(req.getParentId(), req.getProjectId(), id);
        apply(exist, req);
        divisionMapper.updateById(exist);
        operationLogService.log("PROJECT", req.getProjectId(), "DIVISION_UPDATE",
                "更新分工「" + exist.getName() + "」");
        return R.ok();
    }

    /** 删除分工；存在子模块时**一并删除**（返回前会记入日志，便于追溯） */
    @RequireRole({Role.ADMIN, Role.MANAGER})
    @DeleteMapping("/divisions/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ProjectDivision exist = divisionMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "分工不存在");
        }
        int removed = deleteCascade(id);
        operationLogService.log("PROJECT", exist.getProjectId(), "DIVISION_DELETE",
                "删除分工「" + exist.getName() + "」"
                        + (removed > 1 ? "（含 " + (removed - 1) + " 个子模块）" : ""));
        return R.ok();
    }

    /** 递归删除，返回删除条数（含自身）。 */
    private int deleteCascade(Long id) {
        List<ProjectDivision> children = divisionMapper.selectList(new LambdaQueryWrapper<ProjectDivision>()
                .eq(ProjectDivision::getParentId, id));
        int count = 1;
        for (ProjectDivision child : children) {
            count += deleteCascade(child.getId());
        }
        divisionMapper.deleteById(id);
        return count;
    }

    private void checkProject(Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BizException(404, "项目不存在");
        }
    }

    /** 上级模块必须是同项目下的其它节点，且不能形成环。 */
    private void checkParent(Long parentId, Long projectId, Long selfId) {
        if (parentId == null) {
            return;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new BizException(400, "上级模块不能是自己");
        }
        ProjectDivision parent = divisionMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(404, "上级模块不存在");
        }
        if (!parent.getProjectId().equals(projectId)) {
            throw new BizException(400, "上级模块不属于当前项目");
        }
        if (selfId != null && isSelfOrDescendant(parentId, selfId)) {
            throw new BizException(400, "不能把模块挂到它自己的子模块下（会形成循环）");
        }
    }

    private boolean isSelfOrDescendant(Long candidateId, Long nodeId) {
        Long cur = candidateId;
        int guard = 0;
        while (cur != null && guard++ < 20) {
            if (cur.equals(nodeId)) {
                return true;
            }
            ProjectDivision d = divisionMapper.selectById(cur);
            cur = d == null ? null : d.getParentId();
        }
        return false;
    }

    private void apply(ProjectDivision d, DivisionSaveRequest req) {
        d.setProjectId(req.getProjectId());
        d.setParentId(req.getParentId());
        d.setName(req.getName());
        d.setOwnerSide(req.getOwnerSide());
        d.setOwnerName(req.getOwnerName());
        d.setVendorOwner(req.getVendorOwner());
        d.setPlanDevDate(req.getPlanDevDate());
        d.setPlanTestDate(req.getPlanTestDate());
        d.setPlanOnlineDate(req.getPlanOnlineDate());
        d.setProgress(req.getProgress() == null ? 0 : Math.max(0, Math.min(100, req.getProgress())));
        d.setStatus(req.getStatus());
        d.setRemark(req.getRemark());
        d.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
    }
}
