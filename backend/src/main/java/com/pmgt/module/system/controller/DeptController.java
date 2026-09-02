package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.entity.SysDept;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysDeptMapper;
import com.pmgt.module.system.mapper.SysUserMapper;
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

@RestController
@RequestMapping("/api/depts")
public class DeptController {

    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;

    public DeptController(SysDeptMapper deptMapper, SysUserMapper userMapper, OperationLogService operationLogService) {
        this.deptMapper = deptMapper;
        this.userMapper = userMapper;
        this.operationLogService = operationLogService;
    }

    /** 扁平列表，前端按 parentId 组树 */
    @GetMapping
    public R<List<SysDept>> list() {
        return R.ok(deptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .orderByAsc(SysDept::getOrderNo)
                .orderByAsc(SysDept::getId)));
    }

    @RequireRole({Role.ADMIN})
    @PostMapping
    public R<Long> create(@RequestBody SysDept dept) {
        if (!StringUtils.hasText(dept.getName())) {
            throw new BizException(400, "部门名称不能为空");
        }
        dept.setParentId(dept.getParentId() == null ? 0L : dept.getParentId());
        dept.setOrderNo(dept.getOrderNo() == null ? 0 : dept.getOrderNo());
        deptMapper.insert(dept);
        operationLogService.log("DEPT", dept.getId(), "DEPT_CREATE", "新增部门 " + dept.getName());
        return R.ok(dept.getId());
    }

    @RequireRole({Role.ADMIN})
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody SysDept dept) {
        SysDept exist = deptMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "部门不存在");
        }
        if (dept.getParentId() != null && dept.getParentId().equals(id)) {
            throw new BizException(400, "上级部门不能是自己");
        }
        dept.setId(id);
        dept.setCreateTime(exist.getCreateTime());
        deptMapper.updateById(dept);
        operationLogService.log("DEPT", id, "DEPT_UPDATE", "更新部门 " + (dept.getName() == null ? exist.getName() : dept.getName()));
        return R.ok();
    }

    @RequireRole({Role.ADMIN})
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (deptMapper.selectCount(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, id)) > 0) {
            throw new BizException(400, "存在下级部门，无法删除");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, id)) > 0) {
            throw new BizException(400, "该部门下仍有用户，无法删除");
        }
        SysDept exist = deptMapper.selectById(id);
        deptMapper.deleteById(id);
        operationLogService.log("DEPT", id, "DEPT_DELETE", "删除部门 " + (exist == null ? id : exist.getName()));
        return R.ok();
    }
}
