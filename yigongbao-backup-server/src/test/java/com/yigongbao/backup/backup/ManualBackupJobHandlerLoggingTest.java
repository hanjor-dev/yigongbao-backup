package com.yigongbao.backup.backup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.PartialJobFailureException;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ManualBackupJobHandlerLoggingTest {
    @Test
    void reservationReleaseFailureDoesNotMaskTheBusinessFailure() {
        SpaceReservationService reservations = mock(SpaceReservationService.class);
        SpaceReservation reservation = new SpaceReservation("hot", 100L, "1");
        IllegalStateException businessFailure = new IllegalStateException("receipt failed");
        doThrow(new IllegalStateException("release failed")).when(reservations).release(reservation);

        ManualBackupJobHandler.releaseReservation(reservations, reservation, businessFailure, 9L, 101L, 100L);

        assertThat(businessFailure.getSuppressed()).hasSize(1);
        assertThat(businessFailure.getSuppressed()[0]).hasMessage("release failed");
    }

    @Test
    void logsBatchOrderFailureProgressAndSummary() throws Exception {
        OnlineManifestClient manifests = mock(OnlineManifestClient.class);
        when(manifests.fetch("101")).thenThrow(new IllegalStateException("remote unavailable"));
        ObjectProvider<OnlineManifestClient> manifestProvider = mock(ObjectProvider.class);
        when(manifestProvider.getIfAvailable()).thenReturn(manifests);
        ObjectProvider<CosObjectClient> cosProvider = mock(ObjectProvider.class);
        when(cosProvider.getIfAvailable()).thenReturn(mock(CosObjectClient.class));
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getInt("backup.retry.max-retries", 3, 0)).thenReturn(0);
        when(configs.get("backup.retry.delays-seconds", "60,300,900")).thenReturn("60,300,900");
        ManualBackupJobHandler handler = new ManualBackupJobHandler(manifestProvider, cosProvider,
                mock(ArtifactStore.class), new ObjectMapper(), mock(SpaceReservationService.class),
                mock(BackupEvidenceService.class), configs,
                new BackupExecutionModeProvider(new MockEnvironment()));
        JobEntity job = new JobEntity();
        job.setId(9L); job.setJobNo("job-9");
        job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(
                new ManualBackupJobHandler.Payload(List.of(101L))));
        JobExecutionContext context = mock(JobExecutionContext.class);
        Logger logger = (Logger) LoggerFactory.getLogger(ManualBackupJobHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> handler.execute(context, job)).isInstanceOf(PartialJobFailureException.class);
        } finally { logger.detachAppender(appender); }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs).contains("订单备份任务开始", "订单备份开始", "订单备份尝试失败", "订单备份终态失败", "订单备份任务汇总",
                "任务ID=9", "订单ID=101", "处理进度=1/1", "异常原因=remote unavailable");
        // 任务控制台需要能在不展开订单日志的前提下，看到订单进入、失败和最终汇总。
        verify(context).taskInfo(eq("BACKUP_ORDER_STARTED"), eq("订单进度"), contains("订单备份开始"), anyMap());
        verify(context).taskError(eq("BACKUP_ORDER_FAILED"), eq("订单进度"), contains("订单备份最终失败"), anyMap());
        verify(context).taskInfo(eq("BACKUP_TASK_SUMMARY"), eq("任务结束"), contains("订单备份任务处理完成"), anyMap());
    }
}
