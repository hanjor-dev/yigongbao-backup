package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActiveVolumeSelectionServiceTest {
    @Test void selectsLowestPriorityNumberEligibleStandby() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity slow = volume(2L, 20); StorageVolumeEntity preferred = volume(3L, 1);
        when(mapper.selectList(any())).thenReturn(List.of(slow, preferred));
        when(health.probe(any())).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 100L, null, null, 0L));
        assertThat(new ActiveVolumeSelectionService(mapper, health).selectStandbyForNewBackup(1L).getId()).isEqualTo(3L);
    }
    private static StorageVolumeEntity volume(long id, int priority) {
        StorageVolumeEntity value = new StorageVolumeEntity(); value.setId(id); value.setPriority(priority);
        value.setTier("HOT"); value.setMountStatus("ONLINE"); value.setWriteStatus("STANDBY"); value.setIsDeleted(0); return value;
    }
}
