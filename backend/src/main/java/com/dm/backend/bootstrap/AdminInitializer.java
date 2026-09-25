package com.dm.backend.bootstrap;

import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次启动（无任何账号）时创建默认管理员，便于建组织。 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AdminInitializer(SysUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setAccount("admin");
        admin.setName("系统管理员");
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        admin.setGlobalRole(GlobalRole.ADMIN);
        users.save(admin);
        log.warn("系统无账号，已初始化默认管理员 admin / admin123，请登录后尽快重置密码");
    }
}
