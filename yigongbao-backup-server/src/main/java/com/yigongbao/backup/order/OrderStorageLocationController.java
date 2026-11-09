package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 为订单页提供脱敏的包所在卷与可访问性，物理路径不下发给普通用户。 */
@RestController
public class OrderStorageLocationController {
    private final OrderPackageMapper packages; private final ArtifactReplicaResolver replicas; private final StorageVolumeMapper volumes;
    public OrderStorageLocationController(OrderPackageMapper packages, ArtifactReplicaResolver replicas, StorageVolumeMapper volumes) { this.packages = packages; this.replicas = replicas; this.volumes = volumes; }
    @GetMapping("/api/v1/orders/{id}/package-location")
    public ApiResponse<LocationView> location(@PathVariable long id) {
        StpUtil.checkLogin();
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery().eq(OrderPackageEntity::getSnapshotId, id).eq(OrderPackageEntity::getStatus, "AVAILABLE").last("LIMIT 1"));
        if (pkg == null) return ApiResponse.ok(new LocationView(null, null, null, "UNKNOWN", false, "订单包不存在"), UUID.randomUUID().toString());
        try {
            var replica = replicas.resolveReadable("ORDER_PACKAGE", pkg.getId());
            StorageVolumeEntity volume = volumes.selectById(replica.storageVolumeId());
            return ApiResponse.ok(new LocationView(volume.getId(), volume.getDisplayName(), volume.getTier(), volume.getMountStatus(), true, null), UUID.randomUUID().toString());
        } catch (StorageVolumeUnavailableException unavailable) {
            StorageVolumeEntity volume = volumes.selectById(pkg.getStorageVolumeId());
            return ApiResponse.ok(new LocationView(pkg.getStorageVolumeId(), volume == null ? null : volume.getDisplayName(), volume == null ? null : volume.getTier(), volume == null ? "UNAVAILABLE" : volume.getMountStatus(), false, unavailable.getMessage()), UUID.randomUUID().toString());
        }
    }
    public record LocationView(Long storageVolumeId, String storageVolumeName, String storageTier, String mountStatus, boolean accessible, String unavailableReason) { }
}
