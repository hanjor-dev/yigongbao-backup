package com.yigongbao.backup.storage.volume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 存储卷状态机的关键安全边界测试。 */
class StorageVolumeLifecycleServiceTest {

    @Test
    void shouldRejectActivatingOfflineVolume() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity offline = new StorageVolumeEntity();
        offline.setId(1L);
        offline.setTier(StorageVolumeStatus.Tier.HOT.name());
        offline.setMountStatus(StorageVolumeStatus.Mount.OFFLINE.name());
        offline.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(offline);
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper,
                mock(StorageVolumeDiscoveryService.class), mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(AuditLogService.class), new ObjectMapper());

        assertThatThrownBy(() -> service.setActive(1L, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("在线 HOT 卷");
    }
}
