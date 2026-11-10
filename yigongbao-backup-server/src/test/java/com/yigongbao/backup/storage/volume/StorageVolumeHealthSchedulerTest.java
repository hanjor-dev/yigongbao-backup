package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StorageVolumeHealthSchedulerTest {
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
}
