package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobExecutionContextLeaseTest {

    @Test
    void writesOrderEvidenceInsideLockedLeaseTransaction() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        JobEvidenceService evidence = mock(JobEvidenceService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return null;
        }).when(coordinator).withinActiveLease(any(Long.class), any(String.class), any(Long.class), any(Runnable.class));
        JobExecutionContext context = new JobExecutionContext(coordinator, evidence,
                9L, "worker-a", 17L, 60L);

        context.orderStarted(101L, 201L);

        verify(coordinator).withinActiveLease(any(Long.class), any(String.class), any(Long.class), any(Runnable.class));
        verify(evidence).order(9L, 101L, 201L, "RUNNING", null);
    }
}
