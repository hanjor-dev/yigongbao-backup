package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 统一解析订单包的逻辑卷位置和可访问性。 */
@Service
@RequiredArgsConstructor
public class OrderStorageLocationService {
    private final ArtifactReplicaResolver replicas;
    private final StorageVolumeMapper volumes;

    public LocationView locate(OrderPackageEntity pkg) {
        if (pkg == null) return missing();
        return locateAll(List.of(pkg)).getOrDefault(pkg.getId(), missing());
    }

    /** 一次加载本页全部副本和卷状态，避免订单列表逐行查询。 */
    public Map<Long, LocationView> locateAll(Collection<OrderPackageEntity> packages) {
        if (packages == null || packages.isEmpty()) return Map.of();
        List<OrderPackageEntity> rows = packages.stream().filter(Objects::nonNull).toList();
        if (rows.isEmpty()) return Map.of();
        Map<Long, ArtifactReplicaResolver.ArtifactStorageProjection> projections = replicas.project("ORDER_PACKAGE",
                rows.stream().map(OrderPackageEntity::getId).toList());
        List<Long> fallbackVolumeIds = rows.stream().filter(pkg -> !projections.containsKey(pkg.getId()))
                .map(OrderPackageEntity::getStorageVolumeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, StorageVolumeEntity> fallbackVolumes = new HashMap<>();
        if (!fallbackVolumeIds.isEmpty()) {
            List<StorageVolumeEntity> volumeRows = volumes.selectBatchIds(fallbackVolumeIds);
            if (volumeRows != null) volumeRows.forEach(volume -> fallbackVolumes.put(volume.getId(), volume));
        }
        Map<Long, LocationView> result = new LinkedHashMap<>();
        for (OrderPackageEntity pkg : rows) {
            ArtifactReplicaResolver.ArtifactStorageProjection projection = projections.get(pkg.getId());
            StorageVolumeEntity fallbackVolume = fallbackVolumes.get(pkg.getStorageVolumeId());
            String fallbackReason = pkg.getStorageVolumeId() != null && fallbackVolume == null
                    ? "STORAGE_VOLUME_MISSING：订单包引用的存储卷台账不存在或已删除"
                    : "未找到可用文件副本";
            result.put(pkg.getId(), projection == null ? fallback(pkg, fallbackVolume, fallbackReason)
                    : new LocationView(projection.storageVolumeId(), projection.storageVolumeName(), projection.storageTier(),
                    projection.storageKey(), projection.mountStatus(), projection.readable(), projection.unavailableReason(),
                    projection.adminStatus(), projection.operationStatus(), projection.archiveNote(), projection.offlineLabelNote(),
                    projection.physicalLocation(), projection.orderCreatedFrom(), projection.orderCreatedTo(),
                    projection.periodLabel(), projection.hotRollup(), projection.coldRollup(), projection.diagnosticReason()));
        }
        return result;
    }

    private static LocationView fallback(OrderPackageEntity pkg, StorageVolumeEntity volume, String reason) {
        return new LocationView(pkg.getStorageVolumeId(), volume == null ? null : volume.getDisplayName(),
                volume == null ? null : volume.getTier(), pkg.getStorageKey(),
                volume == null ? "UNAVAILABLE" : volume.getMountStatus(), false, reason,
                volume == null ? null : volume.getAdminStatus(), volume == null ? null : volume.getOperationStatus(),
                volume == null ? null : volume.getArchiveNote(), volume == null ? null : volume.getOfflineLabelNote(),
                volume == null ? null : volume.getPhysicalLocation(), volume == null ? null : volume.getOrderCreatedFrom(),
                volume == null ? null : volume.getOrderCreatedTo(), volume == null ? null : volume.periodLabel(),
                "ABSENT", "ABSENT", null);
    }

    private static LocationView missing() {
        return new LocationView(null, null, null, null, "UNKNOWN", false, "订单包不存在",
                null, null, null, null, null, null, null, null, "ABSENT", "ABSENT", null);
    }

    public record LocationView(Long storageVolumeId, String storageVolumeName, String storageTier, String storageKey,
                               String mountStatus, boolean accessible, String unavailableReason, String adminStatus,
                               String operationStatus, String archiveNote, String offlineLabelNote, String physicalLocation,
                               java.time.LocalDateTime orderCreatedFrom, java.time.LocalDateTime orderCreatedTo,
                               String periodLabel,
                               String hotRollup, String coldRollup, String diagnosticReason) { }
}
