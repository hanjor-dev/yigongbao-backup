package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** Periodically updates volume reachability; it never changes an existing job's pinned target. */
@Component
@RequiredArgsConstructor
public class StorageVolumeHealthScheduler {
    private final StorageVolumeMapper volumes;
    private final StorageVolumeHealthService health;
    private final AuditLogService audit;
    private final ActiveVolumeSelectionService selection;
    private final StorageVolumeLifecycleService lifecycle;
    private final RuntimeConfigService configs;

    @Scheduled(fixedDelayString = "#{@runtimeConfigService.getInt('storage.volume-health.interval-minutes', 5, 1) * 60000}")
    public void probeManagedVolumes() {
        for (StorageVolumeEntity volume : volumes.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getIsDeleted, 0))) {
            StorageVolumeHealthService.HealthSnapshot snapshot = lifecycle.hasValidMarker(volume)
                    ? health.probe(volume)
                    : StorageVolumeHealthService.HealthSnapshot.unavailable("VOLUME_MARKER_MISMATCH", "卷标记校验失败", 0L);
            String next = snapshot.accessible() ? StorageVolumeStatus.Mount.ONLINE.name()
                    : StorageVolumeStatus.Mount.UNAVAILABLE.name();
            boolean changed = !next.equals(volume.getMountStatus());
            volume.setMountStatus(next); volume.setLastProbedAt(LocalDateTime.now());
            volume.setLastErrorCode(snapshot.errorCode()); volume.setLastErrorMessage(snapshot.errorMessage());
            volumes.updateById(volume);
            if (changed && !snapshot.accessible()) {
                audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                        "STORAGE_VOLUME_UNAVAILABLE", "STORAGE_VOLUME", String.valueOf(volume.getId()), null, null,
                        "FAILED", snapshot.errorMessage(), LocalDateTime.now()));
            }
            if (StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus())
                    && shouldSwitch(snapshot)) tryAutoSwitch(volume, snapshot);
        }
    }

    private boolean shouldSwitch(StorageVolumeHealthService.HealthSnapshot snapshot) {
        try {
            if (!configs.getBoolean("storage.volume-switch.enabled", false)) return false;
            if (!snapshot.accessible()) return true;
            long threshold = configs.getInt("storage.volume-switch.threshold-percent", 90, 1);
            return snapshot.totalBytes() > 0 && (snapshot.totalBytes() - snapshot.usableBytes()) * 100L
                    >= snapshot.totalBytes() * threshold;
        } catch (RuntimeException unavailableConfig) { return false; }
    }

    private void tryAutoSwitch(StorageVolumeEntity active, StorageVolumeHealthService.HealthSnapshot snapshot) {
        try {
            StorageVolumeEntity standby = selection.selectStandbyForNewBackup(0L);
            lifecycle.setActive(standby.getId(), 0L);
            audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                    "STORAGE_VOLUME_AUTO_SWITCH", "STORAGE_VOLUME", String.valueOf(standby.getId()), null, null,
                    "SUCCESS", "原活动卷=" + active.getId(), LocalDateTime.now()));
        } catch (RuntimeException failed) {
            audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                    "STORAGE_VOLUME_AUTO_SWITCH", "STORAGE_VOLUME", String.valueOf(active.getId()), null, null,
                    "FAILED", failed.getMessage(), LocalDateTime.now()));
        }
    }
}
