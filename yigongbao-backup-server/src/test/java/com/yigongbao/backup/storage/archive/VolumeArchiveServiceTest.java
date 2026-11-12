package com.yigongbao.backup.storage.archive;

import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VolumeArchiveServiceTest {

    @Test
    void standbyHotVolumeIsBlockedBeforeArchiveVerification() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        com.yigongbao.backup.job.JobOrderMapper jobOrders = mock(com.yigongbao.backup.job.JobOrderMapper.class);
        com.yigongbao.backup.storage.reservation.SpaceReservationMapper reservations =
                mock(com.yigongbao.backup.storage.reservation.SpaceReservationMapper.class);
        com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper leases =
                mock(com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper.class);
        StorageVolumeEntity source = new StorageVolumeEntity();
        source.setId(7L); source.setTier("HOT"); source.setMountStatus("ONLINE");
        source.setWriteStatus("STANDBY"); source.setOperationStatus("IDLE");
        StorageVolumeEntity currentActive = new StorageVolumeEntity();
        currentActive.setId(8L); currentActive.setTier("HOT"); currentActive.setMountStatus("ONLINE");
        currentActive.setWriteStatus("ACTIVE"); currentActive.setOperationStatus("IDLE");
        when(volumes.selectById(7L)).thenReturn(source);
        when(volumes.selectById(8L)).thenReturn(currentActive);

        var found = new VolumeArchiveBlockerService(volumes, jobs, jobOrders, reservations, leases)
                .inspect(7L, 8L);

        assertThat(found).anySatisfy(blocker -> {
            assertThat(blocker.code()).isEqualTo("SOURCE_WRITE_STATE_NOT_READY");
            assertThat(blocker.message()).contains("取消备用");
        });
    }

    @Test
    void commitStateConflictReportsBothVolumeStates() {
        VolumeArchiveMapper archives = mock(VolumeArchiveMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        VolumeArchiveEntity archive = new VolumeArchiveEntity();
        archive.setId(91L); archive.setJobId(101L); archive.setSourceVolumeId(7L); archive.setSuccessorVolumeId(8L);
        when(archives.selectById(91L)).thenReturn(archive);
        StorageVolumeEntity source = new StorageVolumeEntity();
        source.setId(7L); source.setTier("HOT"); source.setMountStatus("ONLINE");
        source.setWriteStatus("STANDBY"); source.setOperationStatus("SEALING"); source.setOperationRefId(91L);
        StorageVolumeEntity successor = new StorageVolumeEntity();
        successor.setId(8L); successor.setTier("HOT"); successor.setMountStatus("ONLINE");
        successor.setWriteStatus("ACTIVE"); successor.setOperationStatus("ARCHIVE_GUARD"); successor.setOperationRefId(91L);
        when(volumes.findByIdForUpdate(7L)).thenReturn(source);
        when(volumes.findByIdForUpdate(8L)).thenReturn(successor);
        JobEntity job = new JobEntity();
        job.setId(101L); job.setStatus("RUNNING"); job.setLeaseOwner("runner-1");
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(1)); job.setFencingToken(3L);
        when(jobs.findByIdForUpdate(101L)).thenReturn(job);
        VolumeArchiveService service = new VolumeArchiveService(
                archives, mock(VolumeArchiveItemMapper.class), volumes,
                mock(StorageVolumeLifecycleService.class), mock(VolumeArchiveBlockerService.class),
                mock(ManagedVolumeArtifactCatalog.class), mock(PersistentJobCoordinator.class), jobs,
                mock(AuditLogService.class), mock(TransactionTemplate.class));

        assertThatThrownBy(() -> service.complete(91L, "runner-1", 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("源卷ID=7", "writeStatus=STANDBY", "保障卷ID=8", "operationStatus=ARCHIVE_GUARD");
    }

    @Test
    void readonlyHotVolumeArchiveUsesCurrentActiveHotVolumeAsGuarantee() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        com.yigongbao.backup.job.JobOrderMapper jobOrders = mock(com.yigongbao.backup.job.JobOrderMapper.class);
        com.yigongbao.backup.storage.reservation.SpaceReservationMapper reservations =
                mock(com.yigongbao.backup.storage.reservation.SpaceReservationMapper.class);
        com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper leases =
                mock(com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper.class);
        StorageVolumeEntity previousActive = new StorageVolumeEntity();
        previousActive.setId(7L); previousActive.setTier("HOT"); previousActive.setMountStatus("ONLINE");
        previousActive.setWriteStatus("READ_ONLY"); previousActive.setOperationStatus("IDLE");
        StorageVolumeEntity currentActive = new StorageVolumeEntity();
        currentActive.setId(8L); currentActive.setTier("HOT"); currentActive.setMountStatus("ONLINE");
        currentActive.setWriteStatus("ACTIVE"); currentActive.setOperationStatus("IDLE");
        when(volumes.selectById(7L)).thenReturn(previousActive);
        when(volumes.selectById(8L)).thenReturn(currentActive);
        VolumeArchiveBlockerService service = new VolumeArchiveBlockerService(
                volumes, jobs, jobOrders, reservations, leases);

        assertThat(service.inspect(7L, 8L))
                .extracting(VolumeArchiveBlockerService.Blocker::code)
                .doesNotContain("INVALID_SUCCESSOR");
        assertThat(service.inspect(7L, null))
                .anySatisfy(blocker -> {
                    assertThat(blocker.code()).isEqualTo("INVALID_SUCCESSOR");
                    assertThat(blocker.message()).isEqualTo("整盘封存必须选择当前 HOT/ONLINE/ACTIVE 保障卷");
                });
    }

    @Test
    void blockerRejectsSuccessorAlreadyGuardingAnotherArchive() {
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        com.yigongbao.backup.job.JobOrderMapper jobOrders = mock(com.yigongbao.backup.job.JobOrderMapper.class);
        com.yigongbao.backup.storage.reservation.SpaceReservationMapper reservations =
                mock(com.yigongbao.backup.storage.reservation.SpaceReservationMapper.class);
        com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper leases =
                mock(com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper.class);
        StorageVolumeEntity source = new StorageVolumeEntity();
        source.setId(7L); source.setTier("HOT"); source.setMountStatus("ONLINE");
        source.setWriteStatus("READ_ONLY"); source.setOperationStatus("IDLE");
        StorageVolumeEntity successor = new StorageVolumeEntity();
        successor.setId(8L); successor.setTier("HOT"); successor.setMountStatus("ONLINE");
        successor.setWriteStatus("ACTIVE"); successor.setOperationStatus("ARCHIVE_GUARD");
        successor.setOperationRefId(77L);
        when(volumes.selectById(7L)).thenReturn(source);
        when(volumes.selectById(8L)).thenReturn(successor);

        var found = new VolumeArchiveBlockerService(volumes, jobs, jobOrders, reservations, leases)
                .inspect(7L, 8L);

        assertThat(found).extracting(VolumeArchiveBlockerService.Blocker::code)
                .contains("SUCCESSOR_BUSY");
    }

    @Test
    void archiveNeverReleasesGuardOwnedByAnotherAttempt() {
        VolumeArchiveMapper archives = mock(VolumeArchiveMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        VolumeArchiveEntity archive = new VolumeArchiveEntity();
        archive.setId(91L); archive.setSourceVolumeId(7L); archive.setSuccessorVolumeId(8L);
        archive.setPhase("CANCELLED");
        StorageVolumeEntity successor = new StorageVolumeEntity();
        successor.setId(8L); successor.setOperationStatus("ARCHIVE_GUARD"); successor.setOperationRefId(92L);
        when(archives.selectById(91L)).thenReturn(archive);
        when(volumes.findByIdForUpdate(7L)).thenReturn(null);
        when(volumes.findByIdForUpdate(8L)).thenReturn(successor);
        VolumeArchiveService service = new VolumeArchiveService(
                archives, mock(VolumeArchiveItemMapper.class), volumes,
                mock(StorageVolumeLifecycleService.class), mock(VolumeArchiveBlockerService.class),
                mock(ManagedVolumeArtifactCatalog.class), mock(PersistentJobCoordinator.class),
                mock(JobMapper.class), mock(AuditLogService.class), mock(TransactionTemplate.class));

        service.abandon(91L, 1L, "abandon");

        verify(volumes, never()).updateById(same(successor));
        assertThat(successor.getOperationStatus()).isEqualTo("ARCHIVE_GUARD");
        assertThat(successor.getOperationRefId()).isEqualTo(92L);
    }

    @Test
    void resumesPreparationLeftWithoutJobBySameIdempotencyKey() {
        VolumeArchiveMapper archives = mock(VolumeArchiveMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        ManagedVolumeArtifactCatalog catalog = mock(ManagedVolumeArtifactCatalog.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactions).execute(any());

        VolumeArchiveEntity unfinished = new VolumeArchiveEntity();
        unfinished.setId(91L);
        unfinished.setSourceVolumeId(7L);
        unfinished.setSuccessorVolumeId(8L);
        unfinished.setIdempotencyKey("archive-request-1");
        unfinished.setPhase("SWITCHED");
        when(archives.selectOne(any())).thenReturn(unfinished);
        StorageVolumeEntity source = new StorageVolumeEntity();
        source.setId(7L);
        source.setWriteStatus("READ_ONLY");
        source.setOperationStatus("SEALING");
        source.setOperationRefId(91L);
        StorageVolumeEntity successor = new StorageVolumeEntity();
        successor.setId(8L);
        successor.setWriteStatus("ACTIVE");
        successor.setOperationStatus("ARCHIVE_GUARD");
        successor.setOperationRefId(91L);
        when(volumes.selectById(7L)).thenReturn(source);
        when(volumes.findByIdForUpdate(7L)).thenReturn(source);
        when(volumes.findByIdForUpdate(8L)).thenReturn(successor);

        RuntimeException interruptedAfterPreparation = new RuntimeException("stop-after-resume");
        when(catalog.inspect(91L, 7L)).thenThrow(interruptedAfterPreparation);
        VolumeArchiveService service = new VolumeArchiveService(
                archives, mock(VolumeArchiveItemMapper.class), volumes,
                mock(StorageVolumeLifecycleService.class), mock(VolumeArchiveBlockerService.class), catalog,
                mock(PersistentJobCoordinator.class), mock(JobMapper.class), mock(AuditLogService.class), transactions);

        assertThatThrownBy(() -> service.start(new VolumeArchiveService.StartArchiveCommand(
                7L, 8L, "archive", null, null, "archive-request-1"), 1L))
                .isSameAs(interruptedAfterPreparation);
        verify(catalog).inspect(91L, 7L);
    }
}
