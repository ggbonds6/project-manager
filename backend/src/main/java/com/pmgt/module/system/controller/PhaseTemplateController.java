package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.module.system.entity.PhaseTemplate;
import com.pmgt.module.system.mapper.PhaseTemplateMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 阶段模板（按项目类型）：新建项目时据其生成阶段实例。
 */
@RestController
@RequestMapping("/api/phase-templates")
public class PhaseTemplateController {

    private final PhaseTemplateMapper templateMapper;

    public PhaseTemplateController(PhaseTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
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
}
