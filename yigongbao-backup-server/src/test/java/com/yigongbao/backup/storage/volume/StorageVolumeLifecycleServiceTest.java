package com.yigongbao.backup.storage.volume;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import static org.mockito.Mockito.never;

/** 存储卷状态机的关键安全边界测试。 */
class StorageVolumeLifecycleServiceTest {

    @Test
    void logsStandbyTransitionStartAndSuccessWithOperationalContext() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity volume = transitionVolume(6L);
        when(mapper.findByIdForUpdate(6L)).thenReturn(volume);
        when(markers.verify(any(), any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        StorageVolumeLifecycleService service = service(mapper, markers, health, mock(JobMapper.class),
                mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class),
                mock(RuntimeConfigService.class));
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            service.changeTier(6L, StorageVolumeStatus.Tier.HOT, StorageVolumeStatus.Write.STANDBY, 100L);
        } finally {
            detachAppender(appender);
        }

        String logs = formattedLogs(appender);
        assertThat(logs).contains("存储卷状态变更开始", "存储卷状态变更成功", "存储卷ID=6",
                "目标分层=HOT", "目标写入状态=STANDBY", "操作人ID=100", "执行耗时=");
    }

    @Test
    void logsStandbyTransitionFailureWithReason() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeEntity volume = transitionVolume(7L);
        when(mapper.findByIdForUpdate(7L)).thenReturn(volume);
        when(markers.verify(any(), any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, false, "卷标记不匹配"));
        StorageVolumeLifecycleService service = service(mapper, markers, mock(StorageVolumeHealthService.class),
                mock(JobMapper.class), mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class),
                mock(SpaceReservationMapper.class), mock(RuntimeConfigService.class));
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            assertThatThrownBy(() -> service.changeTier(7L, StorageVolumeStatus.Tier.HOT,
                    StorageVolumeStatus.Write.STANDBY, 101L))
                    .isInstanceOf(StorageVolumeOperationException.class);
        } finally {
            detachAppender(appender);
        }

        String logs = formattedLogs(appender);
        assertThat(logs).contains("存储卷状态变更开始", "存储卷状态变更失败", "存储卷ID=7",
                "目标写入状态=STANDBY", "操作人ID=101", "异常类型=StorageVolumeOperationException",
                "异常原因=存储卷卷标记校验失败：卷标记不匹配", "执行耗时=");
    }

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
    void activationClassifiesAHealthRaceAsVolumeUnavailabilityBeforeWritingState() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        StorageVolumeEntity standby = new StorageVolumeEntity();
        standby.setId(2L); standby.setTier("HOT"); standby.setMountStatus("ONLINE");
        standby.setWriteStatus("STANDBY"); standby.setAdminStatus("ENABLED");
        standby.setOperationStatus("IDLE"); standby.setRootPath("E:/managed"); standby.setIsDeleted(0);
        when(mapper.findByIdForUpdate(2L)).thenReturn(standby);
        when(markers.verify(any(), any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(standby)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                false, false, 0L, 0L, "STORAGE_PROBE_FAILED", "介质已拔出", 1L));
        StorageVolumeLifecycleService service = service(mapper, markers, health, mock(JobMapper.class),
                mock(JobOrderMapper.class), mock(ArtifactCopyMapper.class), mock(SpaceReservationMapper.class),
                mock(RuntimeConfigService.class));

        assertThatThrownBy(() -> service.setActive(2L, 0L))
                .isInstanceOf(StorageVolumeUnavailableException.class)
                .hasMessageContaining("介质已拔出");
        verify(mapper, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void promotesReconnectedIdleColdVolumeAndDemotesCurrentHotVolume() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
        StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        JobMapper jobs = mock(JobMapper.class);
        SpaceReservationMapper reservations = mock(SpaceReservationMapper.class);
        ArtifactCopyMapper copies = mock(ArtifactCopyMapper.class);

        StorageVolumeEntity current = new StorageVolumeEntity();
        current.setId(1L); current.setTier("HOT"); current.setMountStatus("ONLINE");
        current.setWriteStatus("ACTIVE"); current.setOperationStatus("IDLE"); current.setIsDeleted(0);
        StorageVolumeEntity cold = new StorageVolumeEntity();
        cold.setId(2L); cold.setTier("COLD"); cold.setMountStatus("ONLINE");
        cold.setWriteStatus("READ_ONLY"); cold.setOperationStatus("IDLE"); cold.setAdminStatus("ENABLED");
        cold.setRootPath("E:/yigongbao-backup");
        cold.setMarkerJson("{\"volumeId\":\"00000000-0000-0000-0000-000000000002\"}"); cold.setIsDeleted(0);

        when(mapper.findActiveForUpdate()).thenReturn(current);
        when(mapper.findByIdForUpdate(2L)).thenReturn(cold);
        when(markers.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(cold)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 100L, 80L, null, null, 1L));
        when(configs.getInt("storage.volume-switch.threshold-percent", 90, 1)).thenReturn(90);
        when(jobs.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        // 历史冷备文件仍然保留在该卷；它们不能成为重新启用原盘的阻塞条件。
        when(copies.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(12L);
        StorageVolumeLifecycleService service = service(mapper, markers, health, jobs, mock(JobOrderMapper.class),
                copies, reservations, configs);

        java.util.concurrent.atomic.AtomicReference<StorageVolumeEntity> result = new java.util.concurrent.atomic.AtomicReference<>();
        org.assertj.core.api.Assertions.assertThatCode(() -> result.set(service.setActive(2L, 100L)))
                .doesNotThrowAnyException();
        StorageVolumeEntity activated = result.get();

        assertThat(current.getTier()).isEqualTo("HOT");
        assertThat(current.getWriteStatus()).isEqualTo("READ_ONLY");
        assertThat(activated.getTier()).isEqualTo("HOT");
        assertThat(activated.getWriteStatus()).isEqualTo("ACTIVE");
        assertThat(activated.getOperationStatus()).isEqualTo("IDLE");
        verify(mapper).updateById(current);
        verify(mapper).updateById(cold);
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

    private static StorageVolumeEntity transitionVolume(long id) {
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(id);
        volume.setTier("HOT");
        volume.setMountStatus("ONLINE");
        volume.setWriteStatus("READ_ONLY");
        volume.setAdminStatus("ENABLED");
        volume.setOperationStatus("IDLE");
        volume.setRootPath("D:/managed-" + id);
        volume.setMarkerJson("{\"volumeId\":\"00000000-0000-0000-0000-00000000000" + id + "\"}");
        volume.setIsDeleted(0);
        return volume;
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(StorageVolumeLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(StorageVolumeLifecycleService.class);
        logger.detachAppender(appender);
    }

    private static String formattedLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
