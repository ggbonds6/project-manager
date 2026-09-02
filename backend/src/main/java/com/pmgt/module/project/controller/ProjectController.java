package com.pmgt.module.project.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pmgt.common.api.R;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.project.dto.PhaseUpdateRequest;
import com.pmgt.module.project.dto.ProjectDetailVO;
import com.pmgt.module.project.dto.ProjectQuery;
import com.pmgt.module.project.dto.ProjectSaveRequest;
import com.pmgt.module.project.dto.ProjectVO;
import com.pmgt.module.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public R<Page<ProjectVO>> page(ProjectQuery query) {
        return R.ok(projectService.page(query));
    }

    @GetMapping("/{id}")
    public R<ProjectDetailVO> detail(@PathVariable Long id) {
        return R.ok(projectService.detail(id));
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PostMapping
    public R<Long> create(@Valid @RequestBody ProjectSaveRequest req) {
        return R.ok(projectService.create(req));
    }

    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ProjectSaveRequest req) {
        projectService.update(id, req);
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return R.ok();
    }

    /** 推进/更新单个阶段（经办人操作） */
    @RequireRole({Role.ADMIN, Role.MANAGER})
    @PutMapping("/{id}/phases/{phaseId}")
    public R<Void> updatePhase(@PathVariable Long id,
                               @PathVariable Long phaseId,
                               @RequestBody PhaseUpdateRequest req) {
        projectService.updatePhase(id, phaseId, req);
        return R.ok();
    }
}
