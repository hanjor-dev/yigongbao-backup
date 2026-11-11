package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.PartialJobFailureException;
import com.yigongbao.backup.remote.RemoteCallException;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DegradedBackupJobHandlerTest {
    @TempDir
    Path temporaryStore;

    @Test
    void degradedTaskUsesOnlyExplicitDegradedManifestEndpoint() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetchDegraded("42")).thenThrow(new RemoteCallException(
                "关系已修复", 409, false, "STATE_CONFLICT", "request-1", "关系已修复", null));
        ObjectProvider<OnlineManifestClient> manifestProvider = mock(ObjectProvider.class);
        when(manifestProvider.getIfAvailable()).thenReturn(manifests);
        ObjectProvider<CosObjectClient> cosProvider = mock(ObjectProvider.class);
        when(cosProvider.getIfAvailable()).thenReturn(mock(CosObjectClient.class));
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(3);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("0,0,0");
        ObjectMapper objectMapper = new ObjectMapper();
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider,
                mock(ArtifactStore.class), objectMapper, mock(SpaceReservationService.class),
                mock(BackupEvidenceService.class), mock(BackupStageService.class), configs,
                new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity();
        job.setId(6L);
        job.setJobType("DEGRADED_BACKUP");
        job.setConfigSnapshotJson(objectMapper.writeValueAsString(new ManualBackupJobHandler.Payload(List.of(42L))));

        assertThatThrownBy(() -> handler.execute(mock(JobExecutionContext.class), job))
                .isInstanceOf(PartialJobFailureException.class)
                .hasMessageContaining("关系已修复");

        verify(manifests).fetchDegraded("42");
        verify(manifests, never()).fetch(anyString());
    }

    @Test
    void manifestChangedForcesOneFreshDegradedReadWhenConfiguredRetriesAreDisabled() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        BackupManifest manifest = new BackupManifest("42", "2026-01-01T00:00:00Z", List.of(),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 2, "INCOMPLETE",
                List.of(new BackupManifest.RelationGap("ORDER_FILE", "100", "d".repeat(64),
                        "10.1", "200", "FILE_RECORD_NOT_FOUND", "missing", "e".repeat(64))),
                1, "f".repeat(64), "9".repeat(64), Map.of("id", 42), Map.of("id", 42));
        when(manifests.fetchDegraded("42")).thenReturn(manifest);
        doThrow(new RemoteCallException("changed", 409, true, "MANIFEST_CHANGED", "r", "changed", null))
                .doNothing().when(manifests).confirm(eq("42"), any(BackupManifest.class));
        ObjectProvider<OnlineManifestClient> manifestProvider = mock(ObjectProvider.class);
        when(manifestProvider.getIfAvailable()).thenReturn(manifests);
        ObjectProvider<CosObjectClient> cosProvider = mock(ObjectProvider.class);
        when(cosProvider.getIfAvailable()).thenReturn(mock(CosObjectClient.class));
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(0);
        when(configs.getInt("storage.backup-reservation-percent", 200, 100)).thenReturn(100);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("60,300,900");
        SpaceReservationService reservations = mock(SpaceReservationService.class);
        when(reservations.reserve(eq("hot"), anyLong(), eq(6L)))
                .thenReturn(new SpaceReservation("hot", 1L, "reservation-1"));
        BackupStageService stages = mock(BackupStageService.class);
        when(stages.stage(eq(6L), eq(42L), any(), any(), isNull(), anyString())).thenAnswer(invocation -> {
            OrderBackupPipeline.Result result = invocation.getArgument(3);
            BackupStageEntity stage = new BackupStageEntity();
            stage.setId(7L); stage.setJobId(6L); stage.setSourceOrderId(42L);
            stage.setIdempotencyKey(invocation.getArgument(5)); stage.setStageStatus(BackupStageService.STAGED);
            stage.setStorageKey(result.storageKey()); stage.setPackageSha256(result.packageSha256());
            stage.setPackageSize(result.packageSize()); stage.setReceiptCompletedAt(java.time.Instant.parse("2026-11-10T10:15:30Z"));
            return stage;
        });
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(19L);
        when(stages.publish(7L)).thenReturn(snapshot);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider,
                new LocalDirectoryArtifactStore(temporaryStore), objectMapper, reservations,
                mock(BackupEvidenceService.class), stages, configs,
                new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity();
        job.setId(6L); job.setJobType("DEGRADED_BACKUP");
        job.setConfigSnapshotJson(objectMapper.writeValueAsString(new ManualBackupJobHandler.Payload(List.of(42L))));

        handler.execute(mock(JobExecutionContext.class), job);

        verify(manifests, times(2)).fetchDegraded("42");
        verify(manifests, times(2)).confirm(eq("42"), any(BackupManifest.class));
        verify(stages).publish(7L);
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(temporaryStore)) {
            assertThat(files).isNotEmpty();
        }
    }
}
