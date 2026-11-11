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

    /**
     * 订单备份建任务入口。预期的“没有可用卷”以结果返回，避免异常穿过事务代理后将建任务事务标记为回滚；
     * 切卷过程本身的异常仍向外抛出，防止提交部分完成的卷状态变更。
     */
    @Transactional(rollbackFor = Exception.class)
    public SelectionResult selectActiveForNewBackupResult(long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("预计写入容量不能为负数");
        StorageVolumeEntity active = volumeMapper.findActiveForUpdate();
        if (active != null && hasCapacity(active, Math.max(requiredBytes, 1L))) {
            return SelectionResult.available(active);
        }
        if (!configs.getBoolean("storage.volume-switch.enabled", false)) {
            return SelectionResult.unavailable("STORAGE_VOLUME_UNAVAILABLE", "活动热备存储卷不可用或剩余空间不足");
        }
        StorageVolumeEntity standby;
        try {
            standby = selectStandbyForNewBackup(Math.max(requiredBytes, 1L));
        } catch (StorageVolumeOperationException unavailable) {
            alerts.noWritableVolume(unavailable.getMessage());
            return SelectionResult.unavailable("STORAGE_VOLUME_UNAVAILABLE", unavailable.getMessage());
        }
        try {
            lifecycleService.setActive(standby.getId(), 0L);
        } catch (StorageVolumeUnavailableException unavailable) {
            alerts.noWritableVolume(unavailable.getMessage());
            return SelectionResult.unavailable("STORAGE_VOLUME_UNAVAILABLE", unavailable.getMessage());
        }
        return SelectionResult.available(standby);
    }

    /** 其他写入流程保持原有快速失败语义。 */
    @Transactional(rollbackFor = Exception.class)
    public StorageVolumeEntity selectActiveForNewBackup(long requiredBytes) {
        SelectionResult result = selectActiveForNewBackupResult(requiredBytes);
        if (!result.available()) throw new StorageVolumeOperationException(result.failureMessage());
        return result.volume();
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

    public record SelectionResult(StorageVolumeEntity volume, String failureCode, String failureMessage) {
        public static SelectionResult available(StorageVolumeEntity volume) {
            if (volume == null || volume.getId() == null) throw new IllegalArgumentException("可用存储卷不能为空");
            return new SelectionResult(volume, null, null);
        }

        public static SelectionResult unavailable(String code, String message) {
            return new SelectionResult(null, code, message);
        }

        public boolean available() {
            return volume != null;
        }
    }
}
