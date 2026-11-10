package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 跨卷文件路由不得因同名相对路径发生覆盖。 */
class VolumeArtifactStoreRouterTest {

    @Test
    void shouldRejectMountedDirectoryWhoseMarkerBelongsToAnotherVolume() throws Exception {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        java.nio.file.Path root = Files.createTempDirectory("volume-swapped");
        UUID mountedMarker = UUID.randomUUID();
        new StorageVolumeMarkerService().initialize(root, mountedMarker);
        StorageVolumeEntity registered = writable(7L, root);
        registered.setMarkerJson("{\"volumeId\":\"" + UUID.randomUUID() + "\",\"formatVersion\":1}");
        when(mapper.selectById(7L)).thenReturn(registered);
        VolumeArtifactStoreRouter router = new VolumeArtifactStoreRouter(mapper);

        assertThatThrownBy(() -> router.put(7L, "orders/wrong-disk.zip",
                new ByteArrayInputStream("data".getBytes())))
                .isInstanceOf(StorageVolumeUnavailableException.class)
                .hasMessageContaining("卷标记");
    }

    @Test
    void shouldKeepSameStorageKeyIndependentAcrossVolumes() throws Exception {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity a = writable(1L, Files.createTempDirectory("volume-a"));
        StorageVolumeEntity b = writable(2L, Files.createTempDirectory("volume-b"));
        initializeMarker(a);
        initializeMarker(b);
        when(mapper.selectById(1L)).thenReturn(a);
        when(mapper.selectById(2L)).thenReturn(b);
        VolumeArtifactStoreRouter router = new VolumeArtifactStoreRouter(mapper);

        router.put(1L, "orders/same.zip", new ByteArrayInputStream("A".getBytes()));
        router.put(2L, "orders/same.zip", new ByteArrayInputStream("B".getBytes()));

        assertThat(new String(router.open(1L, "orders/same.zip").readAllBytes())).isEqualTo("A");
        assertThat(new String(router.open(2L, "orders/same.zip").readAllBytes())).isEqualTo("B");
        assertThatThrownBy(() -> router.open(1L, "../escape")).isInstanceOf(IllegalArgumentException.class);
    }

    private static StorageVolumeEntity writable(long id, java.nio.file.Path root) {
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(id);
        volume.setRootPath(root.toString());
        volume.setMountStatus("ONLINE");
        volume.setWriteStatus("ACTIVE");
        volume.setIsDeleted(0);
        return volume;
    }

    private static void initializeMarker(StorageVolumeEntity volume) throws Exception {
        UUID markerId = UUID.randomUUID();
        new StorageVolumeMarkerService().initialize(java.nio.file.Path.of(volume.getRootPath()), markerId);
        volume.setMarkerJson("{\"volumeId\":\"" + markerId + "\",\"formatVersion\":1}");
    }
}
