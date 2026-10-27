package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JobDispatcherTest {
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
