package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次部署用一次性环境密钥创建应急管理员；已有部署不受影响。 */
@Component
@RequiredArgsConstructor
public class EmergencyAdminBootstrap implements CommandLineRunner {
    private static final String LEGACY_FIXED_HASH = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi";
    private final BkUserMapper mapper;
    private final Environment environment;

    @Override
    public void run(String... args) {
        BkUserEntity existing = mapper.findEmergencyAdmin();
        if (existing != null && !LEGACY_FIXED_HASH.equals(existing.getPasswordHash())) return;
        String password = environment.getProperty("BACKUP_BOOTSTRAP_ADMIN_PASSWORD", "");
        if (password.length() < 16) {
            throw new IllegalStateException("首次启动必须设置至少16位的 BACKUP_BOOTSTRAP_ADMIN_PASSWORD");
        }
        if (existing != null) {
            existing.setPasswordHash(new BCryptPasswordEncoder().encode(password));
            existing.setLoginFailCount(0);
            existing.setLockUntil(null);
            mapper.updateById(existing);
            return;
        }
        String username = environment.getProperty("BACKUP_BOOTSTRAP_ADMIN_USERNAME", "admin1").trim();
        if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalStateException("BACKUP_BOOTSTRAP_ADMIN_USERNAME 格式非法");
        }
        if (mapper.findByUsername(username) != null) {
            throw new IllegalStateException("引导管理员账号已被非应急账号占用: " + username);
        }
        BkUserEntity user = new BkUserEntity();
        user.setUsername(username);
        user.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        user.setRealName("应急管理员");
        user.setRoleCode("ADMIN");
        user.setEnabledStatus("ENABLED");
        user.setLoginFailCount(0);
        user.setEmergencyAdmin(true);
        mapper.insert(user);
    }
}
