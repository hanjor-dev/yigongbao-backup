package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackupStageServiceTest {
    @Test
    void concurrentReconciliationRequiresAnAuthoritativeAvailableSnapshot() {
        BackupStageMapper mapper = mock(BackupStageMapper.class);
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        BackupStageEntity available = new BackupStageEntity();
        available.setId(7L); available.setSourceOrderId(42L); available.setPublishedSnapshotId(19L);
        available.setStageStatus("AVAILABLE"); available.setBackupCompleteness("INCOMPLETE");
        OrderSnapshotEntity snapshot = new OrderSnapshotEntity();
        snapshot.setId(19L); snapshot.setSourceOrderId(42L); snapshot.setSnapshotStatus("AVAILABLE");
        snapshot.setBackupCompleteness("INCOMPLETE");
        when(evidence.findSnapshot(19L)).thenReturn(snapshot);
        BackupStageService service = new BackupStageService(mapper, evidence, new ObjectMapper());

        assertThat(service.requirePublishedSnapshot(available)).isSameAs(snapshot);

        snapshot.setSnapshotStatus("PENDING_RECEIPT");
        assertThatThrownBy(() -> service.requirePublishedSnapshot(available))
                .hasMessageContaining("权威快照");
    }

    @Test
    void stagesInvisibleEvidenceThenPublishesExactlyOnceAfterReceiptAcceptance() {
        BackupStageMapper mapper = mock(BackupStageMapper.class);
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        AtomicReference<BackupStageEntity> stored = new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(mapper.selectById(anyLong())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            BackupStageEntity row = invocation.getArgument(0);
            row.setId(7L);
            row.setVersion(0);
            stored.set(row);
            return 1;
        }).when(mapper).insert(any(BackupStageEntity.class));
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return 1; })
                .when(mapper).updateById(any(BackupStageEntity.class));
        OrderSnapshotEntity snapshot = new OrderSnapshotEntity();
        snapshot.setId(19L);
        when(evidence.save(eq(42L), any(), any(), eq(3L))).thenReturn(snapshot);
        when(evidence.findSnapshot(19L)).thenReturn(snapshot);
        BackupStageService service = new BackupStageService(mapper, evidence, new ObjectMapper());

        BackupStageEntity stage = service.stage(5L, 42L, manifest("INCOMPLETE"), result(), 3L, "receipt-42");

        assertThat(stage.getStageStatus()).isEqualTo("STAGED");
        assertThat(stage.getReceiptCompletedAt()).isNotNull();
        verifyNoInteractions(evidence);

        service.markReceiptAccepted(stage.getId());
        OrderSnapshotEntity published = service.publish(stage.getId());
        snapshot.setSourceOrderId(42L);
        snapshot.setSnapshotStatus("AVAILABLE");
        snapshot.setBackupCompleteness("INCOMPLETE");
        OrderSnapshotEntity replayed = service.publish(stage.getId());

        assertThat(published.getId()).isEqualTo(19L);
        assertThat(replayed.getId()).isEqualTo(19L);
        assertThat(stored.get().getStageStatus()).isEqualTo("AVAILABLE");
        verify(evidence, times(1)).save(eq(42L), any(), any(), eq(3L));
        verify(evidence, times(1)).markAvailable(42L);
    }

    @Test
    void resumesSameIdempotencyKeyAndNeverPublishesSupersededLoser() {
        BackupStageMapper mapper = mock(BackupStageMapper.class);
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        BackupStageEntity existing = new BackupStageEntity();
        existing.setId(8L);
        existing.setJobId(6L);
        existing.setSourceOrderId(42L);
        existing.setIdempotencyKey("receipt-42");
        existing.setManifestSha256("b".repeat(64));
        existing.setPackageSha256("9".repeat(64));
        existing.setStorageKey("orders/42.zip");
        existing.setStageStatus("STAGED");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.selectById(8L)).thenReturn(existing);
        BackupStageService service = new BackupStageService(mapper, evidence, new ObjectMapper());

        BackupStageEntity replayed = service.stage(6L, 42L, manifest("INCOMPLETE"), result(), 3L, "receipt-42");
        service.supersede(replayed.getId());

        assertThat(replayed).isSameAs(existing);
        assertThat(existing.getStageStatus()).isEqualTo("SUPERSEDED");
        assertThat(existing.getCleanupStatus()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.publish(8L)).hasMessageContaining("SUPERSEDED");
        verifyNoInteractions(evidence);
    }

    private static BackupManifest manifest(String completeness) {
        return new BackupManifest("42", "2026-01-01T00:00:00Z", List.of(),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 2, completeness,
                List.of(), 0, "d".repeat(64), "e".repeat(64), Map.of("id", 42), Map.of("id", 42));
    }

    private static OrderBackupPipeline.Result result() {
        return new OrderBackupPipeline.Result("orders/42.zip", "9".repeat(64), 100L, Map.of(), Map.of());
    }
}
