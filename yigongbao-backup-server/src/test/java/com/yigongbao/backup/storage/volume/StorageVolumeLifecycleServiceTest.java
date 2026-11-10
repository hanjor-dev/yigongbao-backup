package com.yigongbao.backup.storage.volume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import java.util.List;
import java.nio.file.Path;
import java.util.UUID;
import java.nio.file.Files;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 存储卷状态机的关键安全边界测试。 */
class StorageVolumeLifecycleServiceTest {

    @Test
    void firstHealthyManagedVolumeBecomesActiveAutomatically() throws Exception {
        Path mount = Files.createTempDirectory("first-active-volume");
        StorageVolumeDiscoveryService discovery = mock(StorageVolumeDiscoveryService.class);
        when(discovery.discoverMountedPaths(any())).thenReturn(List.of(new DiscoveredStorageVolume(
                mount, mount.resolve("yigongbao-backup"), StorageAccessType.LOCAL, "test", "test")));
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        doAnswer(invocation -> { ((StorageVolumeEntity) invocation.getArgument(0)).setId(1L); return 1; })
                .when(mapper).insert(any(StorageVolumeEntity.class));
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        when(health.probe(any())).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper, discovery,
                new StorageVolumeMarkerService(), health, mock(AuditLogService.class), new ObjectMapper(),
                mock(JobMapper.class), mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class),
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        StorageVolumeEntity registered = service.register(new StorageVolumeLifecycleService.RegisterCommand(
                mount, null, "首卷", StorageVolumeStatus.Tier.HOT, 0), 100L);

        assertThat(registered.getTier()).isEqualTo("HOT");
        assertThat(registered.getMountStatus()).isEqualTo("ONLINE");
        assertThat(registered.getWriteStatus()).isEqualTo("ACTIVE");
        assertThat(registered.getAdminStatus()).isEqualTo("ENABLED");
        assertThat(registered.getOperationStatus()).isEqualTo("IDLE");
    }

    @Test
    void laterHealthyManagedVolumeDefaultsToStandby() throws Exception {
        Path mount = Files.createTempDirectory("later-standby-volume");
        StorageVolumeDiscoveryService discovery = mock(StorageVolumeDiscoveryService.class);
        when(discovery.discoverMountedPaths(any())).thenReturn(List.of(new DiscoveredStorageVolume(
                mount, mount.resolve("yigongbao-backup"), StorageAccessType.LOCAL, "test", "test")));
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity active = new StorageVolumeEntity(); active.setId(9L); active.setWriteStatus("ACTIVE");
        when(mapper.findActiveForUpdate()).thenReturn(active);
        doAnswer(invocation -> { ((StorageVolumeEntity) invocation.getArgument(0)).setId(2L); return 1; })
                .when(mapper).insert(any(StorageVolumeEntity.class));
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        when(health.probe(any())).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper, discovery,
                new StorageVolumeMarkerService(), health, mock(AuditLogService.class), new ObjectMapper(),
                mock(JobMapper.class), mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class),
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        StorageVolumeEntity registered = service.register(new StorageVolumeLifecycleService.RegisterCommand(
                mount, null, "后续卷", StorageVolumeStatus.Tier.HOT, 0), 100L);

        assertThat(registered.getWriteStatus()).isEqualTo("STANDBY");
    }

    @Test
    void genericTransitionCannotTurnVolumeCold() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(8L); volume.setTier("HOT"); volume.setWriteStatus("READ_ONLY"); volume.setIsDeleted(0);
        when(mapper.findByIdForUpdate(8L)).thenReturn(volume);
        StorageVolumeLifecycleService service = service(mapper, mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.changeTier(8L, StorageVolumeStatus.Tier.COLD,
                StorageVolumeStatus.Write.READ_ONLY, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("封存");
    }

    @Test
    void registerRecoversAValidOrphanMarkerLeftByRolledBackRegistration() throws Exception {
        Path mount = Files.createTempDirectory("volume-register-retry");
        Path root = mount.resolve("yigongbao-backup");
        UUID existingMarker = UUID.randomUUID();
        StorageVolumeMarkerService markers = new StorageVolumeMarkerService();
        markers.initialize(root, existingMarker);
        StorageVolumeDiscoveryService discovery = mock(StorageVolumeDiscoveryService.class);
        when(discovery.discoverMountedPaths(any())).thenReturn(List.of(new DiscoveredStorageVolume(
                mount, root, StorageAccessType.LOCAL, "test", "test")));
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        doAnswer(invocation -> { ((StorageVolumeEntity) invocation.getArgument(0)).setId(12L); return 1; })
                .when(mapper).insert(any(StorageVolumeEntity.class));
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        when(health.probe(any())).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        StorageVolumeLifecycleService service = new StorageVolumeLifecycleService(mapper, discovery, markers, health,
                mock(AuditLogService.class), new ObjectMapper(), mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        StorageVolumeEntity registered = service.register(
                new StorageVolumeLifecycleService.RegisterCommand(mount, null, "重试卷", StorageVolumeStatus.Tier.HOT, 0), 100L);

        assertThat(registered.getMarkerJson()).contains(existingMarker.toString());
    }

    @Test
    void shouldRejectActivatingOfflineVolume() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity offline = new StorageVolumeEntity();
        offline.setId(1L);
        offline.setTier(StorageVolumeStatus.Tier.HOT.name());
        offline.setMountStatus(StorageVolumeStatus.Mount.OFFLINE.name());
        offline.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(offline);
        StorageVolumeLifecycleService service = service(mapper, mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

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
        StorageVolumeLifecycleService service = service(mapper, mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

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
        StorageVolumeLifecycleService service = service(mapper, markers, health, mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        service.confirmMounted(1L, 100L);
    }

    @Test
    void activatesEligibleOnlineHotVolumeThroughControlledTransition() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(1L); volume.setTier("HOT"); volume.setMountStatus("ONLINE"); volume.setRootPath("D:/managed");
        volume.setMarkerJson("{\"volumeId\":\"00000000-0000-0000-0000-000000000001\"}"); volume.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(volume);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(markers.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, true, 100L, 80L, null, null, 1L));
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);
        StorageVolumeLifecycleService service = service(mapper, markers, health, mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), configs);

        service.setActive(1L, 100L);

        assertThat(volume.getWriteStatus()).isEqualTo("ACTIVE");
        verify(mapper).updateById(volume);
    }

    @Test
    void rejectsDisablingVolumeReferencedByActiveArtifact() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(2L); volume.setTier("HOT"); volume.setWriteStatus("READ_ONLY"); volume.setIsDeleted(0);
        when(mapper.selectById(2L)).thenReturn(volume);
        when(copies.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        StorageVolumeLifecycleService service = service(mapper, mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(JobMapper.class), mock(JobOrderMapper.class), copies,
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.changeTier(2L, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.DISABLED, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("活跃文件副本");
    }

    @Test
    void rejectsChangingVolumeReferencedByRunningRestore() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        JobOrderMapper jobOrders = mock(JobOrderMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(3L); volume.setTier("COLD"); volume.setWriteStatus("READ_ONLY"); volume.setIsDeleted(0);
        when(mapper.selectById(3L)).thenReturn(volume);
        when(jobOrders.countRunningSourceReferences(3L)).thenReturn(1L);
        StorageVolumeLifecycleService service = service(mapper, mock(StorageVolumeMarkerService.class),
                mock(StorageVolumeHealthService.class), mock(JobMapper.class), jobOrders, mock(ArtifactCopyMapper.class),
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.changeTier(3L, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.READ_ONLY, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("运行中恢复任务");
    }

    @Test
    void rejectsActivatingUnwritableVolume() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(4L); volume.setTier("HOT"); volume.setMountStatus("ONLINE"); volume.setRootPath("D:/readonly");
        volume.setMarkerJson("{\"volumeId\":\"00000000-0000-0000-0000-000000000004\"}"); volume.setIsDeleted(0);
        when(mapper.selectById(4L)).thenReturn(volume);
        when(markers.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(true, false, 100L, 80L, null, null, 1L));
        StorageVolumeLifecycleService service = service(mapper, markers, health, mock(JobMapper.class), mock(JobOrderMapper.class),
                mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.setActive(4L, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("不可写");
    }

    @Test
    void rejectsPromotingVolumeWhenMarkerIsInvalid() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(5L); volume.setTier("COLD"); volume.setMountStatus("ONLINE");
        volume.setWriteStatus("READ_ONLY"); volume.setRootPath("D:/wrong-disk"); volume.setIsDeleted(0);
        when(mapper.selectById(5L)).thenReturn(volume);
        when(markers.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, false, "卷标记不匹配"));
        StorageVolumeLifecycleService service = service(mapper, markers, mock(StorageVolumeHealthService.class),
                mock(JobMapper.class), mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class),
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.changeTier(5L, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.STANDBY, 100L))
                .isInstanceOf(StorageVolumeOperationException.class)
                .hasMessageContaining("卷标记");
    }

    private static StorageVolumeLifecycleService service(StorageVolumeMapper mapper, StorageVolumeMarkerService markers,
            StorageVolumeHealthService health, JobMapper jobs, JobOrderMapper jobOrders, ArtifactCopyMapper copies,
            SpaceReservationMapper reservations, RuntimeConfigService configs) {
        return new StorageVolumeLifecycleService(mapper, mock(StorageVolumeDiscoveryService.class), markers, health,
                mock(AuditLogService.class), new ObjectMapper(), jobs, jobOrders, copies, reservations, configs);
    }
}
