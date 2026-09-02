package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.AuthContext;
import com.pmgt.common.security.RequireRole;
import com.pmgt.common.security.Role;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.dto.AdminUserVO;
import com.pmgt.module.system.dto.UserCreateRequest;
import com.pmgt.module.system.dto.UserPasswordRequest;
import com.pmgt.module.system.dto.UserUpdateRequest;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@RequireRole({Role.ADMIN})
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;

    public AdminUserController(SysUserMapper userMapper,
                               PasswordEncoder passwordEncoder,
                               OperationLogService operationLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public R<Page<AdminUserVO>> page(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String role,
                                     @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getId);
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(SysUser::getName, keyword).or().like(SysUser::getAccount, keyword));
        }
        if (StringUtils.hasText(role)) {
            try {
                qw.eq(SysUser::getRole, Role.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                // noop
            }
        }
        if (status != null) {
            qw.eq(SysUser::getStatus, status);
        }
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, Math.min(size, 100)), qw);
        Page<AdminUserVO> vo = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        vo.setRecords(p.getRecords().stream().map(AdminUserVO::from).toList());
        return R.ok(vo);
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody UserCreateRequest req) {
        if (req.getRole() == null) {
            throw new BizException(400, "请选择角色");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getAccount, req.getAccount())) > 0) {
            throw new BizException(400, "账号已存在: " + req.getAccount());
        }
        SysUser u = new SysUser();
        u.setAccount(req.getAccount());
        u.setName(req.getName());
        u.setRole(req.getRole());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setStatus(1);
        userMapper.insert(u);
        operationLogService.log("USER", u.getId(), "USER_CREATE", "创建用户 " + req.getAccount() + "/" + req.getName());
        return R.ok(u.getId());
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        SysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "用户不存在");
        }
        if (req.getRole() == null) {
            throw new BizException(400, "请选择角色");
        }
        exist.setName(req.getName());
        exist.setRole(req.getRole());
        if (req.getStatus() != null) {
            if (req.getStatus() == 0 && AuthContext.userId().map(id::equals).orElse(false)) {
                throw new BizException(400, "不能停用当前登录账号");
            }
            exist.setStatus(req.getStatus());
        }
        userMapper.updateById(exist);
        operationLogService.log("USER", id, "USER_UPDATE", "更新用户 " + exist.getAccount() + " 资料");
        return R.ok();
    }

    @PutMapping("/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody UserPasswordRequest req) {
        SysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "用户不存在");
        }
        exist.setPassword(passwordEncoder.encode(req.getPassword()));
        userMapper.updateById(exist);
        operationLogService.log("USER", id, "USER_PWD", "重置用户 " + exist.getAccount() + " 密码");
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long selfId = AuthContext.userId().orElse(null);
        if (selfId != null && selfId.equals(id)) {
            throw new BizException(400, "不能删除当前登录账号");
        }
        SysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "用户不存在");
        }
        if (exist.getRole() == Role.ADMIN
                && userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, Role.ADMIN)) <= 1) {
            throw new BizException(400, "系统至少保留一名管理员");
        }
        userMapper.deleteById(id);
        operationLogService.log("USER", id, "USER_DELETE", "删除用户 " + exist.getAccount());
        return R.ok();
    }
}
