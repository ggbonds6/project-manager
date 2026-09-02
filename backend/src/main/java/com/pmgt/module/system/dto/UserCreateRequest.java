package com.pmgt.module.system.dto;

import com.pmgt.common.security.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员：新建用户。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "姓名不能为空")
    private String name;

    private Role role;

    @NotBlank(message = "初始密码不能为空")
    private String password;
}
