package com.yigongbao.backup.storage.location;

import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactReplicaResolverTest {
    @Test void excludesBusyVolumeAndUsesDeterministicReadableVolume() {
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class); StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ArtifactCopyEntity busy = copy(1L, 10L, LocalDateTime.now()); ArtifactCopyEntity ready = copy(2L, 20L, LocalDateTime.now().minusDays(1));
        when(copies.selectList(any())).thenReturn(List.of(busy, ready));
        StorageVolumeEntity a = volume(10L, "HOT", "SEALING"); StorageVolumeEntity b = volume(20L, "COLD", "IDLE");
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(a, b));
        assertThat(new ArtifactReplicaResolver(copies, volumes).resolveReadable("ORDER_PACKAGE", 9L).storageVolumeId()).isEqualTo(20L);
    }

    @Test void projectsOfflineColdCopyAsPresentButNotReadable() {
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ArtifactCopyEntity coldCopy = copy(1L, 10L, LocalDateTime.now());
        coldCopy.setLifecycle("ACTIVE"); coldCopy.setState("VERIFIED"); coldCopy.setIsDeleted(0);
        StorageVolumeEntity cold = volume(10L, "COLD", "IDLE");
        cold.setDisplayName("归档盘-A"); cold.setMountStatus("OFFLINE");
        cold.setOfflineLabelNote("标签：2026-Q1");
        cold.setPhysicalLocation("档案室 A-03");
        cold.setOrderCreatedFrom(LocalDateTime.of(2026, 1, 1, 8, 0));
        cold.setOrderCreatedTo(LocalDateTime.of(2026, 3, 15, 18, 0));
        when(copies.selectList(any())).thenReturn(List.of(coldCopy));
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(cold));

        Map<Long, ArtifactReplicaResolver.ArtifactStorageProjection> result =
                new ArtifactReplicaResolver(copies, volumes).project("ORDER_PACKAGE", List.of(9L));

        ArtifactReplicaResolver.ArtifactStorageProjection projection = result.get(9L);
        assertThat(projection.hotPresent()).isFalse();
        assertThat(projection.coldPresent()).isTrue();
        assertThat(projection.readable()).isFalse();
        assertThat(projection.storageVolumeId()).isEqualTo(10L);
        assertThat(projection.storageTier()).isEqualTo("COLD");
        assertThat(projection.mountStatus()).isEqualTo("OFFLINE");
        assertThat(projection.offlineLabelNote()).isEqualTo("标签：2026-Q1");
        assertThat(projection.physicalLocation()).isEqualTo("档案室 A-03");
        assertThat(projection.periodLabel()).isEqualTo("20260101-20260315");
    }

    @Test void keepsDamagedCopyInTierDiagnosticsWithoutRoutingIt() {
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ArtifactCopyEntity healthyHot = copy(1L, 10L, LocalDateTime.now());
        healthyHot.setLifecycle("ACTIVE"); healthyHot.setState("VERIFIED"); healthyHot.setIsDeleted(0);
        ArtifactCopyEntity damagedCold = copy(2L, 20L, LocalDateTime.now().minusHours(1));
        damagedCold.setLifecycle("ACTIVE"); damagedCold.setState("DAMAGED"); damagedCold.setIsDeleted(0);
        StorageVolumeEntity hot = volume(10L, "HOT", "IDLE");
        StorageVolumeEntity cold = volume(20L, "COLD", "IDLE");
        when(copies.selectList(any())).thenReturn(List.of(healthyHot, damagedCold));
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(hot, cold));

        ArtifactReplicaResolver.ArtifactStorageProjection projection =
                new ArtifactReplicaResolver(copies, volumes).project("ORDER_PACKAGE", List.of(9L)).get(9L);

        assertThat(projection.readable()).isTrue();
        assertThat(projection.storageVolumeId()).isEqualTo(10L);
        assertThat(projection.hotRollup()).isEqualTo("PRESENT");
        assertThat(projection.coldRollup()).isEqualTo("ERROR");
    }

    @Test void keepsMissingVolumeCopyAsDiagnosticWhileRoutingHealthyCopy() {
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ArtifactCopyEntity healthyHot = copy(1L, 10L, LocalDateTime.now());
        ArtifactCopyEntity missingLedger = copy(2L, 99L, LocalDateTime.now().minusHours(1));
        StorageVolumeEntity hot = volume(10L, "HOT", "IDLE");
        when(copies.selectList(any())).thenReturn(List.of(healthyHot, missingLedger));
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(hot));

        ArtifactReplicaResolver.ArtifactStorageProjection projection =
                new ArtifactReplicaResolver(copies, volumes).project("ORDER_PACKAGE", List.of(9L)).get(9L);

        assertThat(projection.readable()).isTrue();
        assertThat(projection.storageVolumeId()).isEqualTo(10L);
        assertThat(projection.diagnosticReason()).contains("STORAGE_VOLUME_MISSING").contains("99");
    }
    private static ArtifactCopyEntity copy(long id,long volume,LocalDateTime verified){ ArtifactCopyEntity c=new ArtifactCopyEntity(); c.setId(id);c.setStorageVolumeId(volume);c.setArtifactType("ORDER_PACKAGE");c.setArtifactId(9L);c.setStorageKey("a.zip");c.setState("VERIFIED");c.setLifecycle("ACTIVE");c.setIsDeleted(0);c.setLastVerifiedAt(verified);return c; }
    private static StorageVolumeEntity volume(long id,String tier,String operation){ StorageVolumeEntity v=new StorageVolumeEntity();v.setId(id);v.setTier(tier);v.setWriteStatus("COLD".equals(tier)?"READ_ONLY":"ACTIVE");v.setMountStatus("ONLINE");v.setAdminStatus("ENABLED");v.setOperationStatus(operation);v.setIsDeleted(0);return v; }
}
