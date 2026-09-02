package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.security.Role;
import com.pmgt.module.system.dto.UserVO;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysUserMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户基础查询（供项目负责人/参与人选择器等场景使用；用户管理维护见 M6）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final SysUserMapper userMapper;

    public UserController(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping
    public R<List<UserVO>> list(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String role) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getDeptId)
                .orderByAsc(SysUser::getId);
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(SysUser::getName, keyword).or().like(SysUser::getAccount, keyword));
        }
        if (StringUtils.hasText(role)) {
            try {
                qw.eq(SysUser::getRole, Role.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                // 非法角色参数忽略
            }
        }
        return R.ok(userMapper.selectList(qw).stream().map(UserVO::from).toList());
    }
}
