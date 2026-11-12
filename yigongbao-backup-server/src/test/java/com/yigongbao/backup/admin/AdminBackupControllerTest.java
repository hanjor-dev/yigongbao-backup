package com.yigongbao.backup.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import cn.dev33.satoken.stp.StpUtil;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.yigongbao.backup.backup.BackupJobCreationService;

/** 失败订单补偿任务必须继承原任务的执行模式。 */
class AdminBackupControllerTest {
    @Test
    void runNowPassesCurrentAdministratorToManualScan() {
        com.yigongbao.backup.backup.ScheduledBackupService scheduled =
                mock(com.yigongbao.backup.backup.ScheduledBackupService.class);
        AdminBackupController controller = new AdminBackupController(
                mock(com.yigongbao.backup.job.PersistentJobService.class),
                mock(BackupJobCreationService.class),
                mock(com.yigongbao.backup.job.JobExecutionLogQueryService.class),
                scheduled, new ObjectMapper());

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("ADMIN")).thenAnswer(invocation -> null);
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(9L);
            controller.runScheduleNow();
        }

        verify(scheduled).runNowByAdministrator(9L);
    }

    @Test
    void administratorRetryCreatesManualBackupTask() throws Exception {
        com.yigongbao.backup.job.PersistentJobService jobs =
                mock(com.yigongbao.backup.job.PersistentJobService.class);
        BackupJobCreationService creation = mock(BackupJobCreationService.class);
        com.yigongbao.backup.job.JobOrderEntity failed = new com.yigongbao.backup.job.JobOrderEntity();
        failed.setSourceOrderId(7L); failed.setStatus("FAILED");
        when(jobs.listOrders(20L)).thenReturn(List.of(failed));
        when(jobs.configJson(20L)).thenReturn(new ObjectMapper().writeValueAsString(
                new ManualBackupJobHandler.Payload(List.of(7L))));
        when(creation.autoDegradeOnRelationBroken()).thenReturn(true);
        AdminBackupController controller = new AdminBackupController(jobs, creation,
                mock(com.yigongbao.backup.job.JobExecutionLogQueryService.class),
                mock(com.yigongbao.backup.backup.ScheduledBackupService.class), new ObjectMapper());

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("ADMIN")).thenAnswer(invocation -> null);
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(9L);
            controller.retryFailed(20L);
        }

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(creation).createForOrders(eq("MANUAL_BACKUP"), anyString(), eq(9L), payload.capture(), eq(1));
        assertThat(new ObjectMapper().readValue(payload.getValue(), ManualBackupJobHandler.Payload.class)
                .resolvedAutoDegradeOnRelationBroken()).isTrue();
    }

    @Test
    void explicitDegradedEndpointCreatesSingleIndependentTask() throws Exception {
        com.yigongbao.backup.job.PersistentJobService jobs =
                mock(com.yigongbao.backup.job.PersistentJobService.class);
        BackupJobCreationService creation = mock(BackupJobCreationService.class);
        com.yigongbao.backup.job.JobOrderEntity eligible = new com.yigongbao.backup.job.JobOrderEntity();
        eligible.setSourceOrderId(7L); eligible.setFailureCode("RELATION_BROKEN");
        when(jobs.requireDegradedBackupEligible(20L, 7L)).thenReturn(eligible);
        com.yigongbao.backup.job.JobEntity created = new com.yigongbao.backup.job.JobEntity();
        created.setId(30L); created.setJobType("DEGRADED_BACKUP");
        when(creation.createForOrders(eq("DEGRADED_BACKUP"), anyString(), eq(9L), anyString(), eq(1)))
                .thenReturn(created);
        AdminBackupController controller = new AdminBackupController(jobs, creation,
                mock(com.yigongbao.backup.job.JobExecutionLogQueryService.class),
                mock(com.yigongbao.backup.backup.ScheduledBackupService.class), new ObjectMapper());

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("ADMIN")).thenAnswer(invocation -> null);
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(9L);
            assertThat(controller.degradedBackup(20L, 7L).data().getJobType())
                    .isEqualTo("DEGRADED_BACKUP");
        }

        verify(jobs).requireDegradedBackupEligible(20L, 7L);
        verify(creation).createForOrders(eq("DEGRADED_BACKUP"),
                eq(com.yigongbao.backup.job.JobIdempotency.key("DEGRADED_BACKUP", 9L, "20:7")), eq(9L),
                argThat(json -> json.contains("7") && json.contains("REAL")), eq(1));
    }

    @Test
    void retryAlsoUsesAtomicVolumeAwareJobCreation() {
        assertThat(java.util.Arrays.stream(AdminBackupController.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(BackupJobCreationService.class))).isTrue();
    }
    @Test
    void retryPayloadPreservesSimulationMode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String original = mapper.writeValueAsString(new ManualBackupJobHandler.Payload(
                List.of(1L, 2L), 2, "20261104", 7, "2026-10-28T00:00:00Z", "METADATA_SIMULATION", "D:/simulation"));

        ManualBackupJobHandler.Payload retry = AdminBackupController.retryPayload(
                mapper, original, List.of(2L), true);

        assertThat(retry.sourceOrderIds()).containsExactly(2L);
        assertThat(retry.executionMode()).isEqualTo("METADATA_SIMULATION");
        assertThat(retry.backupDate()).isEqualTo("20261104");
        assertThat(retry.simulationRoot()).isEqualTo("D:/simulation");
        assertThat(retry.resolvedAutoDegradeOnRelationBroken()).isTrue();
    }

    @Test
    void legacyPayloadWithoutAutomaticDegradeFieldRemainsDisabled() throws Exception {
        ManualBackupJobHandler.Payload payload = new ObjectMapper().readValue(
                "{\"sourceOrderIds\":[1],\"orderTotal\":1,\"backupDate\":\"20261112\",\"executionMode\":\"REAL\"}",
                ManualBackupJobHandler.Payload.class);

        assertThat(payload.resolvedAutoDegradeOnRelationBroken()).isFalse();
    }
}
