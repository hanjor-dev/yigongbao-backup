package com.yigongbao.backup.backup;

import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BackupJobCreationServiceTest {
    @Test
    void batchCreationUsesConservativeEstimatedBytesInsteadOfOneByte() {
        ActiveVolumeSelectionService volumes = mock(ActiveVolumeSelectionService.class);
        PersistentJobCoordinator jobs = mock(PersistentJobCoordinator.class);
        var packages = mock(com.yigongbao.backup.order.OrderPackageMapper.class);
        var configs = mock(com.yigongbao.backup.config.RuntimeConfigService.class);
        when(packages.selectList(any())).thenReturn(java.util.List.of());
        when(configs.getInt("storage.backup-reservation-percent", 200, 100)).thenReturn(200);
        StorageVolumeEntity volume = new StorageVolumeEntity(); volume.setId(9L);
        JobEntity reserved = new JobEntity(); reserved.setId(1L);
        when(jobs.reserve("ORDER_BACKUP", "k", 0L, "{}"))
                .thenReturn(new PersistentJobCoordinator.CreationResult(reserved, true));
        when(volumes.selectActiveForNewBackupResult(2_097_152_000L))
                .thenReturn(ActiveVolumeSelectionService.SelectionResult.available(volume));
        when(jobs.completeCreation(reserved, 9L, null, null)).thenReturn(reserved);
        new BackupJobCreationService(volumes, jobs, packages, configs)
                .createForOrders("ORDER_BACKUP", "k", 0L, "{}", 2);
        verify(volumes).selectActiveForNewBackupResult(2_097_152_000L);
    }

    @Test
    void selectionAndCreationShareOneTransactionAndFrozenVolume() throws Exception {
        ActiveVolumeSelectionService volumes = mock(ActiveVolumeSelectionService.class);
        PersistentJobCoordinator jobs = mock(PersistentJobCoordinator.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(17L);
        JobEntity expected = new JobEntity();
        expected.setId(2L);
        when(jobs.reserve("ORDER_BACKUP", "key", 3L, "{}"))
                .thenReturn(new PersistentJobCoordinator.CreationResult(expected, true));
        when(volumes.selectActiveForNewBackupResult(99L))
                .thenReturn(ActiveVolumeSelectionService.SelectionResult.available(volume));
        when(jobs.completeCreation(expected, 17L, null, null)).thenReturn(expected);

        BackupJobCreationService service = new BackupJobCreationService(volumes, jobs,
                mock(com.yigongbao.backup.order.OrderPackageMapper.class), mock(com.yigongbao.backup.config.RuntimeConfigService.class));
        assertSame(expected, service.create("ORDER_BACKUP", "key", 3L, "{}", 99L));
        var order = inOrder(jobs, volumes);
        order.verify(jobs).reserve("ORDER_BACKUP", "key", 3L, "{}");
        order.verify(volumes).selectActiveForNewBackupResult(99L);
        order.verify(jobs).completeCreation(expected, 17L, null, null);
        assertNotNull(BackupJobCreationService.class.getMethod("create", String.class, String.class,
                long.class, String.class, long.class).getAnnotation(Transactional.class));
    }

    @Test
    void unavailableHotVolumeStillCreatesAuditableTaskWithoutTargetVolume() {
        ActiveVolumeSelectionService volumes = mock(ActiveVolumeSelectionService.class);
        PersistentJobCoordinator jobs = mock(PersistentJobCoordinator.class);
        when(volumes.selectActiveForNewBackupResult(99L)).thenReturn(
                ActiveVolumeSelectionService.SelectionResult.unavailable(
                        "STORAGE_VOLUME_UNAVAILABLE", "活动热备存储卷不可用或剩余空间不足"));
        JobEntity expected = new JobEntity();
        expected.setId(3L);
        expected.setStatus("CREATED");
        when(jobs.reserve("ORDER_BACKUP", "key", 3L, "{}"))
                .thenReturn(new PersistentJobCoordinator.CreationResult(expected, true));
        when(jobs.completeCreation(expected, null,
                "STORAGE_VOLUME_UNAVAILABLE", "活动热备存储卷不可用或剩余空间不足"))
                .thenAnswer(invocation -> {
                    expected.setFailureCode(invocation.getArgument(2));
                    expected.setFailureMessage(invocation.getArgument(3));
                    return expected;
                });

        JobEntity created = new BackupJobCreationService(volumes, jobs,
                mock(com.yigongbao.backup.order.OrderPackageMapper.class),
                mock(com.yigongbao.backup.config.RuntimeConfigService.class))
                .create("ORDER_BACKUP", "key", 3L, "{}", 99L);

        assertSame(expected, created);
        assertThat(created.getTargetStorageVolumeId()).isNull();
        assertThat(created.getFailureCode()).isEqualTo("STORAGE_VOLUME_UNAVAILABLE");
        verify(jobs).completeCreation(expected, null,
                "STORAGE_VOLUME_UNAVAILABLE", "活动热备存储卷不可用或剩余空间不足");
    }

    @Test
    void idempotentReplayReturnsExistingTaskBeforeAnyVolumeSelectionSideEffect() {
        ActiveVolumeSelectionService volumes = mock(ActiveVolumeSelectionService.class);
        PersistentJobCoordinator jobs = mock(PersistentJobCoordinator.class);
        JobEntity existing = new JobEntity(); existing.setId(88L); existing.setTargetStorageVolumeId(7L);
        when(jobs.reserve("ORDER_BACKUP", "same-key", 3L, "{}"))
                .thenReturn(new PersistentJobCoordinator.CreationResult(existing, false));

        JobEntity result = new BackupJobCreationService(volumes, jobs,
                mock(com.yigongbao.backup.order.OrderPackageMapper.class),
                mock(com.yigongbao.backup.config.RuntimeConfigService.class))
                .create("ORDER_BACKUP", "same-key", 3L, "{}", 99L);

        assertSame(existing, result);
        verifyNoInteractions(volumes);
        verify(jobs, never()).completeCreation(any(), any(), any(), any());
    }
}
