package com.yigongbao.backup.storage.volume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import java.util.List;
import java.nio.file.Path;
import java.util.UUID;

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
                mock(StorageVolumeHealthService.class), mock(AuditLogService.class), new ObjectMapper(), mock(JobMapper.class));

        assertThatThrownBy(() -> service.setActive(1L, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("在线 HOT 卷");
    }

    @Test
    void shouldRejectActiveStateThroughGenericTransition() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity online = new StorageVolumeEntity();
        online.setId(1L); online.setTier("HOT"); online.setMountStatus("ONLINE"); online.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(online);
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper,
                mock(StorageVolumeDiscoveryService.class), mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(AuditLogService.class), new ObjectMapper(), mock(JobMapper.class));

        assertThatThrownBy(() -> service.changeTier(1L, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.ACTIVE, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("setActive");
    }

    @Test
    void confirmsMountedVolumeAgainstPersistedRandomMarkerId() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(1L); volume.setRootPath("D:/managed"); volume.setIsDeleted(0);
        UUID markerId = UUID.randomUUID();
        volume.setMarkerJson("{\"volumeId\":\"" + markerId + "\",\"formatVersion\":1}");
        when(mapper.selectById(1L)).thenReturn(volume);
        when(markers.verify(Path.of("D:/managed"), markerId))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 1L, 1L, null, null, 0L));
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper,
                mock(StorageVolumeDiscoveryService.class), markers, health, mock(AuditLogService.class), new ObjectMapper(), mock(JobMapper.class));

        service.confirmMounted(1L, 100L);
    }

    @Test
    void activatesEligibleOnlineHotVolumeThroughControlledTransition() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(1L); volume.setTier("HOT"); volume.setMountStatus("ONLINE"); volume.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(volume);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper,
                mock(StorageVolumeDiscoveryService.class), mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(AuditLogService.class), new ObjectMapper(), mock(JobMapper.class));

        service.setActive(1L, 100L);

        assertThat(volume.getWriteStatus()).isEqualTo("ACTIVE");
        verify(mapper).updateById(volume);
    }
}
