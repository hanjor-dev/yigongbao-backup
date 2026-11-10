package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.order.*;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DegradedBackupEvidenceTest {
    @Test
    void persistsIncompleteSnapshotAndGapsSeparatelyFromRealAssets() {
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        AssetMapper assets = mock(AssetMapper.class);
        AssetRelationMapper relations = mock(AssetRelationMapper.class);
        RelationGapMapper gaps = mock(RelationGapMapper.class);
        OrderPackageMapper packages = mock(OrderPackageMapper.class);
        when(snapshots.selectOne(any())).thenReturn(null);
        when(assets.selectList(any())).thenReturn(List.of());
        when(packages.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { ((OrderSnapshotEntity) invocation.getArgument(0)).setId(10L); return 1; })
                .when(snapshots).insert(any(OrderSnapshotEntity.class));
        doAnswer(invocation -> { ((OrderPackageEntity) invocation.getArgument(0)).setId(20L); return 1; })
                .when(packages).insert(any(OrderPackageEntity.class));
        BackupEvidenceService service = new BackupEvidenceService(snapshots, assets, relations, gaps,
                packages, mock(ArtifactCopyMapper.class), new ObjectMapper());
        var gap = new BackupManifest.RelationGap("ORDER_FILE", "100", "a".repeat(64), "10.1", "200",
                "FILE_RECORD_NOT_FOUND", "订单文件不存在: 200", "b".repeat(64));
        BackupManifest manifest = new BackupManifest("42", "2026-01-01T00:00:00Z", List.of(),
                "c".repeat(64), "c".repeat(64), "d".repeat(64), 2, "INCOMPLETE", List.of(gap), 1,
                "e".repeat(64), "f".repeat(64), Map.of("id", 42), Map.of("id", 42));

        OrderSnapshotEntity saved = service.save(42L, manifest, new OrderBackupPipeline.Result(
                "orders/42.zip", "9".repeat(64), 100L, Map.of()));

        assertThat(saved.getBackupCompleteness()).isEqualTo("INCOMPLETE");
        assertThat(saved.getManifestSchemaVersion()).isEqualTo(2);
        assertThat(saved.getRelationGapCount()).isEqualTo(1);
        verify(assets, never()).insert(any(AssetEntity.class));
        verify(gaps).insert(argThat((RelationGapEntity row) -> row.getSnapshotId().equals(10L)
                && row.getReasonCode().equals("FILE_RECORD_NOT_FOUND")
                && row.getOriginalFileId().equals("200")));
        verify(packages).insert(argThat((OrderPackageEntity pkg) -> pkg.getFileCount() == 0));
    }

    @Test
    void migrationBackfillsRealAndSimulationCompletenessWithoutInventingHashes() throws Exception {
        Path migration = Path.of("sql/migration/2026-11-10-10-degraded-order-backup.sql");
        String sql = Files.readString(migration);

        assertThat(sql).contains("backup_mode = 'METADATA_SIMULATION'", "NOT_APPLICABLE", "COMPLETE",
                "manifest_schema_version", "relation_gap_count", "bk_relation_gap");
        assertThat(sql).doesNotContain("SET relationship_state_hash = SHA");
    }
}
