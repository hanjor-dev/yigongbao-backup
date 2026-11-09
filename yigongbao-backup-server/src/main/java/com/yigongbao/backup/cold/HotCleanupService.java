package com.yigongbao.backup.cold;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Deletes only verified HOT replicas, always from the replica's recorded source volume. */
public final class HotCleanupService {
    private final VolumeArtifactStore volumes;
    private final ColdBatchArtifactMapper batchArtifacts;
    private final OrderPackageMapper packages;
    private final ArtifactCopyMapper copies;
    private final StorageVolumeMapper volumeMapper;

    public HotCleanupService(VolumeArtifactStore volumes, ColdBatchArtifactMapper batchArtifacts,
                              OrderPackageMapper packages, ArtifactCopyMapper copies, StorageVolumeMapper volumeMapper) {
        this.volumes = volumes;
        this.batchArtifacts = batchArtifacts;
        this.packages = packages;
        this.copies = copies;
        this.volumeMapper = volumeMapper;
    }

    public Result cleanup(long batchId, String batchNo, List<String> requestedKeys) {
        List<ColdBatchArtifactEntity> verified = batchArtifacts.selectList(Wrappers.<ColdBatchArtifactEntity>lambdaQuery()
                .eq(ColdBatchArtifactEntity::getBatchId, batchId).eq(ColdBatchArtifactEntity::getArtifactType, "ORDER_PACKAGE")
                .eq(ColdBatchArtifactEntity::getWriteStatus, "VERIFIED"));
        if (verified.isEmpty()) throw new IllegalStateException("冷备批次没有已校验副本");
        List<String> failures = new ArrayList<>(); int deleted = 0;
        for (ColdBatchArtifactEntity cold : verified) {
            OrderPackageEntity pkg = packages.selectById(cold.getArtifactId());
            if (pkg == null || pkg.getStorageKey() == null || (requestedKeys != null && !requestedKeys.contains(pkg.getStorageKey()))) continue;
            ArtifactCopyEntity hot = copies.selectOne(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                    .eq(ArtifactCopyEntity::getArtifactType, "ORDER_PACKAGE").eq(ArtifactCopyEntity::getArtifactId, pkg.getId())
                    .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE").ne(ArtifactCopyEntity::getStorageVolumeId, cold.getStorageVolumeId()).last("LIMIT 1"));
            if (hot == null || hot.getStorageVolumeId() == null) continue;
            var sourceVolume = volumeMapper.selectById(hot.getStorageVolumeId());
            if (sourceVolume == null || !StorageVolumeStatus.Tier.HOT.name().equals(sourceVolume.getTier())) continue;
            try {
                volumes.delete(hot.getStorageVolumeId(), hot.getStorageKey());
                hot.setState("ABSENT"); hot.setLifecycle("RETIRED"); copies.updateById(hot); deleted++;
            } catch (IOException | RuntimeException failure) { failures.add(pkg.getStorageKey()); }
        }
        if (!failures.isEmpty()) throw new IllegalStateException("热备清理不完整: " + failures);
        return new Result(batchNo, deleted);
    }

    public record Result(String batchId, int deleted) { }
}
