package com.yigongbao.backup.storage.location;

import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactReplicaResolverTest {
    @Test void excludesBusyVolumeAndUsesDeterministicReadableVolume() {
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class); StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ArtifactCopyEntity busy = copy(1L, 10L, LocalDateTime.now()); ArtifactCopyEntity ready = copy(2L, 20L, LocalDateTime.now().minusDays(1));
        when(copies.selectList(any())).thenReturn(List.of(busy, ready));
        StorageVolumeEntity a = volume(10L, "HOT", "SEALING"); StorageVolumeEntity b = volume(20L, "COLD", "IDLE");
        when(volumes.selectById(10L)).thenReturn(a); when(volumes.selectById(20L)).thenReturn(b);
        assertThat(new ArtifactReplicaResolver(copies, volumes).resolveReadable("ORDER_PACKAGE", 9L).storageVolumeId()).isEqualTo(20L);
    }
    private static ArtifactCopyEntity copy(long id,long volume,LocalDateTime verified){ ArtifactCopyEntity c=new ArtifactCopyEntity(); c.setId(id);c.setStorageVolumeId(volume);c.setArtifactType("ORDER_PACKAGE");c.setArtifactId(9L);c.setStorageKey("a.zip");c.setLastVerifiedAt(verified);return c; }
    private static StorageVolumeEntity volume(long id,String tier,String operation){ StorageVolumeEntity v=new StorageVolumeEntity();v.setId(id);v.setTier(tier);v.setWriteStatus("COLD".equals(tier)?"READ_ONLY":"ACTIVE");v.setMountStatus("ONLINE");v.setAdminStatus("ENABLED");v.setOperationStatus(operation);v.setIsDeleted(0);return v; }
}
