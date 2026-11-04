package com.yigongbao.backup.storage;

import com.yigongbao.backup.storage.reservation.*;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.api.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PersistentSpaceReservationServiceTest {
    @Test
    void inactiveReservationsMustUseNullInTheGeneratedUniqueKey() throws Exception {
        String schema = Files.readString(Path.of("sql/init/01-schema.sql"));
        String fullInit = Files.readString(Path.of("sql/init/yigongbao-backup-full-init.sql"));

        assertThat(schema).contains("THEN 1 ELSE NULL END");
        assertThat(fullInit).contains("THEN 1 ELSE NULL END");
    }

    @Test
    void capacityProbeMustUseTheArtifactStoreRootBoundAtStartup() {
        assertThat(PersistentSpaceReservationService.class.getConstructors())
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes()).contains(ArtifactStore.class));
    }

    @Test
    void reservationUsesOwningJobId() {
        StoragePoolMapper pools = mock(StoragePoolMapper.class);
        SpaceReservationMapper rows = mock(SpaceReservationMapper.class);
        StoragePoolEntity pool = new StoragePoolEntity();
        pool.setId(7L); pool.setAvailableBytes(1000L);
        when(pools.selectOne(any())).thenReturn(pool);
        when(pools.selectForUpdate(7L)).thenReturn(pool);
        when(rows.sumActive(7L)).thenReturn(0L);

        StorageProbe probe = mock(StorageProbe.class);
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        when(artifactStore.root()).thenReturn(java.nio.file.Path.of("D:/yigongbao-backup/data"));
        when(probe.probe(any())).thenReturn(new StorageProbe.StorageSnapshot(
                "hot", java.nio.file.Path.of("D:/yigongbao-backup/data"), 2000L, 1000L, true));
        new PersistentSpaceReservationService(pools, rows, probe, artifactStore).reserve("hot", 100L, 42L);

        ArgumentCaptor<SpaceReservationEntity> saved = ArgumentCaptor.forClass(SpaceReservationEntity.class);
        verify(rows).insert(saved.capture());
        verify(probe).probe(artifactStore.root().toAbsolutePath().normalize());
        assertEquals(42L, saved.getValue().getJobId());
    }
}
