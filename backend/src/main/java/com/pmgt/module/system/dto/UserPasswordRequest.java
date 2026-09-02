package com.pmgt.module.system.dto;

import com.pmgt.common.security.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    private String password;
}
