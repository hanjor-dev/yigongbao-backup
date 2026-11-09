package com.yigongbao.backup.cold;

import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HotCleanupServiceTest {
    @Test void deletesRecordedHotReplicaRatherThanCurrentActivePath() throws Exception {
        ColdBatchArtifactMapper artifacts = mock(ColdBatchArtifactMapper.class);
        ColdBatchArtifactEntity cold = new ColdBatchArtifactEntity(); cold.setArtifactId(10L); cold.setStorageVolumeId(2L); cold.setWriteStatus("VERIFIED");
        when(artifacts.selectList(any())).thenReturn(List.of(cold));
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        OrderPackageEntity pkg = new OrderPackageEntity(); pkg.setId(10L); pkg.setStorageKey("orders/a.zip");
        when(packages.selectById(10L)).thenReturn(pkg);
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        ArtifactCopyEntity hot = new ArtifactCopyEntity(); hot.setId(20L); hot.setStorageVolumeId(1L); hot.setStorageKey("orders/a.zip");
        when(copies.selectOne(any())).thenReturn(hot);
        VolumeArtifactStore volumes = mock(VolumeArtifactStore.class);

        StorageVolumeMapper volumeMapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity(); volume.setTier("HOT");
        when(volumeMapper.selectById(1L)).thenReturn(volume);
        assertThat(new HotCleanupService(volumes, artifacts, packages, copies, volumeMapper).cleanup(3L, "CB-3", List.of("orders/a.zip")).deleted()).isEqualTo(1);
        verify(volumes).delete(1L, "orders/a.zip");
        assertThat(hot.getLifecycle()).isEqualTo("RETIRED");
        assertThat(hot.getState()).isEqualTo("ABSENT");
    }

    @Test void doesNotDeleteReplicaOutsideHotTier() throws Exception {
        ColdBatchArtifactMapper artifacts = mock(ColdBatchArtifactMapper.class);
        ColdBatchArtifactEntity cold = new ColdBatchArtifactEntity(); cold.setArtifactId(10L); cold.setStorageVolumeId(2L); cold.setWriteStatus("VERIFIED");
        when(artifacts.selectList(any())).thenReturn(List.of(cold));
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        OrderPackageEntity pkg = new OrderPackageEntity(); pkg.setId(10L); pkg.setStorageKey("orders/a.zip"); when(packages.selectById(10L)).thenReturn(pkg);
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        ArtifactCopyEntity replica = new ArtifactCopyEntity(); replica.setStorageVolumeId(3L); replica.setStorageKey("orders/a.zip"); when(copies.selectOne(any())).thenReturn(replica);
        StorageVolumeMapper volumeMapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity importVolume = new StorageVolumeEntity(); importVolume.setTier("IMPORT"); when(volumeMapper.selectById(3L)).thenReturn(importVolume);
        VolumeArtifactStore volumes = mock(VolumeArtifactStore.class);

        assertThat(new HotCleanupService(volumes, artifacts, packages, copies, volumeMapper).cleanup(3L, "CB-3", List.of("orders/a.zip")).deleted()).isZero();
        verify(volumes, never()).delete(anyLong(), anyString());
    }
}
