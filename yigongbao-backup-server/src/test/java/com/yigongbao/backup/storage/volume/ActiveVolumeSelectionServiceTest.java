package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveVolumeSelectionServiceTest {
    @Test void persistsAlertWhenNoStandbyCanAcceptNewBackup() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeAlertService alerts = mock(StorageVolumeAlertService.class);
        when(configs.getBoolean("storage.volume-switch.enabled", false)).thenReturn(true);
        when(mapper.selectList(any())).thenReturn(List.of());
        var service = new ActiveVolumeSelectionService(mapper, mock(StorageVolumeHealthService.class),
                mock(StorageVolumeLifecycleService.class), configs, alerts);
        assertThatThrownBy(() -> service.selectActiveForNewBackup(100L))
                .isInstanceOf(StorageVolumeOperationException.class);
        verify(alerts).noWritableVolume(anyString());
    }

    @Test void selectsLowestPriorityNumberEligibleStandby() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity slow = volume(2L, 20); StorageVolumeEntity preferred = volume(3L, 1);
        when(mapper.selectList(any())).thenReturn(List.of(slow, preferred));
        when(health.probe(any())).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 100L, null, null, 0L));
        assertThat(new ActiveVolumeSelectionService(mapper, health, mock(StorageVolumeLifecycleService.class), mock(RuntimeConfigService.class), mock(StorageVolumeAlertService.class)).selectStandbyForNewBackup(1L).getId()).isEqualTo(3L);
    }

    @Test void switchesOnlyWhenNewBackupNeedsAnEligibleVolume() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeLifecycleService lifecycle = mock(StorageVolumeLifecycleService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity active = volume(1L, 0); active.setWriteStatus("ACTIVE");
        StorageVolumeEntity standby = volume(2L, 1);
        when(mapper.findActiveForUpdate()).thenReturn(active);
        when(mapper.selectList(any())).thenReturn(List.of(standby));
        when(health.probe(active)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 1L, null, null, 0L));
        when(health.probe(standby)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 80L, null, null, 0L));
        when(configs.getBoolean("storage.volume-switch.enabled", false)).thenReturn(true);
        when(lifecycle.setActive(2L, 0L)).thenReturn(standby);

        StorageVolumeEntity selected = new ActiveVolumeSelectionService(mapper, health, lifecycle, configs, mock(StorageVolumeAlertService.class))
                .selectActiveForNewBackup(20L);

        assertThat(selected.getId()).isEqualTo(2L);
        verify(lifecycle).setActive(2L, 0L);
    }

    @Test void switchesWhenActiveVolumeHasReachedConfiguredUsageThreshold() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeLifecycleService lifecycle = mock(StorageVolumeLifecycleService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity active = volume(1L, 0); active.setWriteStatus("ACTIVE");
        StorageVolumeEntity standby = volume(2L, 1);
        when(mapper.findActiveForUpdate()).thenReturn(active);
        when(mapper.selectList(any())).thenReturn(List.of(standby));
        when(health.probe(active)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 9L, null, null, 0L));
        when(health.probe(standby)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 80L, null, null, 0L));
        when(configs.getBoolean("storage.volume-switch.enabled", false)).thenReturn(true);
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);
        when(lifecycle.setActive(2L, 0L)).thenReturn(standby);

        StorageVolumeEntity selected = new ActiveVolumeSelectionService(mapper, health, lifecycle, configs, mock(StorageVolumeAlertService.class))
                .selectActiveForNewBackup(1L);

        assertThat(selected.getId()).isEqualTo(2L);
        verify(lifecycle).setActive(2L, 0L);
    }
    private static StorageVolumeEntity volume(long id, int priority) {
        StorageVolumeEntity value = new StorageVolumeEntity(); value.setId(id); value.setPriority(priority);
        value.setTier("HOT"); value.setMountStatus("ONLINE"); value.setWriteStatus("STANDBY"); value.setIsDeleted(0); return value;
    }
}
