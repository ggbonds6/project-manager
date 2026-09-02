package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.module.system.entity.SysDept;
import com.pmgt.module.system.mapper.SysDeptMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/depts")
public class DeptController {

    private final SysDeptMapper deptMapper;

    public DeptController(SysDeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    /** 扁平列表，前端按 parentId 组树 */
    @GetMapping
    public R<List<SysDept>> list() {
        return R.ok(deptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .orderByAsc(SysDept::getOrderNo)
                .orderByAsc(SysDept::getId)));
    }
}
