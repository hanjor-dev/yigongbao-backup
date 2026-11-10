package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 统一解析订单包的逻辑卷位置和可访问性。 */
@Service
@RequiredArgsConstructor
public class OrderStorageLocationService {
    private final ArtifactReplicaResolver replicas;
    private final StorageVolumeMapper volumes;

    public LocationView locate(OrderPackageEntity pkg) {
        if (pkg == null) return new LocationView(null, null, null, null, "UNKNOWN", false, "订单包不存在", null, null, null, null, null);
        try {
            var replica = replicas.resolveReadable("ORDER_PACKAGE", pkg.getId());
            StorageVolumeEntity volume = volumes.selectById(replica.storageVolumeId());
            if (volume == null) return fallback(pkg, "存储卷台账不存在");
            return new LocationView(volume.getId(), volume.getDisplayName(), volume.getTier(), replica.storageKey(),
                    volume.getMountStatus(), true, null, volume.getAdminStatus(), volume.getOperationStatus(),
                    volume.getArchiveNote(), volume.getOfflineLabelNote(), volume.getPhysicalLocation());
        } catch (IllegalStateException unavailable) {
            return fallback(pkg, unavailable.getMessage());
        }
    }

    private LocationView fallback(OrderPackageEntity pkg, String reason) {
        StorageVolumeEntity volume = pkg.getStorageVolumeId() == null ? null : volumes.selectById(pkg.getStorageVolumeId());
        return new LocationView(pkg.getStorageVolumeId(), volume == null ? null : volume.getDisplayName(),
                volume == null ? null : volume.getTier(), pkg.getStorageKey(),
                volume == null ? "UNAVAILABLE" : volume.getMountStatus(), false, reason,
                volume == null ? null : volume.getAdminStatus(), volume == null ? null : volume.getOperationStatus(),
                volume == null ? null : volume.getArchiveNote(), volume == null ? null : volume.getOfflineLabelNote(),
                volume == null ? null : volume.getPhysicalLocation());
    }

    public record LocationView(Long storageVolumeId, String storageVolumeName, String storageTier, String storageKey,
                               String mountStatus, boolean accessible, String unavailableReason, String adminStatus,
                               String operationStatus, String archiveNote, String offlineLabelNote, String physicalLocation) { }
}
