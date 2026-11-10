package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.backup.BackupStageMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentJobServiceTest {
    @Test
    void degradedBackupEligibilityRequiresStrictRealRelationFailureWithoutAvailableSnapshot() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        BackupStageMapper stages = mock(BackupStageMapper.class);
        JobEntity source = new JobEntity();
        source.setId(20L); source.setJobType("ORDER_BACKUP"); source.setStatus("PARTIAL_FAILED");
        source.setFencingToken(0L); source.setConfigSnapshotJson("{\"executionMode\":\"REAL\"}");
        JobOrderEntity failed = new JobOrderEntity();
        failed.setJobId(20L); failed.setSourceOrderId(7L); failed.setStatus("FAILED");
        failed.setFailureCode("RELATION_BROKEN");
        when(jobs.selectById(20L)).thenReturn(source);
        when(orders.selectOne(any())).thenReturn(failed);
        when(snapshots.selectCount(any())).thenReturn(0L);
        PersistentJobService service = new PersistentJobService(
                jobs, orders, files, null, null, snapshots, stages);

        assertThat(service.requireDegradedBackupEligible(20L, 7L)).isSameAs(failed);
        assertThat(failed.getDegradedBackupEligible()).isTrue();

        source.setConfigSnapshotJson("{\"executionMode\":\"METADATA_SIMULATION\"}");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.requireDegradedBackupEligible(20L, 7L)).isInstanceOf(IllegalStateException.class);
        source.setConfigSnapshotJson("{\"executionMode\":\"REAL\"}");
        failed.setFailureCode("DEPENDENCY_UNAVAILABLE");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.requireDegradedBackupEligible(20L, 7L)).isInstanceOf(IllegalStateException.class);
        failed.setFailureCode("RELATION_BROKEN");
        when(snapshots.selectCount(any())).thenReturn(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.requireDegradedBackupEligible(20L, 7L)).isInstanceOf(IllegalStateException.class);
        when(snapshots.selectCount(any())).thenReturn(0L);
        when(stages.selectCount(any())).thenReturn(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.requireDegradedBackupEligible(20L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有可用备份");
    }

    @Test
    void degradedTaskCannotUseNormalFailedOrderRetry() {
        JobMapper jobs = mock(JobMapper.class);
        JobEntity source = new JobEntity();
        source.setId(21L); source.setJobType("DEGRADED_BACKUP"); source.setStatus("FAILED");
        source.setFencingToken(0L); source.setConfigSnapshotJson("{\"executionMode\":\"REAL\"}");
        when(jobs.selectById(21L)).thenReturn(source);
        PersistentJobService service = new PersistentJobService(jobs);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireRetryableStrictBackupJob(21L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("降级");
    }

    @Test
    void createsAndClaimsDatabaseTaskWithLease() {
        JobMapper mapper = mock(JobMapper.class);
        doAnswer(invocation -> { JobEntity entity = invocation.getArgument(0); entity.setId(7L); entity.setVersion(0); return 1; })
                .when(mapper).insert(any(JobEntity.class));
        int[] reads = {0};
        when(mapper.selectById(7L)).thenAnswer(invocation -> {
            JobEntity entity = new JobEntity(); entity.setId(7L); entity.setVersion(0);
            entity.setStatus(reads[0]++ == 0 ? "CREATED" : "RUNNING"); entity.setFencingToken(reads[0] <= 1 ? 0L : 1L);
            entity.setConfigSnapshotJson("{\"orderTotal\":2,\"fileTotal\":3}"); return entity;
        });
        when(mapper.update(any(), any())).thenReturn(1);
        PersistentJobService service = new PersistentJobService(mapper);

        assertThat(service.create(2, 3, 9L).getId()).isEqualTo(7L);
        assertThat(service.claim(7L, "worker-1", Duration.ofMinutes(1)).getState()).isEqualTo(JobState.RUNNING);
        verify(mapper).update(any(), any());
    }

    @Test
    void createsOrderAndFileEvidenceInSameService() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        doAnswer(invocation -> { JobEntity entity = invocation.getArgument(0); entity.setId(8L); entity.setVersion(0); return 1; }).when(jobs).insert(any(JobEntity.class));
        PersistentJobService service = new PersistentJobService(jobs, orders, files);
        service.create(0, 0, 9L,
                List.of(new PersistentJobService.OrderInput(100L, 200L)),
                List.of(new PersistentJobService.FileInput(100L, "asset-1", "DOWNLOAD", 12L, "COS", "bucket", "a/1", "v1", null)));
        verify(orders).insert(any(JobOrderEntity.class));
        verify(files).insert(any(JobFileEntity.class));
    }

    @Test
    void derivesLegacyBackupOrderTotalFromSourceOrderIds() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        JobEntity entity = new JobEntity(); entity.setId(2117177485547773987L); entity.setJobType("ORDER_BACKUP");
        entity.setStatus("FAILED"); entity.setFencingToken(0L);
        entity.setConfigSnapshotJson("{\"sourceOrderIds\":[101,102,103]}");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of());
        when(files.selectList(any())).thenReturn(List.of());

        Job result = new PersistentJobService(jobs, orders, files).listBackupJobs().getFirst();

        assertThat(result.getOrderTotal()).isEqualTo(3);
    }

    @Test
    void distinguishesManifestSuccessFromSimulationArtifactFailure() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        JobEntity entity = new JobEntity(); entity.setId(10L); entity.setJobType("ORDER_BACKUP");
        entity.setStatus("PARTIAL_FAILED"); entity.setFencingToken(0L);
        entity.setConfigSnapshotJson("{\"executionMode\":\"METADATA_SIMULATION\",\"sourceOrderIds\":[1,2]}");
        JobOrderEntity artifactFailed = new JobOrderEntity(); artifactFailed.setStatus("SIMULATION_FAILED");
        artifactFailed.setExpectedFileCount(3); artifactFailed.setExpectedBytes(30L);
        JobOrderEntity manifestFailed = new JobOrderEntity(); manifestFailed.setStatus("SIMULATION_FAILED");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of(artifactFailed, manifestFailed));
        when(files.selectList(any())).thenReturn(List.of());

        Job result = new PersistentJobService(jobs, orders, files).listBackupJobs().getFirst();

        assertThat(result.getManifestSucceeded()).isEqualTo(1);
        assertThat(result.getManifestFailed()).isEqualTo(1);
        assertThat(result.getExpectedFileTotal()).isEqualTo(3);
        assertThat(result.getExpectedBytes()).isEqualTo(30L);
    }

    @Test
    void listsRestoreTasksAndDerivesTheirTargetOrderTotal() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        JobEntity entity = new JobEntity(); entity.setId(11L); entity.setJobType("RESTORE");
        entity.setStatus("CREATED"); entity.setFencingToken(0L);
        entity.setConfigSnapshotJson("{\"orderSnapshotIds\":[301,302]}");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of());
        when(files.selectList(any())).thenReturn(List.of());

        Job result = new PersistentJobService(jobs, orders, files).listRestoreJobs().getFirst();

        assertThat(result.getJobType()).isEqualTo("RESTORE");
        assertThat(result.getOrderTotal()).isEqualTo(2);
    }

    @Test
    void countsSkippedRestoreOrdersAsProcessedEvidence() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        JobEntity entity = new JobEntity(); entity.setId(12L); entity.setJobType("RESTORE");
        entity.setStatus("SUCCEEDED"); entity.setFencingToken(0L);
        entity.setConfigSnapshotJson("{\"orderSnapshotIds\":[401]}");
        JobOrderEntity skipped = new JobOrderEntity(); skipped.setStatus("SKIPPED");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of(skipped));
        when(files.selectList(any())).thenReturn(List.of());

        Job result = new PersistentJobService(jobs, orders, files).listRestoreJobs().getFirst();

        assertThat(result.getOrderSkipped()).isEqualTo(1);
    }

    @Test
    void restoreTaskCapacityUsesZipAggregateInsteadOfDoubleCountingAssetRows() {
        JobMapper jobs = mock(JobMapper.class); JobOrderMapper orders = mock(JobOrderMapper.class); JobFileMapper files = mock(JobFileMapper.class);
        JobEntity entity = new JobEntity(); entity.setId(13L); entity.setJobType("RESTORE"); entity.setStatus("SUCCEEDED"); entity.setFencingToken(0L);
        entity.setConfigSnapshotJson("{\"orderSnapshotIds\":[501]}");
        JobFileEntity zip = new JobFileEntity(); zip.setStep("RESTORE_UPLOAD"); zip.setStatus("SUCCEEDED"); zip.setBytesDone(100L); zip.setBytesTotal(100L);
        JobFileEntity asset = new JobFileEntity(); asset.setStep("RESTORE_OBJECT_UPLOAD"); asset.setStatus("SUCCEEDED"); asset.setBytesDone(240L); asset.setBytesTotal(240L);
        when(jobs.selectList(any())).thenReturn(List.of(entity)); when(orders.selectList(any())).thenReturn(List.of()); when(files.selectList(any())).thenReturn(List.of(zip, asset));

        Job result = new PersistentJobService(jobs, orders, files).listRestoreJobs().getFirst();

        assertThat(result.getBytesCompleted()).isEqualTo(100L);
        assertThat(result.getFileCompleted()).isEqualTo(1);
    }
}
