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
    private final StorageVolumeLifecycleService lifecycle;
    private final RuntimeConfigService configs;
    private final java.util.Set<Long> recommendedVolumes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "#{@runtimeConfigService.getInt('storage.volume-health.interval-minutes', 5, 1) * 60000}")
    public void probeManagedVolumes() {
        for (StorageVolumeEntity volume : volumes.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getIsDeleted, 0))) {
            StorageVolumeHealthService.HealthSnapshot snapshot = lifecycle.hasValidMarker(volume)
                    ? health.probe(volume)
                    : StorageVolumeHealthService.HealthSnapshot.unavailable("VOLUME_MARKER_MISMATCH", "卷标记校验失败", 0L);
            // 离线或不可用卷只能由管理员确认同一 marker 后重新上线，避免盘符复用时自动接管。
            String next = StorageVolumeStatus.Mount.OFFLINE.name().equals(volume.getMountStatus())
                    ? StorageVolumeStatus.Mount.OFFLINE.name() : snapshot.accessible()
                    ? (StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())
                        ? StorageVolumeStatus.Mount.ONLINE.name() : volume.getMountStatus())
                    : StorageVolumeStatus.Mount.UNAVAILABLE.name();
            boolean changed = !next.equals(volume.getMountStatus());
            volume.setMountStatus(next); volume.setLastProbedAt(LocalDateTime.now());
            volume.setLastErrorCode(snapshot.errorCode()); volume.setLastErrorMessage(snapshot.errorMessage());
            volume.setTotalBytes(snapshot.accessible() && snapshot.totalBytes() > 0 ? snapshot.totalBytes() : null);
            volume.setUsableBytes(snapshot.accessible() && snapshot.totalBytes() > 0 ? snapshot.usableBytes() : null);
            volumes.updateById(volume);
            if (changed && !snapshot.accessible()) {
                audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                        "STORAGE_VOLUME_UNAVAILABLE", "STORAGE_VOLUME", String.valueOf(volume.getId()), null, null,
                        "FAILED", snapshot.errorMessage(), LocalDateTime.now()));
            }
            boolean recommend = StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus()) && shouldSwitch(snapshot);
            if (recommend && !recommendedVolumes.contains(volume.getId())) {
                recordSwitchRecommendation(volume, snapshot);
                recommendedVolumes.add(volume.getId());
            }
            if (!recommend) recommendedVolumes.remove(volume.getId());
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

    private void recordSwitchRecommendation(StorageVolumeEntity active, StorageVolumeHealthService.HealthSnapshot snapshot) {
        String reason = snapshot.accessible() ? "ACTIVE 卷使用率已达切换阈值" : "ACTIVE 卷健康探测失败";
        audit.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, null, "SYSTEM", null,
                "STORAGE_VOLUME_SWITCH_RECOMMENDED", "STORAGE_VOLUME", String.valueOf(active.getId()), null, null,
                "WARNING", reason, LocalDateTime.now()));
    }
}
