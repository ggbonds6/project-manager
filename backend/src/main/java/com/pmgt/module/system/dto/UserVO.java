package com.pmgt.module.system.dto;

import com.pmgt.common.security.Role;
import com.pmgt.module.system.entity.SysUser;

/**
 * 用户信息（不暴露密码等敏感字段）。
 */
public record UserVO(Long id, String account, String name, Role role, Long deptId, Integer status) {

    public static UserVO from(SysUser u) {
        return new UserVO(u.getId(), u.getAccount(), u.getName(), u.getRole(), u.getDeptId(), u.getStatus());
    }
}
