package com.yigongbao.backup.job;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class JobLeaseRecoverySchedulerTest {
    @Test
    void recoversExpiredJobsAfterProcessCrashBeforeDispatching() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        ExpiredLeaseRecoveryScheduler scheduler = new ExpiredLeaseRecoveryScheduler(coordinator);

        scheduler.recover();

        verify(coordinator).recoverExpired();
    }
}
