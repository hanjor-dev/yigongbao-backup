package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;

/** 为新建写入任务选择唯一活动热备卷。既有任务必须使用其已固化的卷 ID。 */
@Service
@RequiredArgsConstructor
public class ActiveVolumeSelectionService {
    private final StorageVolumeMapper volumeMapper;
    private final StorageVolumeHealthService healthService;

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
    public StorageVolumeEntity selectActiveForNewBackup(long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("预计写入容量不能为负数");
        StorageVolumeEntity volume = requireActiveHotVolume();
        if (!hasCapacity(volume, requiredBytes)) {
            throw new StorageVolumeOperationException("活动热备存储卷剩余空间不足");
        }
        return volume;
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
        return health.accessible() && health.writable() && health.usableBytes() >= requiredBytes;
    }
}
