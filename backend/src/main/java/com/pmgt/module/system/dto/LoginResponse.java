package com.pmgt.module.system.dto;

import lombok.Data;

@Data
public class LoginResponse {

    private String token;
    private UserVO user;

    public LoginResponse(String token, UserVO user) {
        this.token = token;
        this.user = user;
    }
}
