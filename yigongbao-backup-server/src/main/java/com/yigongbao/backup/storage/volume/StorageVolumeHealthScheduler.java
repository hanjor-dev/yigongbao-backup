package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** Periodically updates volume reachability; it never changes an existing job's pinned target. */
@Component
@RequiredArgsConstructor
@Slf4j
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
            StorageVolumeLifecycleService.RootRecoveryResult recovery = canAutoRecoverRoot(volume)
                    ? lifecycle.recoverMissingManagedRoot(volume, 0L) : null;
            StorageVolumeHealthService.HealthSnapshot snapshot;
            if (recovery != null && recovery.blocked()) {
                snapshot = StorageVolumeHealthService.HealthSnapshot.unavailable(
                        "MANAGED_ROOT_RECOVERY_BLOCKED", recovery.reason(), 0L);
            } else {
                snapshot = lifecycle.hasValidMarker(volume)
                        ? health.probe(volume)
                        : StorageVolumeHealthService.HealthSnapshot.unavailable(
                                "VOLUME_MARKER_MISMATCH", "卷标记校验失败", 0L);
            }
            // 除“同一物理卷上的空目录丢失”可受控恢复外，其余离线或不可用状态仍需管理员确认。
            String previous = volume.getMountStatus();
            String next = StorageVolumeStatus.Mount.OFFLINE.name().equals(previous)
                    ? StorageVolumeStatus.Mount.OFFLINE.name() : snapshot.accessible()
                    ? (StorageVolumeStatus.Mount.ONLINE.name().equals(previous)
                        ? StorageVolumeStatus.Mount.ONLINE.name() : previous)
                    : StorageVolumeStatus.Mount.UNAVAILABLE.name();
            boolean changed = !next.equals(previous);
            volume.setMountStatus(next);
            StorageVolumeCapacitySnapshotPolicy.apply(volume, snapshot, LocalDateTime.now());
            volumes.updateById(volume);
            if (recovery != null && recovery.recovered()) {
                log.warn("存储卷健康巡检已自动恢复受控目录｜存储卷ID={}｜卷代码={}｜受控目录={}",
                        volume.getId(), volume.getVolumeCode(), volume.getRootPath());
            }
            if (changed && !snapshot.accessible()) {
                log.warn("存储卷健康巡检判定不可用｜存储卷ID={}｜卷代码={}｜挂载点={}｜受控目录={}｜原挂载状态={}｜错误代码={}｜错误原因={}",
                        volume.getId(), volume.getVolumeCode(), volume.getMountPath(), volume.getRootPath(),
                        previous, snapshot.errorCode(), snapshot.errorMessage());
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

    private static boolean canAutoRecoverRoot(StorageVolumeEntity volume) {
        return StorageVolumeStatus.Tier.HOT.name().equals(volume.getTier())
                && !StorageVolumeStatus.Mount.OFFLINE.name().equals(volume.getMountStatus())
                && !StorageVolumeStatus.Admin.RETIRED.name().equals(volume.getAdminStatus())
                && (StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus())
                    || StorageVolumeStatus.Write.STANDBY.name().equals(volume.getWriteStatus()));
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
