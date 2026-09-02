package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.entity.PhaseTemplate;
import com.pmgt.module.system.mapper.PhaseTemplateMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 阶段模板：项目类型→有序阶段。
 * 读取对所有登录用户开放；维护仅管理员。
 */
@RestController
@RequestMapping("/api/phase-templates")
public class PhaseTemplateController {

    private static final Set<String> TYPES = Set.of("HW", "SW");

    private final PhaseTemplateMapper templateMapper;
    private final OperationLogService operationLogService;

    public PhaseTemplateController(PhaseTemplateMapper templateMapper, OperationLogService operationLogService) {
        this.templateMapper = templateMapper;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public R<List<PhaseTemplate>> list(@RequestParam(required = false) String type) {
        LambdaQueryWrapper<PhaseTemplate> qw = new LambdaQueryWrapper<PhaseTemplate>()
                .orderByAsc(PhaseTemplate::getProjectType)
                .orderByAsc(PhaseTemplate::getSortNo);
        if (StringUtils.hasText(type)) {
            qw.eq(PhaseTemplate::getProjectType, type);
        }
        return R.ok(templateMapper.selectList(qw));
    }

    @RequireRole({Role.ADMIN})
    @PostMapping
    public R<Long> create(@RequestBody PhaseTemplate tpl) {
        validate(tpl, null);
        if (tpl.getSortNo() == null) {
            Integer max = templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                            .eq(PhaseTemplate::getProjectType, tpl.getProjectType()))
                    .stream().mapToInt(PhaseTemplate::getSortNo).max().orElse(0);
            tpl.setSortNo(max + 1);
        }
        templateMapper.insert(tpl);
        operationLogService.log("TEMPLATE", tpl.getId(), "TEMPLATE_CREATE",
                "新增阶段模板 " + tpl.getProjectType() + "/" + tpl.getPhaseName());
        return R.ok(tpl.getId());
    }

    @RequireRole({Role.ADMIN})
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody PhaseTemplate tpl) {
        PhaseTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "阶段模板不存在");
        }
        validate(tpl, id);
        tpl.setId(id);
        tpl.setCreateTime(exist.getCreateTime());
        templateMapper.updateById(tpl);
        operationLogService.log("TEMPLATE", id, "TEMPLATE_UPDATE",
                "更新阶段模板 " + (StringUtils.hasText(tpl.getPhaseName()) ? tpl.getPhaseName() : exist.getPhaseName()));
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        PhaseTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "阶段模板不存在");
        }
        templateMapper.deleteById(id);
        operationLogService.log("TEMPLATE", id, "TEMPLATE_DELETE", "删除阶段模板 " + exist.getPhaseName());
        return R.ok();
    }

    private void validate(PhaseTemplate tpl, Long selfId) {
        if (!StringUtils.hasText(tpl.getPhaseName())) {
            throw new BizException(400, "阶段名称不能为空");
        }
        if (StringUtils.hasText(tpl.getProjectType()) && !TYPES.contains(tpl.getProjectType())) {
            throw new BizException(400, "项目类型仅支持 HW/SW");
        }
        if (tpl.getWeight() != null && (tpl.getWeight() < 0 || tpl.getWeight() > 100)) {
            throw new BizException(400, "权重应在 0-100");
        }
    }
}
