package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.archive.VolumeArchiveMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/** 为新建写入任务选择唯一活动热备卷。既有任务必须使用其已固化的卷 ID。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveVolumeSelectionService {
    private final StorageVolumeMapper volumeMapper;
    private final StorageVolumeHealthService healthService;
    private final StorageVolumeLifecycleService lifecycleService;
    private final RuntimeConfigService configs;
    private final StorageVolumeAlertService alerts;
    private final VolumeArchiveMapper archiveMapper;
    private final StorageVolumeAccessPolicy accessPolicy;

    /** 返回当前可写热备卷；没有合格卷时拒绝创建不可执行任务。 */
    @Transactional(rollbackFor = Exception.class)
    public StorageVolumeEntity requireActiveHotVolume() {
        StorageVolumeEntity volume = volumeMapper.findActiveLedgerForUpdate();
        if (volume == null) throw new StorageVolumeOperationException("未配置活动热备存储卷，无法创建写入任务");
        StorageVolumeLifecycleService.RootRecoveryResult recovery =
                lifecycleService.recoverMissingManagedRoot(volume, 0L);
        if (recovery != null && recovery.blocked()) {
            throw new StorageVolumeOperationException(recovery.reason());
        }
        Eligibility eligibility = eligibility(volume, 1L);
        if (!eligibility.allowed()) throw new StorageVolumeOperationException(eligibility.reason());
        return volume;
    }

    /**
     * 订单备份建任务入口。预期的“没有可用卷”以结果返回，避免异常穿过事务代理后将建任务事务标记为回滚；
     * 切卷过程本身的异常仍向外抛出，防止提交部分完成的卷状态变更。
     */
    @Transactional(rollbackFor = Exception.class)
    public SelectionResult selectActiveForNewBackupResult(long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("预计写入容量不能为负数");
        long expectedBytes = Math.max(requiredBytes, 1L);
        StorageVolumeEntity active = volumeMapper.findActiveLedgerForUpdate();
        String activeFailure = null;
        if (active != null) {
            StorageVolumeLifecycleService.RootRecoveryResult recovery =
                    lifecycleService.recoverMissingManagedRoot(active, 0L);
            if (recovery != null && recovery.blocked()) {
                activeFailure = recovery.reason();
            } else {
                Eligibility eligibility = eligibility(active, expectedBytes);
                if (eligibility.allowed()) {
                    if (recovery != null && recovery.recovered()) {
                        log.info("备份选卷前自动恢复受控目录成功｜存储卷ID={}｜卷代码={}｜受控目录={}",
                                active.getId(), active.getVolumeCode(), active.getRootPath());
                    }
                    return SelectionResult.available(active);
                }
                activeFailure = eligibility.reason();
            }
            log.warn("活动热备卷不可用于新备份｜存储卷ID={}｜卷代码={}｜挂载状态={}｜写入状态={}｜操作状态={}｜受控目录={}｜预计写入={}字节｜总容量={}字节｜可用容量={}字节｜原因={}",
                    active.getId(), active.getVolumeCode(), active.getMountStatus(), active.getWriteStatus(),
                    active.getOperationStatus(), active.getRootPath(), expectedBytes, active.getTotalBytes(),
                    active.getUsableBytes(), activeFailure);
        }
        if (!configs.getBoolean("storage.volume-switch.enabled", false)) {
            String reason = active == null ? "未配置活动热备存储卷" : activeFailure;
            return SelectionResult.unavailable("STORAGE_VOLUME_UNAVAILABLE", reason);
        }
        StorageVolumeEntity standby;
        try {
            standby = selectStandbyForNewBackup(expectedBytes);
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
        return eligibility(volume, requiredBytes).allowed();
    }

    private Eligibility eligibility(StorageVolumeEntity volume, long requiredBytes) {
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(volume);
        int threshold = configs.getInt("storage.volume-switch.threshold-percent", 90, 1);
        if (threshold < 1 || threshold > 100) threshold = 90;
        if (!health.accessible()) {
            return Eligibility.denied(health.errorMessage() == null ? "活动热备存储卷不可访问" : health.errorMessage());
        }
        if (!health.writable()) return Eligibility.denied("活动热备存储卷不可写");
        if (health.usableBytes() < requiredBytes) {
            return Eligibility.denied("活动热备存储卷剩余空间不足：需要 " + requiredBytes
                    + " 字节，可用 " + health.usableBytes() + " 字节");
        }
        volume.setTotalBytes(health.totalBytes());
        volume.setUsableBytes(health.usableBytes());
        if (StorageVolumeStatus.Write.STANDBY.name().equals(volume.getWriteStatus())) {
            return accessPolicy.standbyReady(volume, true, threshold)
                    ? Eligibility.permit() : Eligibility.denied("备用热备存储卷状态或容量不满足接替条件");
        }
        StorageVolumeAccessPolicy.AccessDecision access = accessPolicy.writable(volume, guard(volume));
        if (!access.allowed()) return Eligibility.denied(access.message());
        java.math.BigDecimal used = StorageVolumeCapacitySnapshotPolicy.usedPercent(
                health.totalBytes(), health.usableBytes());
        if (used == null) return Eligibility.denied("活动热备存储卷容量快照无效");
        if (used.compareTo(java.math.BigDecimal.valueOf(threshold)) >= 0) {
            return Eligibility.denied("活动热备存储卷使用率已达到切换阈值 " + threshold + "%");
        }
        return Eligibility.permit();
    }

    private StorageVolumeAccessPolicy.ArchiveGuardContext guard(StorageVolumeEntity volume) {
        if (volume == null || volume.getOperationRefId() == null
                || !StorageVolumeStatus.Operation.ARCHIVE_GUARD.name().equals(volume.getOperationStatus())) return null;
        VolumeArchiveEntity archive = archiveMapper.selectById(volume.getOperationRefId());
        return archive == null ? null : new StorageVolumeAccessPolicy.ArchiveGuardContext(
                archive.getId(), archive.getSuccessorVolumeId(), archive.getPhase(),
                !Integer.valueOf(1).equals(archive.getIsDeleted()));
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

    private record Eligibility(boolean allowed, String reason) {
        static Eligibility permit() { return new Eligibility(true, null); }
        static Eligibility denied(String reason) { return new Eligibility(false, reason); }
    }
}
