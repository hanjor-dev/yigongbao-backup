package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import com.yigongbao.backup.config.RuntimeConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/** 为新建写入任务选择唯一活动热备卷。既有任务必须使用其已固化的卷 ID。 */
@Service
@RequiredArgsConstructor
public class ActiveVolumeSelectionService {
    private final StorageVolumeMapper volumeMapper;
    private final StorageVolumeHealthService healthService;
    private final StorageVolumeLifecycleService lifecycleService;
    private final RuntimeConfigService configs;
    private final StorageVolumeAlertService alerts;

    /** 返回当前可写热备卷；没有合格卷时拒绝创建不可执行任务。 */
    public StorageVolumeEntity requireActiveHotVolume() {
        StorageVolumeEntity volume = volumeMapper.selectOne(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, StorageVolumeStatus.Tier.HOT.name())
                .eq(StorageVolumeEntity::getMountStatus, StorageVolumeStatus.Mount.ONLINE.name())
                .eq(StorageVolumeEntity::getWriteStatus, StorageVolumeStatus.Write.ACTIVE.name())
                .eq(StorageVolumeEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (volume == null || !hasCapacity(volume, 1L)) {
            throw new StorageVolumeOperationException("没有可用的活动热备存储卷，无法创建写入任务");
        }
        return volume;
    }

    /** 新任务入口；保留 requiredBytes 参数以确保调用方不再自行挑选卷。容量预留随后以同一卷执行。 */
    @Transactional(rollbackFor = Exception.class)
    public StorageVolumeEntity selectActiveForNewBackup(long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("预计写入容量不能为负数");
        StorageVolumeEntity active = volumeMapper.findActiveForUpdate();
        if (active != null && hasCapacity(active, Math.max(requiredBytes, 1L))) return active;
        if (!configs.getBoolean("storage.volume-switch.enabled", false)) {
            throw new StorageVolumeOperationException("活动热备存储卷不可用或剩余空间不足");
        }
        StorageVolumeEntity standby;
        try {
            standby = selectStandbyForNewBackup(Math.max(requiredBytes, 1L));
        } catch (StorageVolumeOperationException unavailable) {
            try { alerts.noWritableVolume(unavailable.getMessage()); }
            catch (RuntimeException auditFailure) { unavailable.addSuppressed(auditFailure); }
            throw unavailable;
        }
        lifecycleService.setActive(standby.getId(), 0L);
        return standby;
    }

    /** Selects only previously managed HOT STANDBY volumes; capacity is reserved after selection. */
    public StorageVolumeEntity selectStandbyForNewBackup(long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("预计写入容量不能为负数");
        return volumeMapper.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                        .eq(StorageVolumeEntity::getTier, StorageVolumeStatus.Tier.HOT.name())
                        .eq(StorageVolumeEntity::getMountStatus, StorageVolumeStatus.Mount.ONLINE.name())
                        .eq(StorageVolumeEntity::getWriteStatus, StorageVolumeStatus.Write.STANDBY.name())
                        .eq(StorageVolumeEntity::getIsDeleted, 0)
                        .orderByAsc(StorageVolumeEntity::getPriority))
                .stream().filter(volume -> hasCapacity(volume, requiredBytes)).min(Comparator.comparing(StorageVolumeEntity::getPriority,
                        Comparator.nullsLast(Integer::compareTo)))
                .orElseThrow(() -> new StorageVolumeOperationException("没有可用的备用热备存储卷"));
    }

    private boolean hasCapacity(StorageVolumeEntity volume, long requiredBytes) {
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(volume);
        int threshold = configs.getInt("storage.volume-switch.threshold-percent", 90, 1);
        if (threshold < 1 || threshold > 100) threshold = 90;
        boolean belowThreshold = health.totalBytes() > 0
                && (health.totalBytes() - health.usableBytes()) * 100L < health.totalBytes() * threshold;
        return health.accessible() && health.writable() && belowThreshold && health.usableBytes() >= requiredBytes;
    }
}
