package com.yigongbao.backup.backup;

import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupersededBackupStageCleanupServiceTest {
    @Test
    void claimsAndDeletesSupersededArtifactIdempotently() throws Exception {
        BackupStageMapper mapper = mock(BackupStageMapper.class);
        VolumeArtifactStore store = mock(VolumeArtifactStore.class);
        BackupStageEntity row = stage();
        when(mapper.findCleanupCandidates(50)).thenReturn(List.of(row));
        when(mapper.claimCleanup(7L)).thenReturn(1);
        when(mapper.completeCleanup(7L)).thenReturn(1);

        int cleaned = new SupersededBackupStageCleanupService(mapper, store).cleanupBatch();

        assertThat(cleaned).isEqualTo(1);
        verify(store).delete(3L, "orders/42.zip");
        verify(mapper).completeCleanup(7L);
        verify(mapper, never()).failCleanup(anyLong(), anyString(), any());
    }

    @Test
    void failedDeletionIsPersistedForRetryWithoutFailingTheWholeBatch() throws Exception {
        BackupStageMapper mapper = mock(BackupStageMapper.class);
        VolumeArtifactStore store = mock(VolumeArtifactStore.class);
        BackupStageEntity row = stage();
        when(mapper.findCleanupCandidates(50)).thenReturn(List.of(row));
        when(mapper.claimCleanup(7L)).thenReturn(1);
        doThrow(new IOException("offline")).when(store).delete(3L, "orders/42.zip");

        int cleaned = new SupersededBackupStageCleanupService(mapper, store).cleanupBatch();

        assertThat(cleaned).isZero();
        verify(mapper).failCleanup(eq(7L), contains("offline"), any());
    }

    private static BackupStageEntity stage() {
        BackupStageEntity row = new BackupStageEntity();
        row.setId(7L);
        row.setStorageVolumeId(3L);
        row.setStorageKey("orders/42.zip");
        row.setStageStatus(BackupStageService.SUPERSEDED);
        row.setCleanupStatus("PENDING");
        row.setCleanupRetryCount(0);
        return row;
    }
}
