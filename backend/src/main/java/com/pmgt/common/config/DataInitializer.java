package com.pmgt.common.config;

import com.pmgt.common.security.Role;
import com.pmgt.module.system.entity.SysUser;
import com.pmgt.module.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 系统初始化：用户表为空时预置演示账号（密码统一 123456，首次使用建议尽快修改）。
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userMapper.selectCount(null) > 0) {
            return;
        }
        List<UserSeed> seeds = List.of(
                new UserSeed("admin", "系统管理员", Role.ADMIN),
                new UserSeed("jingban01", "王经办", Role.MANAGER),
                new UserSeed("lingdao01", "李领导", Role.VIEWER)
        );
        for (UserSeed s : seeds) {
            SysUser u = new SysUser();
            u.setAccount(s.account());
            u.setPassword(passwordEncoder.encode("123456"));
            u.setName(s.name());
            u.setRole(s.role());
            u.setStatus(1);
            userMapper.insert(u);
            log.info("初始化用户: {} / 123456 ({})", s.account(), s.role());
        }
    }

    private record UserSeed(String account, String name, Role role) {
    }
}
