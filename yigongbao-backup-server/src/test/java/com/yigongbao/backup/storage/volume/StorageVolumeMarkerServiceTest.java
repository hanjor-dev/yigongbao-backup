package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 受控目录卷标记用于识别盘符变化后的同一逻辑卷。 */
class StorageVolumeMarkerServiceTest {

    @Test
    void shouldRecognizeSameVolumeAfterMountPathChanges() throws Exception {
        Path firstMount = Files.createTempDirectory("volume-a");
        Path secondMount = Files.createTempDirectory("volume-b");
        UUID volumeId = UUID.randomUUID();
        StorageVolumeMarkerService markerService = new StorageVolumeMarkerService();

        markerService.initialize(firstMount.resolve("yigongbao-backup"), volumeId);
        Files.createDirectories(secondMount.resolve("yigongbao-backup"));
        Files.copy(firstMount.resolve("yigongbao-backup/.yigongbao-volume.json"),
                secondMount.resolve("yigongbao-backup/.yigongbao-volume.json"));

        assertThat(markerService.verify(secondMount.resolve("yigongbao-backup"), volumeId).valid()).isTrue();
        assertThat(markerService.verify(secondMount.resolve("yigongbao-backup"), UUID.randomUUID()).valid()).isFalse();
    }
}
