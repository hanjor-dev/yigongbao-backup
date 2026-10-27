package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 本地账号同步规则：线上可覆盖 REMOTE 字段，本地密码和状态永不被线上改写。 */
@Service
@RequiredArgsConstructor
public class AccountSyncService {
    private final BkUserRepository repository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public BkUser merge(RemoteAccount remote) {
        BkUser local = repository.findBySourceUserId(remote.id());
        if (local == null) {
            local = new BkUser();
            local.setSourceUserId(remote.id());
            local.setUsername(remote.username());
            local.setPasswordHash(remote.passwordHash());
            local.setEnabledStatus(remote.status());
        }
        local.setUsername(remote.username());
        local.setRealName(remote.realName());
        local.setPhone(remote.phone());
        local.setEmail(remote.email());
        local.setSourceUpdatedAt(remote.updateTime());
        local.setLastSyncedAt(LocalDateTime.now());
        // passwordHash/status intentionally remain local after first provisioning.
        return repository.save(local);
    }

    public boolean verifyPassword(BkUser user, String rawPassword) {
        if (user == null || rawPassword == null || user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(LocalDateTime.now())
                || !Integer.valueOf(1).equals(user.getEnabledStatus())) {
            return false;
        }
        boolean valid = passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (valid) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        } else {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            if (user.getFailedLoginCount() >= 5) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
            }
        }
        repository.save(user);
        return valid;
    }
}
