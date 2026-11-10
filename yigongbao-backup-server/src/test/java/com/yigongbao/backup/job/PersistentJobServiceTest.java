package com.yigongbao.backup.job;

import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentJobServiceTest {
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

    @Test
    void fileEvidenceKeepsTaskTimeTierAndProjectsCurrentVolumeInOneBatch() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobFileEntity file = new JobFileEntity();
        file.setStorageVolumeId(7L); file.setStorageVolumeName("D盘"); file.setStorageTier("HOT");
        file.setStorageKey("orders/O-1.zip!/a.stl");
        StorageVolumeEntity current = new StorageVolumeEntity();
        current.setId(7L); current.setDisplayName("归档盘-A"); current.setTier("COLD");
        current.setMountStatus("OFFLINE"); current.setOperationStatus("IDLE");
        when(files.selectList(any())).thenReturn(List.of(file));
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(current));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, packages, volumes);

        JobFileEntity result = service.listFiles(99L).getFirst();

        assertThat(result.getStorageTier()).isEqualTo("HOT");
        assertThat(result.getCurrentStorageVolumeName()).isEqualTo("归档盘-A");
        assertThat(result.getCurrentStorageTier()).isEqualTo("COLD");
        assertThat(result.getCurrentMountStatus()).isEqualTo("OFFLINE");
        verify(volumes).selectBatchIds(anyCollection());
        verify(volumes, never()).selectById(any());
    }

    @Test
    void restoreOrderFilePageProjectsCurrentVolumeWithoutOverwritingTaskTimeSnapshot() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobEntity restore = new JobEntity();
        restore.setId(99L); restore.setJobType("RESTORE");
        when(jobs.selectById(99L)).thenReturn(restore);
        JobFileEntity file = new JobFileEntity();
        file.setStorageVolumeId(7L); file.setStorageVolumeName("D盘"); file.setStorageTier("HOT");
        when(files.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<JobFileEntity> page = invocation.getArgument(0);
            page.setRecords(List.of(file)); page.setTotal(1L);
            return page;
        });
        StorageVolumeEntity current = new StorageVolumeEntity();
        current.setId(7L); current.setDisplayName("归档盘-A"); current.setTier("COLD");
        current.setMountStatus("OFFLINE"); current.setOperationStatus("IDLE");
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(current));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, packages, volumes);

        JobFileEntity result = service.listRestoreOrderFiles(99L, 75L, 1L, 20L).records().getFirst();

        assertThat(result.getStorageTier()).isEqualTo("HOT");
        assertThat(result.getCurrentStorageVolumeName()).isEqualTo("归档盘-A");
        assertThat(result.getCurrentStorageTier()).isEqualTo("COLD");
        assertThat(result.getCurrentMountStatus()).isEqualTo("OFFLINE");
        verify(volumes).selectBatchIds(anyCollection());
        verify(volumes, never()).selectById(any());
    }

    @Test
    void backupTaskProjectsCurrentStateFromPersistedTargetBeforeAnyOrderEvidenceExists() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobEntity entity = new JobEntity();
        entity.setId(101L); entity.setJobType("ORDER_BACKUP"); entity.setStatus("CREATED");
        entity.setFencingToken(0L); entity.setTargetStorageVolumeId(7L); entity.setConfigSnapshotJson("{}");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of());
        when(files.selectList(any())).thenReturn(List.of());
        StorageVolumeEntity current = new StorageVolumeEntity();
        current.setId(7L); current.setDisplayName("归档盘-A"); current.setTier("COLD");
        current.setMountStatus("OFFLINE"); current.setOperationStatus("IDLE");
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(current));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, volumes);

        Job result = service.listBackupJobs().getFirst();

        assertThat(result.getTargetStorageVolumeId()).isEqualTo(7L);
        assertThat(result.getCurrentStorageVolumeName()).isEqualTo("归档盘-A");
        assertThat(result.getCurrentStorageTier()).isEqualTo("COLD");
        assertThat(result.getCurrentMountStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void taskSummaryDoesNotProjectFileRowsThatAreUsedOnlyForProgress() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobEntity entity = new JobEntity();
        entity.setId(102L); entity.setJobType("ORDER_BACKUP"); entity.setStatus("SUCCEEDED");
        entity.setFencingToken(0L); entity.setConfigSnapshotJson("{}");
        JobFileEntity file = new JobFileEntity();
        file.setStorageVolumeId(8L); file.setStep("DOWNLOAD"); file.setStatus("SUCCEEDED");
        when(jobs.selectList(any())).thenReturn(List.of(entity));
        when(orders.selectList(any())).thenReturn(List.of());
        when(files.selectList(any())).thenReturn(List.of(file));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, volumes);

        service.listBackupJobs();

        verifyNoInteractions(volumes);
    }

    @Test
    void fileEvidenceMarksMissingCurrentVolumeInsteadOfReusingHistoricalSnapshot() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobFileEntity file = new JobFileEntity();
        file.setStorageVolumeId(7L); file.setStorageVolumeName("旧归档盘"); file.setStorageTier("HOT");
        when(files.selectList(any())).thenReturn(List.of(file));
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of());
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, volumes);

        JobFileEntity result = service.listFiles(99L).getFirst();

        assertThat(result.getCurrentStorageResolved()).isFalse();
        assertThat(result.getCurrentStorageUnavailableReason()).contains("STORAGE_VOLUME_MISSING");
        assertThat(result.getStorageVolumeName()).isEqualTo("旧归档盘");
        assertThat(result.getStorageTier()).isEqualTo("HOT");
    }

    @Test
    void legacyFileSnapshotWithoutVolumeIdIsExplicitlyUnresolved() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobFileEntity file = new JobFileEntity();
        file.setStorageVolumeName("旧盘"); file.setStorageTier("HOT");
        when(files.selectList(any())).thenReturn(List.of(file));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, volumes);

        JobFileEntity result = service.listFiles(99L).getFirst();

        assertThat(result.getCurrentStorageResolved()).isFalse();
        assertThat(result.getCurrentStorageUnavailableReason()).contains("未保存存储卷ID");
        verifyNoInteractions(volumes);
    }

    @Test
    void legacyTaskSnapshotWithoutVolumeIdKeepsUnresolvedReasonAtTaskLevel() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        JobEntity restore = new JobEntity();
        restore.setId(150L); restore.setJobType("RESTORE"); restore.setStatus("SUCCEEDED");
        restore.setFencingToken(0L); restore.setConfigSnapshotJson("{}");
        JobOrderEntity order = new JobOrderEntity();
        order.setJobId(150L); order.setStorageVolumeName("旧盘"); order.setStorageTier("HOT");
        when(jobs.selectList(any())).thenReturn(List.of(restore));
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(files.selectList(any())).thenReturn(List.of());
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, mock(StorageVolumeMapper.class));

        Job result = service.listRestoreJobs().getFirst();

        assertThat(result.getCurrentStorageResolved()).isFalse();
        assertThat(result.getCurrentStorageUnavailableReason()).contains("STORAGE_VOLUME_UNRESOLVED");
    }

    @Test
    void multiTaskListLoadsEvidenceAndAllCurrentVolumesInBatches() {
        JobMapper jobs = mock(JobMapper.class);
        JobOrderMapper orders = mock(JobOrderMapper.class);
        JobFileMapper files = mock(JobFileMapper.class);
        StorageVolumeMapper volumes = mock(StorageVolumeMapper.class);
        JobEntity first = new JobEntity(); first.setId(201L); first.setJobType("ORDER_BACKUP");
        first.setStatus("SUCCEEDED"); first.setFencingToken(0L); first.setConfigSnapshotJson("{}");
        first.setTargetStorageVolumeId(7L);
        JobEntity second = new JobEntity(); second.setId(202L); second.setJobType("ORDER_BACKUP");
        second.setStatus("SUCCEEDED"); second.setFencingToken(0L); second.setConfigSnapshotJson("{}");
        second.setTargetStorageVolumeId(8L);
        JobOrderEntity firstOrder = new JobOrderEntity(); firstOrder.setJobId(201L); firstOrder.setStorageVolumeId(7L);
        JobOrderEntity secondOrder = new JobOrderEntity(); secondOrder.setJobId(202L); secondOrder.setStorageVolumeId(8L);
        when(jobs.selectList(any())).thenReturn(List.of(first, second));
        when(orders.selectList(any())).thenReturn(List.of(firstOrder, secondOrder));
        when(files.selectList(any())).thenReturn(List.of());
        StorageVolumeEntity a = new StorageVolumeEntity(); a.setId(7L); a.setTier("COLD");
        StorageVolumeEntity b = new StorageVolumeEntity(); b.setId(8L); b.setTier("HOT");
        when(volumes.selectBatchIds(anyCollection())).thenReturn(List.of(a, b));
        PersistentJobService service = new PersistentJobService(jobs, orders, files, null, volumes);

        List<Job> result = service.listBackupJobs();

        assertThat(result).hasSize(2);
        verify(orders, times(1)).selectList(any());
        verify(files, times(1)).selectList(any());
        verify(volumes, times(1)).selectBatchIds(anyCollection());
    }
}
