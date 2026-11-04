package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 模拟执行不得进入 COS、容量预留、正式证据和线上回执链路。 */
class MetadataSimulationOrderExecutionTest {
    @TempDir Path root;

    @Test
    void simulationReadsManifestButDoesNotCallSensitiveCollaborators() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        BackupManifest manifest = new BackupManifest("6", "2026-07-02T13:14:13Z",
                List.of(new BackupManifest.Asset("a1", "report.pdf", "REPORT", 12, "signed-url")),
                "revision", "sha", "relations", Map.of("orderNo", "20260702000002", "status", "1040"));
        when(manifests.fetch("6")).thenReturn(manifest);
        ObjectProvider<OnlineManifestClient> manifestProvider = provider(manifests);
        CosObjectClient cos = mock(CosObjectClient.class);
        ObjectProvider<CosObjectClient> cosProvider = provider(cos);
        SpaceReservationService reservations = mock(SpaceReservationService.class);
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        when(evidence.saveSimulation(anyLong(), any(), any())).thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(0);
        // 模拟任务创建后即使当前部署已切回 REAL，仍应使用任务快照目录安全续跑。
        BackupExecutionModeProvider modes = new BackupExecutionModeProvider(new MockEnvironment());
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider,
                new LocalDirectoryArtifactStore(root.resolve("hot-1")), new ObjectMapper(), reservations, evidence, configs, modes);
        JobEntity job = new JobEntity(); job.setId(99L); job.setJobNo("job-99");
        job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(new ManualBackupJobHandler.Payload(
                List.of(6L), 1, "20261104", 7, "2026-10-28T00:00:00Z", "METADATA_SIMULATION", root.toString())));
        JobExecutionContext context = mock(JobExecutionContext.class);

        handler.execute(context, job);

        verify(manifests).fetch("6");
        verify(manifests, never()).confirm(anyString(), any());
        verify(manifests, never()).submitReceipt(anyString(), any(), any(), anyString(), anyInt());
        verifyNoInteractions(cos, reservations);
        verify(evidence).saveSimulation(eq(6L), eq(manifest), any(OrderBackupPipeline.Result.class));
        verify(evidence).markSimulationAvailable(6L);
        verify(context).orderSimulationMetadata(eq(1), eq(12L), eq("orders/20261104/20260702000002-6.zip"));
        verify(context).orderCompleted("SIMULATED");
        org.assertj.core.api.Assertions.assertThat(root.resolve("hot-1/orders/20261104/20260702000002-6.zip")).isRegularFile();
    }

    @Test
    void failedOrderIsRetriedOnlyAfterTheInitialPassFinishes() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        BackupManifest first = manifest("1", "20260001");
        BackupManifest second = manifest("2", "20260002");
        when(manifests.fetch("1")).thenThrow(new IllegalStateException("temporary failure")).thenReturn(first);
        when(manifests.fetch("2")).thenReturn(second);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(1);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("0");
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        when(evidence.saveSimulation(anyLong(), any(), any())).thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
        ManualBackupJobHandler handler = new ManualBackupJobHandler(provider(manifests), provider(mock(CosObjectClient.class)),
                new LocalDirectoryArtifactStore(root.resolve("hot-2")), new ObjectMapper(), mock(SpaceReservationService.class),
                evidence, configs,
                new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity(); job.setId(100L); job.setJobNo("job-100");
        job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(new ManualBackupJobHandler.Payload(
                List.of(1L, 2L), 2, "20261104", 7, "2026-10-28T00:00:00Z", "METADATA_SIMULATION", root.toString())));
        JobExecutionContext context = mock(JobExecutionContext.class);

        handler.execute(context, job);

        var order = inOrder(manifests, context);
        order.verify(manifests).fetch("1");
        order.verify(manifests).fetch("2");
        order.verify(context).failedOrderRetryPhaseStarted(1);
        order.verify(manifests).fetch("1");
        verify(context).orderQueuedForRetry(eq(1L), any(Throwable.class));
    }

    private static BackupManifest manifest(String id, String orderNo) {
        return new BackupManifest(id, "2026-07-02T13:14:13Z", List.of(),
                "revision-" + id, "sha", "relations", Map.of("orderNo", orderNo, "status", "1040"));
    }

    private static OrderSnapshotEntity snapshot(long orderId) {
        OrderSnapshotEntity value = new OrderSnapshotEntity();
        value.setId(orderId + 1000); value.setSourceOrderId(orderId); value.setOrderCode("O-" + orderId);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
