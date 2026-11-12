package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class StorageVolumeHealthSchedulerTest {
    @Test
    void healthProbeBringsAnAutomaticallyRecoveredManagedRootBackOnline() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeLifecycleService lifecycle = mock(StorageVolumeLifecycleService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity active = new StorageVolumeEntity();
        active.setId(8L); active.setVolumeCode("volume-d"); active.setRootPath("D:/yigongbao-backup");
        active.setTier("HOT"); active.setAdminStatus("ENABLED");
        active.setWriteStatus("ACTIVE"); active.setMountStatus("UNAVAILABLE"); active.setIsDeleted(0);
        when(volumes.selectList(any())).thenReturn(List.of(active));
        when(lifecycle.recoverMissingManagedRoot(active, 0L)).thenAnswer(invocation -> {
            active.setMountStatus("ONLINE");
            return new StorageVolumeLifecycleService.RootRecoveryResult(true, null);
        });
        when(lifecycle.hasValidMarker(active)).thenReturn(true);
        when(health.probe(active)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));

        new StorageVolumeHealthScheduler(volumes, health, mock(AuditLogService.class), lifecycle, configs)
                .probeManagedVolumes();

        assertThat(active.getMountStatus()).isEqualTo("ONLINE");
        assertThat(active.getLastErrorMessage()).isNull();
        verify(lifecycle).recoverMissingManagedRoot(active, 0L);
    }

    @Test
    void healthProbeOnlyRecordsSwitchRecommendationAndNeverChangesActiveVolume() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeLifecycleService lifecycle = mock(StorageVolumeLifecycleService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity active = new StorageVolumeEntity();
        active.setId(1L); active.setWriteStatus("ACTIVE"); active.setMountStatus("ONLINE"); active.setIsDeleted(0);
        when(volumes.selectList(any())).thenReturn(List.of(active));
        when(lifecycle.hasValidMarker(active)).thenReturn(true);
        when(health.probe(active)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 5L, null, null, 1L));
        when(configs.getBoolean("storage.volume-switch.enabled", false)).thenReturn(true);
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);

        AuditLogService audit = mock(AuditLogService.class);
        StorageVolumeHealthScheduler scheduler = new StorageVolumeHealthScheduler(volumes, health, audit, lifecycle, configs);
        scheduler.probeManagedVolumes();
        scheduler.probeManagedVolumes();

        verify(lifecycle, never()).setActive(anyLong(), anyLong());
        verify(audit, times(1)).append(argThat(event -> "STORAGE_VOLUME_SWITCH_RECOMMENDED".equals(event.action())));
    }

    @Test
    void deliberatelyOfflineColdVolumeIsNeverAutoRecoveredOrBroughtOnline() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeLifecycleService lifecycle = mock(StorageVolumeLifecycleService.class);
        StorageVolumeEntity cold = new StorageVolumeEntity();
        cold.setId(9L); cold.setTier("COLD"); cold.setWriteStatus("READ_ONLY");
        cold.setMountStatus("OFFLINE"); cold.setAdminStatus("ENABLED"); cold.setIsDeleted(0);
        when(volumes.selectList(any())).thenReturn(List.of(cold));
        when(lifecycle.hasValidMarker(cold)).thenReturn(false);

        new StorageVolumeHealthScheduler(volumes, health, mock(AuditLogService.class), lifecycle,
                mock(RuntimeConfigService.class)).probeManagedVolumes();

        assertThat(cold.getMountStatus()).isEqualTo("OFFLINE");
        verify(lifecycle, never()).recoverMissingManagedRoot(any(), anyLong());
    }
}
