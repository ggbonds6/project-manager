package com.pmgt.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import com.pmgt.common.security.Role;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String account;
    private String password;
    private String name;
    private Role role;
    private Integer status;
    private LocalDateTime lastLoginTime;
}
