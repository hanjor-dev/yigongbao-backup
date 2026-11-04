package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers; import lombok.RequiredArgsConstructor; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** 持久化账号同步：首次创建初始化密码/状态；后续同步只更新线上托管字段。 */
@Service @RequiredArgsConstructor
public class PersistentAccountSyncService {
    private final BkUserMapper mapper;
    private final BkUserFieldControlService fieldControl;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** 合并一条线上账号；密码和状态只在首次建档时取线上值。 */
    public BkUserEntity merge(RemoteAccount remote) {
        validate(remote);
        BkUserEntity local = mapper.findBySourceUserId(remote.id());
        boolean first = local == null;
        if (first) {
            local = new BkUserEntity();
            local.setSourceUserId(remote.id());
            local.setPasswordHash(remote.passwordHash());
            local.setEnabledStatus(remote.deleted() || remote.status() == null || remote.status() != 1
                    ? "DISABLED" : "ENABLED");
            local.setRoleCode("USER");
            local.setLoginFailCount(0);
            local.setEmergencyAdmin(false);
        } else if (local.getSourceUpdatedAt() != null && remote.updateTime() != null
                && !remote.updateTime().isAfter(local.getSourceUpdatedAt())) {
            return local;
        }
        local.setUsername(remote.username());
        local.setRealName(remote.realName());
        local.setPhone(remote.phone());
        local.setEmail(remote.email());
        local.setSourceUpdatedAt(remote.updateTime());
        local.setLastSyncedAt(LocalDateTime.now());
        if (first) {
            mapper.insert(local);
            fieldControl.ensureLocal(local.getId(), BkUserFieldControlService.PASSWORD_FIELD, null);
            fieldControl.ensureLocal(local.getId(), BkUserFieldControlService.STATUS_FIELD, null);
        } else {
            mapper.updateById(local);
        }
        return local;
    }

    private static void validate(RemoteAccount remote) {
        if (remote == null || remote.id() == null || remote.id() <= 0
                || remote.username() == null || remote.username().isBlank()
                || remote.updateTime() == null) {
            throw new IllegalArgumentException("线上账号关键字段不完整");
        }
        if (remote.passwordHash() == null || !remote.passwordHash().startsWith("$2")) {
            throw new IllegalArgumentException("线上账号密码哈希格式非法");
        }
    }
    public boolean verify(String username,String raw){BkUserEntity u=mapper.findByUsername(username); if(u==null||!"ENABLED".equals(u.getEnabledStatus())||u.getLockUntil()!=null&&u.getLockUntil().isAfter(LocalDateTime.now())) return false; boolean ok=encoder.matches(raw,u.getPasswordHash()); if(ok){u.setLoginFailCount(0);u.setLockUntil(null);}else{int n=(u.getLoginFailCount()==null?0:u.getLoginFailCount())+1;u.setLoginFailCount(n);if(n>=5)u.setLockUntil(LocalDateTime.now().plusMinutes(15));} mapper.updateById(u); return ok;}
}
