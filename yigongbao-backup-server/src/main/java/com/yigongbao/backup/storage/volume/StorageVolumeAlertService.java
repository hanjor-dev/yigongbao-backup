package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 以独立事务持久化选卷失败告警，外层任务创建回滚时告警仍可查询。 */
@Service
@RequiredArgsConstructor
public class StorageVolumeAlertService {
    private final AuditLogService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noWritableVolume(String reason) {
        audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                "STORAGE_VOLUME_NO_WRITABLE_CANDIDATE", "STORAGE_VOLUME", "ACTIVE", null, null,
                "FAILED", reason, LocalDateTime.now()));
    }
}
