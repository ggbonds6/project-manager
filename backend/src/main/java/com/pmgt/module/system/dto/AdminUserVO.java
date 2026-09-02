package com.pmgt.module.system.dto;

import com.pmgt.common.security.Role;
import com.pmgt.module.system.entity.SysUser;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员视角用户信息（含最后登录时间/部门名）。
 */
@Data
public class AdminUserVO {

    private Long id;
    private String account;
    private String name;
    private Long deptId;
    private String deptName;
    private Role role;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;

    public static AdminUserVO from(SysUser u, String deptName) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(u.getId());
        vo.setAccount(u.getAccount());
        vo.setName(u.getName());
        vo.setDeptId(u.getDeptId());
        vo.setDeptName(deptName);
        vo.setRole(u.getRole());
        vo.setStatus(u.getStatus());
        vo.setLastLoginTime(u.getLastLoginTime());
        vo.setCreateTime(u.getCreateTime());
        return vo;
    }
}
