package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobOrderEntity;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledBackupServiceTest {
    @Test
    void dynamicMaximumLimitsCandidatePageAndCreatedTaskPayload() throws Exception {
        Fixture fixture = fixture(3);
        when(fixture.candidates.search(eq(""), anyInt(), eq(""), any())).thenReturn(new OnlineCandidateClient.Page(
                candidates(1L, 2L, 3L, 4L, 5L), "next", true, "high"));

        fixture.service.runNowByAdministrator();

        verify(fixture.candidates).search(eq(""), eq(3), eq(""), any());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(fixture.jobCreation).createForOrders(eq("ORDER_BACKUP"), anyString(), eq(0L), payload.capture(), eq(3));
        ManualBackupJobHandler.Payload decoded = fixture.mapper.readValue(
                payload.getValue(), ManualBackupJobHandler.Payload.class);
        assertThat(decoded.sourceOrderIds()).containsExactly(1L, 2L, 3L);
        assertThat(decoded.orderTotal()).isEqualTo(3);
    }

    @Test
    void failedOrderCompensationUsesOnlyRemainingTaskCapacity() throws Exception {
        Fixture fixture = fixture(3);
        when(fixture.candidates.search(eq(""), anyInt(), eq(""), any())).thenReturn(new OnlineCandidateClient.Page(
                candidates(1L, 2L), "", false, "high"));
        when(fixture.jobOrders.selectList(any())).thenReturn(List.of(failed(3L), failed(4L)));

        fixture.service.runNowByAdministrator();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(fixture.jobCreation).createForOrders(eq("ORDER_BACKUP"), anyString(), eq(0L), payload.capture(), eq(3));
        ManualBackupJobHandler.Payload decoded = fixture.mapper.readValue(
                payload.getValue(), ManualBackupJobHandler.Payload.class);
        assertThat(decoded.sourceOrderIds()).containsExactly(1L, 2L, 3L);
    }

    private static Fixture fixture(int maximumOrders) {
        OnlineCandidateClient candidates = mock(OnlineCandidateClient.class);
        ObjectMapper mapper = new ObjectMapper();
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        BackupScheduleCheckpointService checkpoint = mock(BackupScheduleCheckpointService.class);
        JobOrderMapper jobOrders = mock(JobOrderMapper.class);
        BackupExecutionModeProvider executionModes = mock(BackupExecutionModeProvider.class);
        BackupJobCreationService jobCreation = mock(BackupJobCreationService.class);

        when(configs.getBoolean("online.enabled", false)).thenReturn(true);
        when(configs.getBoolean("backup.schedule.enabled", true)).thenReturn(true);
        when(configs.getLong("backup.schedule.interval-days", 1L, 1L)).thenReturn(1L);
        when(configs.get("backup.schedule.start-time", "05:00")).thenReturn("05:00");
        when(configs.getInt("backup.collection.days-before", 7, 0)).thenReturn(7);
        when(configs.getInt("backup.schedule.max-orders-per-run", 100, 1)).thenReturn(maximumOrders);
        when(snapshots.selectCount(any())).thenReturn(0L);
        when(jobOrders.selectList(any())).thenReturn(List.of());
        when(executionModes.mode()).thenReturn(BackupExecutionMode.REAL);
        JobEntity created = new JobEntity();
        created.setId(99L); created.setJobNo("job-99");
        when(jobCreation.createForOrders(eq("ORDER_BACKUP"), anyString(), eq(0L), anyString(), anyInt()))
                .thenReturn(created);

        ScheduledBackupService service = new ScheduledBackupService(candidates, mapper, snapshots, coordinator,
                configs, checkpoint, jobOrders, executionModes, jobCreation);
        return new Fixture(service, candidates, mapper, jobOrders, jobCreation);
    }

    private static List<OnlineCandidateClient.Candidate> candidates(Long... orderIds) {
        return java.util.Arrays.stream(orderIds)
                .map(orderId -> new OnlineCandidateClient.Candidate(orderId, orderId, 1,
                        OffsetDateTime.now(), OffsetDateTime.now(), "READY"))
                .toList();
    }

    private static JobOrderEntity failed(long orderId) {
        JobOrderEntity row = new JobOrderEntity();
        row.setSourceOrderId(orderId);
        row.setStatus("FAILED");
        return row;
    }

    private record Fixture(ScheduledBackupService service, OnlineCandidateClient candidates,
                           ObjectMapper mapper, JobOrderMapper jobOrders,
                           BackupJobCreationService jobCreation) { }
}
