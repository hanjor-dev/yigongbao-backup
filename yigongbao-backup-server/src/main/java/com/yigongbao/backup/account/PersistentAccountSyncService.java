package com.yigongbao.backup.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers; import lombok.RequiredArgsConstructor; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** 持久化账号同步：首次创建初始化密码/状态；后续同步只更新线上托管字段。 */
@Service @RequiredArgsConstructor
public class PersistentAccountSyncService {
    private final BkUserMapper mapper;
    private final BkUserFieldControlService fieldControl;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public BkUserEntity merge(RemoteAccount remote) {
        BkUserEntity local=mapper.findBySourceUserId(remote.id()); boolean first=local==null;
        if(first){local=new BkUserEntity(); local.setSourceUserId(remote.id()); local.setUsername(remote.username()); local.setPasswordHash(remote.passwordHash()); local.setEnabledStatus(remote.deleted() || remote.status()!=1?"DISABLED":"ENABLED"); local.setRoleCode("USER"); local.setLoginFailCount(0); local.setEmergencyAdmin(false);}
        local.setUsername(remote.username()); local.setRealName(remote.realName()); local.setPhone(remote.phone()); local.setEmail(remote.email()); local.setSourceUpdatedAt(remote.updateTime()); local.setLastSyncedAt(LocalDateTime.now());
        if(first) { mapper.insert(local); fieldControl.ensureLocal(local.getId(), BkUserFieldControlService.PASSWORD_FIELD, null); fieldControl.ensureLocal(local.getId(), BkUserFieldControlService.STATUS_FIELD, null); }
        else mapper.updateById(local); return local;
    }
    public boolean verify(String username,String raw){BkUserEntity u=mapper.findByUsername(username); if(u==null||!"ENABLED".equals(u.getEnabledStatus())||u.getLockUntil()!=null&&u.getLockUntil().isAfter(LocalDateTime.now())) return false; boolean ok=encoder.matches(raw,u.getPasswordHash()); if(ok){u.setLoginFailCount(0);u.setLockUntil(null);}else{int n=(u.getLoginFailCount()==null?0:u.getLoginFailCount())+1;u.setLoginFailCount(n);if(n>=5)u.setLockUntil(LocalDateTime.now().plusMinutes(15));} mapper.updateById(u); return ok;}
}
