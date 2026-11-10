package com.yigongbao.backup.job;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JobDispatcherTest {
    @Test
    void logsClaimStartAndTerminalSuccessWithDuration() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(5L, "ORDER_BACKUP", 8L, false);
        claimed.setJobNo("job-log-5");
        when(coordinator.claimNext("worker-a", 60)).thenReturn(claimed);
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("ORDER_BACKUP", (context, job) -> { })),
                "worker-a", Duration.ofSeconds(60), 3);
        Logger logger = (Logger) LoggerFactory.getLogger(JobDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try { dispatcher.dispatchOne(); } finally { logger.detachAppender(appender); }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs).contains("任务已领取", "任务执行开始", "任务执行成功", "任务ID=5", "执行耗时=");
    }
    @Test
    void keepsLeaseAliveWhileHandlerIsBlockedInLongRunningIo() throws Exception {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(6L, "MANUAL_BACKUP", 2L, false);
        when(coordinator.claimNext("worker-a", 1)).thenReturn(claimed);
        CountDownLatch renewed = new CountDownLatch(1);
        doAnswer(invocation -> { renewed.countDown(); return null; }).when(coordinator)
                .renewLease(6L, "worker-a", 2L, 1L);
        JobHandler handler = (context, job) -> {
            if (!renewed.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("租约未自动续期");
        };
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("MANUAL_BACKUP", handler)),
                "worker-a", Duration.ofSeconds(1), 3);

        dispatcher.dispatchOne();

        verify(coordinator, atLeastOnce()).renewLease(6L, "worker-a", 2L, 1L);
        verify(coordinator).succeed(6L, "worker-a", 2L);
    }

    @Test
    void interruptsLongRunningHandlerWhenLeaseHeartbeatObservesCancellation() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(11L, "RESTORE", 12L, false);
        when(coordinator.claimNext("worker-a", 1)).thenReturn(claimed);
        doThrow(new JobCancelledException("管理员已取消任务")).when(coordinator)
                .renewLease(11L, "worker-a", 12L, 1L);
        JobHandler handler = (context, job) -> {
            try { Thread.sleep(2_000L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PartialJobFailureException("中断后的订单汇总失败");
            }
        };
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("RESTORE", handler)),
                "worker-a", Duration.ofSeconds(1), 3);

        long startedAt = System.nanoTime();
        dispatcher.dispatchOne();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        boolean interruptLeakedToDispatcherThread = Thread.interrupted();

        assertThat(elapsedMillis).isLessThan(1_500L);
        assertThat(interruptLeakedToDispatcherThread).isFalse();
        verify(coordinator, never()).succeed(11L, "worker-a", 12L);
        verify(coordinator, never()).partial(eq(11L), anyString(), anyLong(), anyString());
        verify(coordinator, never()).failOrRetry(eq(11L), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void dispatchesRegisteredHandlerAndCompletesWithCurrentFencingToken() throws Exception {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(7L, "RESTORE", 4L, false);
        when(coordinator.claimNext("worker-a", 60)).thenReturn(claimed);
        JobHandler handler = mock(JobHandler.class);
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("RESTORE", handler)), "worker-a", Duration.ofSeconds(60), 3);

        dispatcher.dispatchOne();

        verify(handler).execute(any(JobExecutionContext.class), same(claimed));
        verify(coordinator).succeed(7L, "worker-a", 4L);
    }

    @Test
    void retriesFailureAndHonorsCancellationAtSafePoint() throws Exception {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(8L, "RESTORE", 9L, false);
        when(coordinator.claimNext("worker-a", 60)).thenReturn(claimed);
        doThrow(new IllegalStateException("network")).when(coordinator)
                .safePoint(8L, "worker-a", 9L, "STARTED", 0L);
        JobHandler handler = (context, job) -> context.safePoint("STARTED", 0);
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("RESTORE", handler)), "worker-a", Duration.ofSeconds(60), 3);

        dispatcher.dispatchOne();

        verify(coordinator).failOrRetry(eq(8L), eq("worker-a"), eq(9L), eq(3), any(Throwable.class));
    }

    @Test
    void recordsPartialFailureWithoutRetryingWholeBackupTask() throws Exception {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEntity claimed = job(10L, "MANUAL_BACKUP", 3L, false);
        when(coordinator.claimNext("worker-a", 60)).thenReturn(claimed);
        JobHandler handler = (context, job) -> { throw new PartialJobFailureException("3个订单失败"); };
        JobDispatcher dispatcher = new JobDispatcher(coordinator,
                new JobExecutorRegistry(Map.of("MANUAL_BACKUP", handler)), "worker-a", Duration.ofSeconds(60), 3);

        dispatcher.dispatchOne();

        verify(coordinator).partial(10L, "worker-a", 3L, "3个订单失败");
        verify(coordinator, never()).failOrRetry(anyLong(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void oldFencingTokenIsRejectedByExecutionContext() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        doThrow(new IllegalStateException("租约或 fencing token 无效")).when(coordinator)
                .safePoint(9L, "worker-a", 1L, "UPLOAD", 2L);
        JobExecutionContext context = new JobExecutionContext(coordinator, 9L, "worker-a", 1L, 60);

        assertThatThrownBy(() -> context.safePoint("UPLOAD", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fencing token");
    }

    private static JobEntity job(long id, String type, long token, boolean cancelled) {
        JobEntity job = new JobEntity(); job.setId(id); job.setJobType(type); job.setFencingToken(token);
        job.setCancelRequested(cancelled); job.setRetryCount(0); return job;
    }
}
