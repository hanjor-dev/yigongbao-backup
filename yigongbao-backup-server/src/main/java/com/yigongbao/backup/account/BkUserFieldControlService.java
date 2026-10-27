package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 账号密码/状态本地托管标志服务；不向线上反向同步。 */
@Service
@RequiredArgsConstructor
public class BkUserFieldControlService {
    public static final String PASSWORD_FIELD = "passwordHash";
    public static final String STATUS_FIELD = "enabledStatus";
    private final BkUserFieldControlMapper mapper;

    public void ensureLocal(Long userId, String fieldName, Long actorId) {
        if (userId == null || fieldName == null || fieldName.isBlank()) return;
        BkUserFieldControlEntity row = mapper.find(userId, fieldName);
        if (row == null) {
            row = new BkUserFieldControlEntity();
            row.setUserId(userId); row.setFieldName(fieldName); row.setOwnership("LOCAL");
            row.setLocalChangedAt(LocalDateTime.now()); row.setLocalChangedBy(actorId);
            mapper.insert(row);
        }
    }

    public void markLocal(Long userId, String fieldName, Long actorId) {
        if (userId == null || fieldName == null || fieldName.isBlank()) return;
        BkUserFieldControlEntity row = mapper.find(userId, fieldName);
        if (row == null) {
            row = new BkUserFieldControlEntity();
            row.setUserId(userId); row.setFieldName(fieldName);
        }
        row.setOwnership("LOCAL"); row.setLocalChangedAt(LocalDateTime.now());
        row.setLocalChangedBy(actorId); row.setReleasedAt(null);
        if (row.getId() == null) mapper.insert(row); else mapper.updateById(row);
    }
}
