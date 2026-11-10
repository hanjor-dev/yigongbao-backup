package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderStorageLocationServiceTest {
    @Test
    void missingReplicaReturnsStableInaccessibleLocationInsteadOfThrowing() {
        ArtifactReplicaResolver replicas = mock(ArtifactReplicaResolver.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        OrderPackageEntity pkg = new OrderPackageEntity();
        pkg.setId(9L); pkg.setStorageVolumeId(3L); pkg.setStorageKey("orders/O-9.zip");
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(3L); volume.setDisplayName("历史卷"); volume.setTier("HOT"); volume.setMountStatus("ONLINE");
        when(volumes.selectBatchIds(anyCollection())).thenReturn(java.util.List.of(volume));
        when(replicas.project(any(), any())).thenReturn(java.util.Map.of());

        OrderStorageLocationService.LocationView result = new OrderStorageLocationService(replicas, volumes).locate(pkg);

        assertThat(result.accessible()).isFalse();
        assertThat(result.storageVolumeId()).isEqualTo(3L);
        assertThat(result.storageKey()).isEqualTo("orders/O-9.zip");
        assertThat(result.unavailableReason()).contains("未找到可用文件副本");
        assertThat(result.hotRollup()).isEqualTo("ABSENT");
        assertThat(result.coldRollup()).isEqualTo("ABSENT");
    }


    @Test
    void missingReplicaAndMissingFallbackVolumeReportsLedgerLoss() {
        ArtifactReplicaResolver replicas = mock(ArtifactReplicaResolver.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        OrderPackageEntity pkg = new OrderPackageEntity();
        pkg.setId(9L); pkg.setStorageVolumeId(3L); pkg.setStorageKey("orders/O-9.zip");
        when(replicas.project(any(), any())).thenReturn(java.util.Map.of());
        when(volumes.selectBatchIds(anyCollection())).thenReturn(java.util.List.of());

        OrderStorageLocationService.LocationView result = new OrderStorageLocationService(replicas, volumes).locate(pkg);

        assertThat(result.accessible()).isFalse();
        assertThat(result.unavailableReason()).contains("STORAGE_VOLUME_MISSING");
    }
}
