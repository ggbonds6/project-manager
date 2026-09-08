package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.entity.PhaseTpl;
import com.pmgt.module.system.entity.PhaseTemplate;
import com.pmgt.module.system.mapper.PhaseTplMapper;
import com.pmgt.module.system.mapper.PhaseTemplateMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程模板（Tab 式多模板）：模板 CRUD（复制/默认/启停）+ 阶段整体读取与批量保存。
 * 阶段行结构见 PhaseTemplate（线性阶段，供画布/列表两种视图使用同一数据）。
 */
@RestController
@RequestMapping("/api/phase-tpls")
public class PhaseTplController {

    private static final Set<String> TYPES = Set.of("HW", "SW");

    private final PhaseTplMapper tplMapper;
    private final PhaseTemplateMapper templateMapper;
    private final OperationLogService operationLogService;

    public PhaseTplController(PhaseTplMapper tplMapper,
                              PhaseTemplateMapper templateMapper,
                              OperationLogService operationLogService) {
        this.tplMapper = tplMapper;
        this.templateMapper = templateMapper;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public R<List<PhaseTpl>> list() {
        return R.ok(tplMapper.selectList(new LambdaQueryWrapper<PhaseTpl>()
                .orderByAsc(PhaseTpl::getProjectType)
                .orderByAsc(PhaseTpl::getSortNo)
                .orderByAsc(PhaseTpl::getId)));
    }

    /** 新建模板；body:{projectType,name,copyTplId?,remark?}，copyTplId 提供时复制其阶段 */
    @RequireRole({Role.ADMIN})
    @PostMapping
    public R<Long> create(@RequestBody Map<String, Object> body) {
        String projectType = str(body.get("projectType"));
        String name = str(body.get("name"));
        if (!TYPES.contains(projectType)) {
            throw new BizException(400, "项目类型仅支持 HW/SW");
        }
        if (!StringUtils.hasText(name)) {
            throw new BizException(400, "模板名称不能为空");
        }
        PhaseTpl tpl = new PhaseTpl();
        tpl.setProjectType(projectType);
        tpl.setName(name.trim());
        tpl.setBuiltin(0);
        tpl.setIsDefault(0);
        tpl.setEnabled(1);
        int max = tplMapper.selectList(new LambdaQueryWrapper<PhaseTpl>()
                        .eq(PhaseTpl::getProjectType, projectType))
                .stream().mapToInt(p -> p.getSortNo() == null ? 0 : p.getSortNo()).max().orElse(0);
        tpl.setSortNo(max + 1);
        tpl.setRemark(str(body.get("remark")));
        tplMapper.insert(tpl);

        Long copyTplId = body.get("copyTplId") == null ? null : Long.valueOf(String.valueOf(body.get("copyTplId")));
        if (copyTplId != null) {
            copyPhases(copyTplId, tpl.getId());
        }
        operationLogService.log("TEMPLATE", tpl.getId(), "TPL_CREATE",
                "新建流程模板 " + projectType + "/" + tpl.getName() + (copyTplId != null ? "（从模板#" + copyTplId + "复制）" : ""));
        return R.ok(tpl.getId());
    }

    @RequireRole({Role.ADMIN})
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody PhaseTpl req) {
        PhaseTpl exist = tplMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "模板不存在");
        }
        if (StringUtils.hasText(req.getName())) {
            exist.setName(req.getName().trim());
        }
        if (req.getEnabled() != null) {
            exist.setEnabled(req.getEnabled());
        }
        if (req.getRemark() != null) {
            exist.setRemark(req.getRemark());
        }
        if (req.getIsDefault() != null && req.getIsDefault() == 1) {
            // 同一类型仅一个默认模板
            List<PhaseTpl> same = tplMapper.selectList(new LambdaQueryWrapper<PhaseTpl>()
                    .eq(PhaseTpl::getProjectType, exist.getProjectType()));
            for (PhaseTpl p : same) {
                int def = Objects.equals(p.getId(), id) ? 1 : 0;
                if (!Objects.equals(p.getIsDefault(), def)) {
                    p.setIsDefault(def);
                    tplMapper.updateById(p);
                }
            }
        }
        tplMapper.updateById(exist);
        operationLogService.log("TEMPLATE", id, "TPL_UPDATE", "更新流程模板 " + exist.getName());
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        PhaseTpl exist = tplMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "模板不存在");
        }
        if (exist.getBuiltin() != null && exist.getBuiltin() == 1) {
            throw new BizException(400, "内置默认模板不可删除（可另建模板替代后设为默认）");
        }
        // 逻辑删除模板及其阶段
        List<PhaseTemplate> phases = templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                .eq(PhaseTemplate::getTplId, id));
        for (PhaseTemplate ph : phases) {
            templateMapper.deleteById(ph.getId());
        }
        tplMapper.deleteById(id);
        operationLogService.log("TEMPLATE", id, "TPL_DELETE", "删除流程模板 " + exist.getName());
        return R.ok();
    }

    /** 模板下全部阶段（按顺序号） */
    @GetMapping("/{id}/phases")
    public R<List<PhaseTemplate>> phases(@PathVariable Long id) {
        return R.ok(templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                .eq(PhaseTemplate::getTplId, id)
                .orderByAsc(PhaseTemplate::getSortNo)
                .orderByAsc(PhaseTemplate::getId)));
    }

    /** 整模板批量保存（数组顺序即阶段顺序）：更新已存在/新增/删除被移除项 */
    @RequireRole({Role.ADMIN})
    @PutMapping("/{id}/phases")
    public R<Void> savePhases(@PathVariable Long id, @RequestBody List<PhaseTemplate> items) {
        PhaseTpl tpl = tplMapper.selectById(id);
        if (tpl == null) {
            throw new BizException(404, "模板不存在");
        }
        List<PhaseTemplate> existing = templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                .eq(PhaseTemplate::getTplId, id));
        Set<Long> keepIds = items.stream().map(PhaseTemplate::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (PhaseTemplate ex : existing) {
            if (!keepIds.contains(ex.getId())) {
                templateMapper.deleteById(ex.getId());
            }
        }
        int idx = 1;
        for (PhaseTemplate item : items) {
            if (!StringUtils.hasText(item.getPhaseName())) {
                continue;
            }
            PhaseTemplate target = item.getId() == null ? null : templateMapper.selectById(item.getId());
            if (target == null) {
                target = new PhaseTemplate();
                target.setTplId(id);
                target.setProjectType(tpl.getProjectType());
                target.setPhaseName(item.getPhaseName().trim());
            } else {
                target.setPhaseName(item.getPhaseName().trim());
            }
            target.setSortNo(idx);
            target.setWeight(item.getWeight() == null ? 5 : item.getWeight());
            target.setPayNode(StringUtils.hasText(item.getPayNode()) ? item.getPayNode() : null);
            target.setAttachTypeHints(StringUtils.hasText(item.getAttachTypeHints()) ? item.getAttachTypeHints() : null);
            target.setDescription(StringUtils.hasText(item.getDescription()) ? item.getDescription() : null);
            target.setGuide(StringUtils.hasText(item.getGuide()) ? item.getGuide() : null);
            target.setKeyMaterials(StringUtils.hasText(item.getKeyMaterials()) ? item.getKeyMaterials() : null);
            target.setSkipable(item.getSkipable() == null ? 0 : item.getSkipable());
            if (item.getId() != null && templateMapper.selectById(item.getId()) != null) {
                target.setId(item.getId());
                templateMapper.updateById(target);
            } else {
                templateMapper.insert(target);
            }
            idx++;
        }
        operationLogService.log("TEMPLATE", id, "TPL_SAVE_PHASES",
                "保存流程模板阶段 " + tpl.getName() + "（" + (idx - 1) + " 个阶段）");
        return R.ok();
    }

    private void copyPhases(Long srcTplId, Long dstTplId) {
        List<PhaseTemplate> src = templateMapper.selectList(new LambdaQueryWrapper<PhaseTemplate>()
                .eq(PhaseTemplate::getTplId, srcTplId)
                .orderByAsc(PhaseTemplate::getSortNo));
        PhaseTpl dst = tplMapper.selectById(dstTplId);
        int idx = 1;
        for (PhaseTemplate s : src) {
            PhaseTemplate n = new PhaseTemplate();
            n.setTplId(dstTplId);
            n.setProjectType(dst == null ? s.getProjectType() : dst.getProjectType());
            n.setPhaseName(s.getPhaseName());
            n.setWeight(s.getWeight() == null ? 5 : s.getWeight());
            n.setPayNode(s.getPayNode());
            n.setAttachTypeHints(s.getAttachTypeHints());
            n.setDescription(s.getDescription());
            n.setGuide(s.getGuide());
            n.setKeyMaterials(s.getKeyMaterials());
            n.setSkipable(s.getSkipable() == null ? 0 : s.getSkipable());
            n.setSortNo(idx++);
            templateMapper.insert(n);
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
