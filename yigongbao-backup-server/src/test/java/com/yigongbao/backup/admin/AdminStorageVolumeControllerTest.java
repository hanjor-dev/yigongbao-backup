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

        AdminStorageVolumeController.StorageVolumeView view = AdminStorageVolumeController.StorageVolumeView.from(volume, 85);

        assertThat(view.totalBytes()).isEqualTo(100L);
        assertThat(view.usedBytes()).isEqualTo(60L);
        assertThat(view.usedPercent()).isEqualTo(60.0d);
        assertThat(view.warningThresholdPercent()).isEqualTo(85);
        assertThat(view.capacityWarning()).isFalse();
        verifyNoInteractions(health);
    }

    @Test
    void capacityViewWarnsAtTierThresholdAndKeepsUnknownCapacityDistinct() {
        StorageVolumeEntity cold = new StorageVolumeEntity();
        cold.setTier("COLD"); cold.setTotalBytes(1_000L); cold.setUsableBytes(100L);

        AdminStorageVolumeController.StorageVolumeView warning =
                AdminStorageVolumeController.StorageVolumeView.from(cold, 90);

        assertThat(warning.usedPercent()).isEqualTo(90.0d);
        assertThat(warning.warningThresholdPercent()).isEqualTo(90);
        assertThat(warning.capacityWarning()).isTrue();

        StorageVolumeEntity unknown = new StorageVolumeEntity();
        unknown.setTier("HOT"); unknown.setTotalBytes(null); unknown.setUsableBytes(null);
        AdminStorageVolumeController.StorageVolumeView unknownView =
                AdminStorageVolumeController.StorageVolumeView.from(unknown, 85);

        assertThat(unknownView.usedPercent()).isNull();
        assertThat(unknownView.capacityWarning()).isNull();
    }
}
