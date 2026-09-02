package com.pmgt.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pmgt.common.api.R;
import com.pmgt.common.exception.BizException;
import com.pmgt.common.security.AuthContext;
import com.pmgt.common.security.JwtUtil;
import com.pmgt.module.log.service.OperationLogService;
import com.pmgt.module.system.dto.LoginRequest;
import com.pmgt.module.system.dto.LoginResponse;
import com.pmgt.module.system.dto.UserVO;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final OperationLogService operationLogService;

    public AuthController(SysUserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          OperationLogService operationLogService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.operationLogService = operationLogService;
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, req.getAccount()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException(400, "账号或密码错误");
        }
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String token = jwtUtil.createToken(user.getId(), user.getAccount(), user.getName(), user.getRole());
        operationLogService.log("USER", user.getId(), "LOGIN", "用户登录");
        return R.ok(new LoginResponse(token, UserVO.from(user)));
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        Long userId = AuthContext.userId().orElseThrow(() -> new BizException(401, "未登录"));
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(401, "账号不存在或已停用");
        }
        return R.ok(UserVO.from(user));
    }
}
