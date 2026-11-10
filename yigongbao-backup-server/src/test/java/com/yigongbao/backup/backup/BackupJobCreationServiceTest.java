package com.yigongbao.backup.backup;

import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        when(volumes.selectActiveForNewBackup(4_294_967_296L)).thenReturn(volume);
        when(jobs.create("ORDER_BACKUP", "k", 0L, "{}", 9L)).thenReturn(new JobEntity());
        new BackupJobCreationService(volumes, jobs, packages, configs)
                .createForOrders("ORDER_BACKUP", "k", 0L, "{}", 2);
        verify(volumes).selectActiveForNewBackup(4_294_967_296L);
    }

    @Test
    void selectionAndCreationShareOneTransactionAndFrozenVolume() throws Exception {
        ActiveVolumeSelectionService volumes = mock(ActiveVolumeSelectionService.class);
        PersistentJobCoordinator jobs = mock(PersistentJobCoordinator.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(17L);
        JobEntity expected = new JobEntity();
        when(volumes.selectActiveForNewBackup(99L)).thenReturn(volume);
        when(jobs.create("ORDER_BACKUP", "key", 3L, "{}", 17L)).thenReturn(expected);

        BackupJobCreationService service = new BackupJobCreationService(volumes, jobs,
                mock(com.yigongbao.backup.order.OrderPackageMapper.class), mock(com.yigongbao.backup.config.RuntimeConfigService.class));
        assertSame(expected, service.create("ORDER_BACKUP", "key", 3L, "{}", 99L));
        var order = inOrder(volumes, jobs);
        order.verify(volumes).selectActiveForNewBackup(99L);
        order.verify(jobs).create("ORDER_BACKUP", "key", 3L, "{}", 17L);
        assertNotNull(BackupJobCreationService.class.getMethod("create", String.class, String.class,
                long.class, String.class, long.class).getAnnotation(Transactional.class));
    }
}
