package com.yigongbao.backup.backup;

import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobPreconditionException;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.archive.VolumeArchiveMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeHealthService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMarkerService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupTargetPreflightServiceTest {
    @Test
    void rejectsAFrozenTargetThatReachedTheCapacityThresholdBeforeExecution() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity volume = writableVolume();
        when(volumes.selectById(7L)).thenReturn(volume);
        when(markers.verify(any(), any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 9L, null, null, 1L));
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);

        assertThatThrownBy(() -> new BackupTargetPreflightService(volumes, markers, health, configs,
                mock(VolumeArchiveMapper.class), new com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy())
                .requireWritable(7L))
                .isInstanceOf(JobPreconditionException.class)
                .extracting(error -> ((JobPreconditionException) error).code())
                .isEqualTo("STORAGE_VOLUME_UNAVAILABLE");
    }

    @Test
    void acceptsTheActiveSuccessorOfAnInProgressArchive() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        VolumeArchiveMapper archives = mock(VolumeArchiveMapper.class);
        StorageVolumeEntity volume = writableVolume();
        volume.setOperationStatus("ARCHIVE_GUARD"); volume.setOperationRefId(91L);
        VolumeArchiveEntity archive = new VolumeArchiveEntity();
        archive.setId(91L); archive.setSuccessorVolumeId(7L); archive.setPhase("VERIFYING"); archive.setIsDeleted(0);
        when(volumes.selectById(7L)).thenReturn(volume);
        when(archives.selectById(91L)).thenReturn(archive);
        when(markers.verify(any(), any())).thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);

        assertThatCode(() -> new BackupTargetPreflightService(volumes, markers, health, configs, archives,
                new com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy()).requireWritable(7L))
                .doesNotThrowAnyException();
    }

    private static StorageVolumeEntity writableVolume() {
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(7L); volume.setTier("HOT"); volume.setMountStatus("ONLINE");
        volume.setWriteStatus("ACTIVE"); volume.setAdminStatus("ENABLED");
        volume.setOperationStatus("IDLE"); volume.setRootPath("D:/backup"); volume.setIsDeleted(0);
        return volume;
    }
}
