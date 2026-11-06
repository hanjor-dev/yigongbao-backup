package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 存储卷发现必须只识别候选项，不能对未知挂载点产生写入。 */
class StorageVolumeDiscoveryServiceTest {

    @Test
    void shouldDiscoverCandidateWithoutCreatingBusinessDirectory() throws Exception {
        Path mount = Files.createTempDirectory("volume-discovery");

        List<DiscoveredStorageVolume> candidates = new StorageVolumeDiscoveryService()
                .discoverMountedPaths(List.of(mount));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.mountPath()).isEqualTo(mount.toAbsolutePath().normalize());
            assertThat(candidate.rootPath()).isEqualTo(mount.resolve("yigongbao-backup").toAbsolutePath().normalize());
            assertThat(candidate.accessType()).isEqualTo(StorageAccessType.LOCAL);
        });
        assertThat(Files.exists(mount.resolve("yigongbao-backup"))).isFalse();
    }
}
