package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 为新建写入任务选择唯一活动热备卷。既有任务必须使用其已固化的卷 ID。 */
@Service
@RequiredArgsConstructor
public class ActiveVolumeSelectionService {
    private final StorageVolumeMapper volumeMapper;

    /** 返回当前可写热备卷；没有合格卷时拒绝创建不可执行任务。 */
    public StorageVolumeEntity requireActiveHotVolume() {
        StorageVolumeEntity volume = volumeMapper.selectOne(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, StorageVolumeStatus.Tier.HOT.name())
                .eq(StorageVolumeEntity::getMountStatus, StorageVolumeStatus.Mount.ONLINE.name())
                .eq(StorageVolumeEntity::getWriteStatus, StorageVolumeStatus.Write.ACTIVE.name())
                .eq(StorageVolumeEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (volume == null) {
            throw new StorageVolumeOperationException("没有可用的活动热备存储卷，无法创建写入任务");
        }
        return volume;
    }
}
