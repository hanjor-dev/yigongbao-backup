package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeHealthService;

class AdminStorageVolumeControllerTest {
    @Test
    void parsesFileUriReturnedByStorageVolumeScan() {
        assertThat(AdminStorageVolumeController.parseMountPath("file:///D:/")).isEqualTo(Path.of("D:/"));
    }

    @Test
    void capacityViewUsesCachedProbeSnapshotWithoutTouchingFileSystem() {
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(1L); volume.setMountStatus("ONLINE"); volume.setRootPath("D:/managed");
        volume.setTotalBytes(100L); volume.setUsableBytes(40L); volume.setLastErrorMessage(null);

        AdminStorageVolumeController.StorageVolumeView view = AdminStorageVolumeController.StorageVolumeView.from(volume);

        assertThat(view.totalBytes()).isEqualTo(100L);
        assertThat(view.usedBytes()).isEqualTo(60L);
        verifyNoInteractions(health);
    }
}
