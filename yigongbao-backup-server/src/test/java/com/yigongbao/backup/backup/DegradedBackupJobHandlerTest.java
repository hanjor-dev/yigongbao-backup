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
    void relationBrokenStrictTaskUsesDegradedManifestOnlyWhenFrozenPolicyIsEnabled() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenThrow(relationBroken());
        when(manifests.fetchDegraded("42")).thenReturn(incompleteManifest());
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(19L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        ManualBackupJobHandler handler = simulationHandler(manifests, evidence);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobEntity job = strictSimulationJob(mapper, true);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        handler.execute(context, job);

        verify(manifests).fetch("42");
        verify(manifests).fetchDegraded("42");
        verify(evidence).saveSimulation(eq(42L), argThat(manifest ->
                "INCOMPLETE".equals(manifest.backupCompleteness())), any());
        verify(context).orderQueuedForAutomaticDegraded(eq(42L), any(RemoteCallException.class));
        verify(context, atLeastOnce()).orderDegrading(42L);
        org.mockito.ArgumentCaptor<String> resultJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).result(resultJson.capture());
        com.fasterxml.jackson.databind.JsonNode summary = mapper.readTree(resultJson.getValue());
        assertThat(summary.path("completeSucceeded").asInt()).isZero();
        assertThat(summary.path("incompleteSucceeded").asInt()).isEqualTo(1);
        assertThat(summary.path("orderFailed").asInt()).isZero();
        assertThat(summary.path("autoDegradedAttempted").asInt()).isEqualTo(1);
        assertThat(summary.path("autoDegradedSucceeded").asInt()).isEqualTo(1);
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(temporaryStore.resolve("store"))) {
            Path archive = files.filter(path -> path.toString().endsWith(".zip")).findFirst().orElseThrow();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
                assertThat(zip.getEntry("manifest.json")).isNotNull();
                String manifestJson = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertThat(manifestJson).contains("\"backupCompleteness\" : \"INCOMPLETE\"",
                        "\"relationGaps\"", "\"orderRaw\"");
                assertThat(zip.stream().filter(entry -> entry.getName().startsWith("files/"))).isEmpty();
            }
        }
    }

    @Test
    void restartReconstructsPendingAutomaticDegradedOrderWithoutRepeatingStrictManifest() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetchDegraded("42")).thenReturn(incompleteManifest());
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(20L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        ManualBackupJobHandler handler = simulationHandler(manifests, evidence);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        com.yigongbao.backup.job.JobOrderEntity pending = new com.yigongbao.backup.job.JobOrderEntity();
        pending.setSourceOrderId(42L); pending.setStatus("DEGRADE_PENDING");
        pending.setFailureCode("RELATION_BROKEN"); pending.setFailureMessage("missing");
        when(context.orderStates()).thenReturn(List.of(pending));

        handler.execute(context, strictSimulationJob(mapper, true));

        verify(manifests, never()).fetch("42");
        verify(manifests).fetchDegraded("42");
        verify(context, atLeastOnce()).orderDegrading(42L);
    }

    @Test
    void restartSummaryKeepsPersistedIncompleteSuccessClassification() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        ManualBackupJobHandler handler = simulationHandler(manifests, mock(BackupEvidenceService.class));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        com.yigongbao.backup.job.JobOrderEntity completed = new com.yigongbao.backup.job.JobOrderEntity();
        completed.setSourceOrderId(42L); completed.setStatus("SUCCEEDED");
        completed.setBackupCompleteness("INCOMPLETE"); completed.setRelationGapCount(1);
        when(context.orderStates()).thenReturn(List.of(completed));

        handler.execute(context, strictSimulationJob(mapper, true));

        verify(manifests, never()).fetch(anyString());
        org.mockito.ArgumentCaptor<String> resultJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).result(resultJson.capture());
        com.fasterxml.jackson.databind.JsonNode summary = mapper.readTree(resultJson.getValue());
        assertThat(summary.path("completeSucceeded").asInt()).isZero();
        assertThat(summary.path("incompleteSucceeded").asInt()).isEqualTo(1);
        assertThat(summary.path("autoDegradedAttempted").asInt()).isEqualTo(1);
    }

    @Test
    void restartResumesStrictConfirmationPhaseWithoutRepeatingInitialDegradedRead() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenReturn(completeManifest());
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(31L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        JobExecutionContext context = mock(JobExecutionContext.class);
        com.yigongbao.backup.job.JobOrderEntity pending = pendingAtPhase("STRICT_CONFIRMATION");
        when(context.orderStates()).thenReturn(List.of(pending));

        simulationHandler(manifests, evidence).execute(context,
                strictSimulationJob(new ObjectMapper().findAndRegisterModules(), true));

        verify(manifests).fetch("42");
        verify(manifests, never()).fetchDegraded(anyString());
    }

    @Test
    void restartResumesFinalDegradedPhaseWithoutRepeatingStrictConfirmation() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetchDegraded("42")).thenReturn(incompleteManifest());
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(32L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        JobExecutionContext context = mock(JobExecutionContext.class);
        com.yigongbao.backup.job.JobOrderEntity pending = pendingAtPhase("DEGRADED_FINAL");
        when(context.orderStates()).thenReturn(List.of(pending));

        simulationHandler(manifests, evidence).execute(context,
                strictSimulationJob(new ObjectMapper().findAndRegisterModules(), true));

        verify(manifests).fetchDegraded("42");
        verify(manifests, never()).fetch(anyString());
    }

    @Test
    void automaticDegradedPhaseRetriesTransientFailureWithinTheOrderRetryBudget() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenThrow(relationBroken());
        when(manifests.fetchDegraded("42"))
                .thenThrow(new RemoteCallException("temporary timeout", 504, true,
                        "REMOTE_TIMEOUT", "request-timeout", "temporary timeout", null))
                .thenReturn(incompleteManifest());
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(21L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        ManualBackupJobHandler handler = simulationHandler(manifests, evidence, 1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        handler.execute(context, strictSimulationJob(mapper, true));

        verify(manifests).fetch("42");
        verify(manifests, times(2)).fetchDegraded("42");
        verify(evidence).saveSimulation(eq(42L), argThat(manifest ->
                "INCOMPLETE".equals(manifest.backupCompleteness())), any());
    }

    @Test
    void restartDoesNotResetConsumedAutomaticRetryBudget() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetchDegraded("42")).thenThrow(new RemoteCallException(
                "temporary timeout", 504, true, "REMOTE_TIMEOUT", "request-timeout", "temporary timeout", null));
        ManualBackupJobHandler handler = simulationHandler(manifests, mock(BackupEvidenceService.class), 1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        com.yigongbao.backup.job.JobOrderEntity pending = new com.yigongbao.backup.job.JobOrderEntity();
        pending.setSourceOrderId(42L); pending.setStatus("DEGRADE_PENDING"); pending.setRetryCount(1);
        pending.setFailureCode("RELATION_BROKEN"); pending.setFailureMessage("missing");
        when(context.orderStates()).thenReturn(List.of(pending));

        assertThatThrownBy(() -> handler.execute(context, strictSimulationJob(mapper, true)))
                .isInstanceOf(PartialJobFailureException.class);

        verify(manifests, times(1)).fetchDegraded("42");
        verify(context, never()).orderRetryConsumed(42L);
    }

    @Test
    void relationRepairDuringCompensationCountsAsCompleteButNotDegradedSuccess() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenThrow(relationBroken()).thenReturn(completeManifest());
        when(manifests.fetchDegraded("42")).thenThrow(new RemoteCallException(
                "relations repaired", 409, false, "RELATION_GAPS_RESOLVED",
                "request-repaired", "relations repaired", null));
        BackupEvidenceService evidence = mock(BackupEvidenceService.class);
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(22L); snapshot.setOrderCode("O-42");
        when(evidence.saveSimulation(eq(42L), any(), any())).thenReturn(snapshot);
        ManualBackupJobHandler handler = simulationHandler(manifests, evidence);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        handler.execute(context, strictSimulationJob(mapper, true));

        org.mockito.ArgumentCaptor<String> resultJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).result(resultJson.capture());
        com.fasterxml.jackson.databind.JsonNode summary = mapper.readTree(resultJson.getValue());
        assertThat(summary.path("completeSucceeded").asInt()).isEqualTo(1);
        assertThat(summary.path("incompleteSucceeded").asInt()).isZero();
        assertThat(summary.path("autoDegradedAttempted").asInt()).isEqualTo(1);
        assertThat(summary.path("autoDegradedSucceeded").asInt()).isZero();
    }

    @Test
    void oscillatingRelationStatePersistsStableLocalFailureCode() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenThrow(relationBroken(), relationBroken());
        when(manifests.fetchDegraded("42")).thenThrow(
                new RemoteCallException("relations repaired", 409, false, "RELATION_GAPS_RESOLVED",
                        "request-repaired-1", "relations repaired", null),
                new RemoteCallException("relations repaired again", 409, false, "RELATION_GAPS_RESOLVED",
                        "request-repaired-2", "relations repaired again", null));
        ManualBackupJobHandler handler = simulationHandler(manifests, mock(BackupEvidenceService.class));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        assertThatThrownBy(() -> handler.execute(context, strictSimulationJob(mapper, true)))
                .isInstanceOf(PartialJobFailureException.class);

        verify(context).orderFailed(eq("SIMULATION_FAILED"), argThat(failure ->
                failure instanceof com.yigongbao.backup.job.JobFailureException coded
                        && AutomaticDegradedBackupPolicy.RELATION_STATE_UNSTABLE.equals(coded.code())));
    }

    @Test
    void disabledPolicyLeavesRelationBrokenAsTerminalFailureWithoutTextMatching() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("42")).thenThrow(relationBroken());
        ManualBackupJobHandler handler = simulationHandler(manifests, mock(BackupEvidenceService.class));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        assertThatThrownBy(() -> handler.execute(mock(JobExecutionContext.class), strictSimulationJob(mapper, false)))
                .isInstanceOf(PartialJobFailureException.class);

        verify(manifests, never()).fetchDegraded(anyString());
    }

    @Test
    void degradedTaskUsesOnlyExplicitDegradedManifestEndpoint() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetchDegraded("42")).thenThrow(new RemoteCallException(
                "关系已修复", 409, false, "RELATION_GAPS_RESOLVED", "request-1", "关系已修复", null));
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
            assertThat((String) invocation.getArgument(5)).startsWith("backup:").hasSize(71);
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

    @Test
    void receiptConflictReconcilesOnlyWithAValidPublishedLocalSnapshot() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        BackupManifest manifest = completeManifest();
        when(manifests.fetch("42")).thenReturn(manifest);
        doThrow(new RemoteCallException("another worker already committed", 409, false,
                "STATE_CONFLICT", "request-conflict", "terminal receipt exists", null))
                .when(manifests).submitReceipt(eq("42"), any(), any(), anyString(), eq(1), any());
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
                .thenReturn(new SpaceReservation("hot", 1L, "reservation-conflict"));
        BackupStageService stages = mock(BackupStageService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        java.util.concurrent.atomic.AtomicReference<BackupStageEntity> availableRef = new java.util.concurrent.atomic.AtomicReference<>();
        when(stages.stage(eq(6L), eq(42L), any(), any(), isNull(), anyString())).thenAnswer(invocation -> {
            BackupManifest stagedManifest = invocation.getArgument(2);
            OrderBackupPipeline.Result result = invocation.getArgument(3);
            BackupStageEntity current = stageFrom(7L, BackupStageService.STAGED, stagedManifest, result, objectMapper);
            current.setIdempotencyKey(invocation.getArgument(5));
            current.setReceiptCompletedAt(java.time.Instant.parse("2026-11-12T10:15:30Z"));
            BackupStageEntity available = stageFrom(8L, BackupStageService.AVAILABLE, incompleteManifest(), result, objectMapper);
            available.setPublishedSnapshotId(88L);
            availableRef.set(available);
            return current;
        });
        when(stages.findAvailableByOrder(42L)).thenAnswer(ignored -> availableRef.get());
        when(stages.requirePublishedSnapshot(any(BackupStageEntity.class))).thenAnswer(invocation -> {
            BackupStageEntity available = invocation.getArgument(0);
            com.yigongbao.backup.order.OrderSnapshotEntity snapshot =
                    new com.yigongbao.backup.order.OrderSnapshotEntity();
            snapshot.setId(available.getPublishedSnapshotId());
            snapshot.setSourceOrderId(available.getSourceOrderId());
            snapshot.setSnapshotStatus("AVAILABLE");
            snapshot.setBackupCompleteness(available.getBackupCompleteness());
            return snapshot;
        });
        when(stages.material(any(BackupStageEntity.class))).thenAnswer(invocation -> {
            BackupStageEntity row = invocation.getArgument(0);
            return new BackupStageService.Material(
                    objectMapper.readValue(row.getManifestJson(), BackupManifest.class),
                    objectMapper.readValue(row.getResultJson(), OrderBackupPipeline.Result.class));
        });
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider,
                new LocalDirectoryArtifactStore(temporaryStore.resolve("conflict-store")), objectMapper,
                reservations, mock(BackupEvidenceService.class), stages, configs,
                new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity();
        job.setId(6L); job.setJobNo("JOB-6"); job.setJobType("MANUAL_BACKUP");
        job.setConfigSnapshotJson(objectMapper.writeValueAsString(
                ManualBackupJobHandler.Payload.strictOrders(List.of(42L), true)));
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        handler.execute(context, job);

        verify(stages).supersede(7L);
        verify(stages).findAvailableByOrder(42L);
        verify(context).orderSucceeded();
        verify(stages, never()).publish(7L);
        org.mockito.ArgumentCaptor<String> resultJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).result(resultJson.capture());
        assertThat(objectMapper.readTree(resultJson.getValue()).path("incompleteSucceeded").asInt()).isEqualTo(1);
    }

    @Test
    void restartRecoversPublishedStageBeforeAnyRemoteManifestCall() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        ObjectProvider<OnlineManifestClient> manifestProvider = mock(ObjectProvider.class);
        when(manifestProvider.getIfAvailable()).thenReturn(manifests);
        ObjectProvider<CosObjectClient> cosProvider = mock(ObjectProvider.class);
        when(cosProvider.getIfAvailable()).thenReturn(mock(CosObjectClient.class));
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(0);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("0");
        LocalDirectoryArtifactStore artifactStore = new LocalDirectoryArtifactStore(temporaryStore.resolve("recovery"));
        byte[] packageBytes = "published-package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        artifactStore.put("orders/published.zip", new java.io.ByteArrayInputStream(packageBytes));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(packageBytes));
        OrderBackupPipeline.Result result = new OrderBackupPipeline.Result(
                "orders/published.zip", sha, packageBytes.length, Map.of(), Map.of());
        BackupManifest manifest = completeManifest();
        BackupStageEntity available = stageFrom(12L, BackupStageService.AVAILABLE, manifest, result, objectMapper);
        available.setJobId(6L); available.setPublishedSnapshotId(88L);
        BackupStageService stages = mock(BackupStageService.class);
        when(stages.findAvailableByJobOrder(6L, 42L, "COMPLETE")).thenReturn(available);
        when(stages.material(available)).thenReturn(new BackupStageService.Material(manifest, result));
        com.yigongbao.backup.order.OrderSnapshotEntity snapshot = new com.yigongbao.backup.order.OrderSnapshotEntity();
        snapshot.setId(88L); snapshot.setSourceOrderId(42L); snapshot.setSnapshotStatus("AVAILABLE");
        snapshot.setBackupCompleteness("COMPLETE");
        when(stages.requirePublishedSnapshot(available)).thenReturn(snapshot);
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider, artifactStore,
                objectMapper, mock(SpaceReservationService.class), mock(BackupEvidenceService.class), stages,
                configs, new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity();
        job.setId(6L); job.setJobNo("JOB-6"); job.setJobType("MANUAL_BACKUP");
        job.setConfigSnapshotJson(objectMapper.writeValueAsString(
                ManualBackupJobHandler.Payload.strictOrders(List.of(42L), true)));
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.orderStates()).thenReturn(List.of());

        handler.execute(context, job);

        verify(manifests, never()).fetch(anyString());
        verify(manifests, never()).submitReceipt(anyString(), any(), any(), anyString(), anyInt(), any());
        verify(context).orderSucceeded();
    }

    @Test
    void receiptIdempotencyKeyIsBoundedDeterministicAndModeSpecific() {
        String complete = ManualBackupJobHandler.receiptIdempotencyKey(Long.MAX_VALUE, Long.MAX_VALUE,
                "r".repeat(256), "COMPLETE");
        String incomplete = ManualBackupJobHandler.receiptIdempotencyKey(Long.MAX_VALUE, Long.MAX_VALUE,
                "r".repeat(256), "INCOMPLETE");

        assertThat(complete).hasSize(71).isEqualTo(
                ManualBackupJobHandler.receiptIdempotencyKey(Long.MAX_VALUE, Long.MAX_VALUE,
                        "r".repeat(256), "COMPLETE"));
        assertThat(incomplete).hasSize(71).isNotEqualTo(complete);
    }

    private ManualBackupJobHandler simulationHandler(OnlineManifestClient manifests, BackupEvidenceService evidence) {
        return simulationHandler(manifests, evidence, 0);
    }

    private static com.yigongbao.backup.job.JobOrderEntity pendingAtPhase(String phase) {
        com.yigongbao.backup.job.JobOrderEntity pending = new com.yigongbao.backup.job.JobOrderEntity();
        pending.setSourceOrderId(42L);
        pending.setStatus("DEGRADING");
        pending.setFailureCode("RELATION_BROKEN");
        pending.setFailureMessage("missing");
        pending.setDegradePhase(phase);
        return pending;
    }

    private ManualBackupJobHandler simulationHandler(OnlineManifestClient manifests, BackupEvidenceService evidence,
                                                      int maxRetries) {
        ObjectProvider<OnlineManifestClient> manifestProvider = mock(ObjectProvider.class);
        when(manifestProvider.getIfAvailable()).thenReturn(manifests);
        ObjectProvider<CosObjectClient> cosProvider = mock(ObjectProvider.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(maxRetries);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("0,0,0");
        return new ManualBackupJobHandler(manifestProvider, cosProvider,
                new LocalDirectoryArtifactStore(temporaryStore.resolve("store")),
                new ObjectMapper().findAndRegisterModules(), mock(SpaceReservationService.class),
                evidence, configs, new BackupExecutionModeProvider(new MockEnvironment()));
    }

    private JobEntity strictSimulationJob(ObjectMapper mapper, boolean enabled) throws Exception {
        JobEntity job = new JobEntity();
        job.setId(6L); job.setJobNo("JOB-6"); job.setJobType("MANUAL_BACKUP");
        job.setConfigSnapshotJson(mapper.writeValueAsString(new ManualBackupJobHandler.Payload(
                List.of(42L), 1, "20261112", null, null, "METADATA_SIMULATION",
                temporaryStore.resolve("simulation").toString(), enabled)));
        return job;
    }

    private static RemoteCallException relationBroken() {
        return new RemoteCallException("任意文案", 409, false, "RELATION_BROKEN",
                "request-relation", "FILE_RECORD_DELETED", null);
    }

    private static BackupManifest incompleteManifest() {
        return new BackupManifest("42", "2026-01-01T00:00:00Z", List.of(),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 2, "INCOMPLETE",
                List.of(new BackupManifest.RelationGap("ORDER_FILE", "100", "d".repeat(64),
                        "10.1", "200", "FILE_RECORD_DELETED", "deleted", "e".repeat(64))),
                1, "f".repeat(64), "9".repeat(64), Map.of("id", 42), Map.of("id", 42));
    }

    private static BackupManifest completeManifest() {
        return new BackupManifest("42", "2026-01-01T00:00:00Z", List.of(),
                "1".repeat(64), "2".repeat(64), "3".repeat(64), 2, "COMPLETE",
                List.of(), 0, "4".repeat(64), "5".repeat(64), Map.of("id", 42), Map.of("id", 42));
    }

    private static BackupStageEntity stageFrom(Long id, String status, BackupManifest manifest,
                                               OrderBackupPipeline.Result result, ObjectMapper mapper) throws Exception {
        BackupStageEntity stage = new BackupStageEntity();
        stage.setId(id); stage.setJobId(6L); stage.setSourceOrderId(42L); stage.setStageStatus(status);
        stage.setBackupCompleteness(manifest.backupCompleteness());
        stage.setManifestSha256(manifest.manifestSha256());
        stage.setStorageKey(result.storageKey()); stage.setPackageSha256(result.packageSha256());
        stage.setPackageSize(result.packageSize()); stage.setManifestJson(mapper.writeValueAsString(manifest));
        stage.setResultJson(mapper.writeValueAsString(result));
        return stage;
    }
}
