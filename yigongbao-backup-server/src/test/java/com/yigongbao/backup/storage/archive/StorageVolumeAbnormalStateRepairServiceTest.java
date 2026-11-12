package com.yigongbao.backup.storage.archive;

import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeHealthService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMarkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StorageVolumeAbnormalStateRepairServiceTest {
    private final StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
    private final VolumeArchiveMapper archives = mock(VolumeArchiveMapper.class);
    private final VolumeArchiveItemMapper items = mock(VolumeArchiveItemMapper.class);
    private final JobMapper jobs = mock(JobMapper.class);
    private final JobOrderMapper jobOrders = mock(JobOrderMapper.class);
    private final SpaceReservationMapper reservations = mock(SpaceReservationMapper.class);
    private final StorageVolumeReadLeaseMapper leases = mock(StorageVolumeReadLeaseMapper.class);
    private final StorageVolumeMarkerService markers = mock(StorageVolumeMarkerService.class);
    private final StorageVolumeHealthService health = mock(StorageVolumeHealthService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private StorageVolumeAbnormalStateRepairService service;
    private StorageVolumeEntity volume;
    private VolumeArchiveEntity archive;

    @BeforeEach
    void setUp() {
        service = new StorageVolumeAbnormalStateRepairService(
                volumes, archives, items, jobs, jobOrders, reservations, leases, markers, health, audits);
        volume = new StorageVolumeEntity();
        volume.setId(7L); volume.setTier("HOT"); volume.setMountStatus("ONLINE");
        volume.setWriteStatus("READ_ONLY"); volume.setAdminStatus("ENABLED");
        volume.setOperationStatus("REPAIR_REQUIRED"); volume.setOperationRefId(91L);
        volume.setRootPath("D:/yigongbao-backup");
        archive = new VolumeArchiveEntity();
        archive.setId(91L); archive.setSourceVolumeId(7L); archive.setPhase("COMMIT_FAILED");
        archive.setJobId(101L); archive.setFailureCode("ARCHIVE_COMMIT_FAILED"); archive.setManagedFileCount(13L);
        JobEntity failedJob = new JobEntity(); failedJob.setId(101L); failedJob.setStatus("FAILED");
        when(volumes.findByIdForUpdate(7L)).thenReturn(volume);
        when(archives.selectOne(any())).thenReturn(archive);
        when(archives.selectCount(any())).thenReturn(0L);
        when(jobs.selectById(101L)).thenReturn(failedJob);
        when(items.summarizeVerification(91L))
                .thenReturn(new VolumeArchiveItemMapper.ArchiveVerificationSummary(13L, 13L, 0L, 0L));
        UUID markerId = UUID.randomUUID();
        when(markers.expectedVolumeId(volume)).thenReturn(markerId);
        when(markers.verify(any(), eq(markerId)))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                true, true, 1000L, 800L, null, null, 1L));
    }

    @Test
    void commitFailureWithFullyVerifiedFilesCanReturnToIdle() {
        StorageVolumeEntity repaired = service.repair(7L, 100L, "修复封存提交状态异常");

        assertThat(repaired.getTier()).isEqualTo("HOT");
        assertThat(repaired.getWriteStatus()).isEqualTo("READ_ONLY");
        assertThat(repaired.getOperationStatus()).isEqualTo("IDLE");
        assertThat(repaired.getOperationRefId()).isNull();
        assertThat(repaired.getUpdateBy()).isEqualTo(100L);
        verify(volumes).updateById(same(volume));
        verify(audits).append(argThat(event -> "STORAGE_VOLUME_REPAIR_ABNORMAL_STATE".equals(event.action())
                && "SUCCESS".equals(event.resultCode())
                && event.failureReason().contains("修复封存提交状态异常")));
    }

    @Test
    void fileFailureOrIncompleteVerificationCannotBeCleared() {
        when(items.summarizeVerification(91L))
                .thenReturn(new VolumeArchiveItemMapper.ArchiveVerificationSummary(13L, 12L, 1L, 0L));

        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("文件校验");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void onlyCommitStageProcessFailureCanBeCleared() {
        archive.setPhase("VERIFY_FAILED");
        archive.setFailureCode("ARCHIVE_VERIFY_FAILED");

        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("提交阶段流程异常");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void runningReferencesCannotBeCleared() {
        when(jobs.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("运行中任务");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void archiveJobMustFinishBeforeIsolationIsCleared() {
        JobEntity runningJob = new JobEntity(); runningJob.setId(101L); runningJob.setStatus("RUNNING");
        when(jobs.selectById(101L)).thenReturn(runningJob);

        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("封存任务", "失败终态");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void invalidMarkerOrUnavailableVolumeCannotBeCleared() {
        UUID markerId = markers.expectedVolumeId(volume);
        when(markers.verify(any(), eq(markerId)))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, false, "卷标记不匹配"));

        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("卷标记不匹配");

        when(markers.verify(any(), eq(markerId)))
                .thenReturn(new StorageVolumeMarkerService.VerificationResult(true, true, null));
        when(health.probe(volume)).thenReturn(new StorageVolumeHealthService.HealthSnapshot(
                false, false, 0L, 0L, "ROOT_NOT_FOUND", "目录不存在", 1L));
        assertThatThrownBy(() -> service.repair(7L, 100L, "流程修复"))
                .hasMessageContaining("目录不存在");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }

    @Test
    void repairReasonIsRequired() {
        assertThatThrownBy(() -> service.repair(7L, 100L, "  "))
                .hasMessageContaining("修复原因");
        verify(volumes, never()).updateById(any(StorageVolumeEntity.class));
    }
}
