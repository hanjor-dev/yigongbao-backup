package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void shouldRejectMountedMediumWhosePhysicalFingerprintChanged() throws Exception {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = new StorageVolumeMarkerService();
        StorageVolumeDiscoveryService discovery = mock(StorageVolumeDiscoveryService.class);
        java.nio.file.Path mount = Files.createTempDirectory("volume-fingerprint");
        StorageVolumeEntity registered = writable(8L, mount);
        registered.setMountPath(mount.toString());
        initializeMarker(registered);
        registered.setVolumeFingerprint("VSN|registered|NTFS|1000");
        when(mapper.selectById(8L)).thenReturn(registered);
        when(discovery.discoverMountedPaths(any())).thenReturn(java.util.List.of(
                new DiscoveredStorageVolume(mount, mount, StorageAccessType.LOCAL,
                        "disk", "NTFS", "replacement", 1000L, 900L, 100L)));
        VolumeArtifactStoreRouter router = new VolumeArtifactStoreRouter(mapper, markers, discovery);

        assertThatThrownBy(() -> router.open(8L, "orders/package.zip"))
                .isInstanceOf(StorageVolumeUnavailableException.class)
                .hasMessageContaining("物理卷");
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
        volume.setMountPath(volume.getRootPath());
        DiscoveredStorageVolume discovered = new StorageVolumeDiscoveryService()
                .discoverMountedPaths(java.util.List.of(java.nio.file.Path.of(volume.getMountPath()))).getFirst();
        volume.setVolumeFingerprint(StorageVolumeLifecycleService.fingerprint(discovered));
    }
}
