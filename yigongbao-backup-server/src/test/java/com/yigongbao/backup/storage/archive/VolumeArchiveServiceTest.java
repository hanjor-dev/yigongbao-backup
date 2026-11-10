package com.yigongbao.backup.storage.archive;

import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VolumeArchiveServiceTest {

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
