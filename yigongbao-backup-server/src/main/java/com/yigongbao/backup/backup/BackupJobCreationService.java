package com.yigongbao.backup.backup;

import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** 在同一数据库事务内完成选卷与建任务；无可用卷时仍创建带前置失败证据的可审计任务。 */
@Service
@RequiredArgsConstructor
public class BackupJobCreationService {
    private final ActiveVolumeSelectionService volumes;
    private final PersistentJobCoordinator jobs;
    private final OrderPackageMapper packages;
    private final RuntimeConfigService configs;

    @Transactional(rollbackFor = Exception.class)
    public JobEntity create(String type, String key, long actor, String config, long requiredBytes) {
        PersistentJobCoordinator.CreationResult reservation = jobs.reserve(type, key, actor, config);
        if (!reservation.created()) return reservation.job();
        ActiveVolumeSelectionService.SelectionResult selection = volumes.selectActiveForNewBackupResult(requiredBytes);
        if (selection.available()) {
            return jobs.completeCreation(reservation.job(), selection.volume().getId(), null, null);
        }
        return jobs.completeCreation(reservation.job(), null,
                selection.failureCode(), selection.failureMessage());
    }

    /** 候选阶段没有清单大小时，以近期可用订单包均值估算；无历史数据时按每单 500MiB 保守估算。 */
    @Transactional(rollbackFor = Exception.class)
    public JobEntity createForOrders(String type, String key, long actor, String config, int orderCount) {
        if (orderCount < 1) throw new IllegalArgumentException("备份订单数必须大于0");
        var recent = packages.selectList(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getStatus, "AVAILABLE").gt(OrderPackageEntity::getPackageSize, 0L)
                .eq(OrderPackageEntity::getIsDeleted, 0).orderByDesc(OrderPackageEntity::getId).last("LIMIT 100"));
        long average = recent.isEmpty() ? 500L * 1024L * 1024L : Math.max(1L,
                recent.stream().map(OrderPackageEntity::getPackageSize).filter(java.util.Objects::nonNull)
                        .mapToLong(Long::longValue).sum() / recent.size());
        int reservationPercent = configs.getInt("storage.backup-reservation-percent", 200, 100);
        final long required;
        try { required = Math.max(1L, Math.addExact(Math.multiplyExact(Math.multiplyExact(average, orderCount), reservationPercent), 99L) / 100L); }
        catch (ArithmeticException overflow) { throw new IllegalStateException("备份任务预计容量溢出", overflow); }
        return create(type, key, actor, config, required);
    }
}
