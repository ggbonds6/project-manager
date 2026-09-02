package com.pmgt.module.system.dto;

import com.pmgt.common.security.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员：更新用户基本资料 / 启用状态（不修改密码）。
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    private String name;

    private Long deptId;

    private Role role;

    private Integer status;
}
