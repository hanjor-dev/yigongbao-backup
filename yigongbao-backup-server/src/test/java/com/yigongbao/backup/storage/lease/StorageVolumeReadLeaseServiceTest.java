package com.yigongbao.backup.storage.lease;

import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class StorageVolumeReadLeaseServiceTest {
    @Test void exposesAtomicAcquireAndOfflineBoundary() throws Exception {
        Method acquire=StorageVolumeReadLeaseService.class.getMethod("acquire",String.class,long.class,String.class,String.class,java.time.Duration.class);
        Method offline=StorageVolumeReadLeaseService.class.getMethod("beginOffline",long.class,long.class);
        assertThat(acquire.getReturnType()).isEqualTo(StorageVolumeReadLeaseService.LeasedArtifact.class);
        assertThat(offline.getReturnType()).isEqualTo(StorageVolumeEntity.class);
        assertThat(AutoCloseable.class).isAssignableFrom(StorageVolumeReadLeaseService.LeasedArtifact.class);
    }
}
