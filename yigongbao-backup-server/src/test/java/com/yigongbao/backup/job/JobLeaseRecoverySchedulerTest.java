package com.yigongbao.backup.job;

import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.*;

class JobLeaseRecoverySchedulerTest {

    @Test
    void productionConstructorMustBeExplicitSpringInjectionPoint() throws Exception {
        var constructor = ExpiredLeaseRecoveryScheduler.class.getConstructor(
                PersistentJobCoordinator.class, RuntimeConfigService.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }
    @Test
    void recoversExpiredJobsAfterProcessCrashBeforeDispatching() {
        PersistentJobCoordinator coordinator = mock(PersistentJobCoordinator.class);
        ExpiredLeaseRecoveryScheduler scheduler = new ExpiredLeaseRecoveryScheduler(coordinator);

        scheduler.recover();

        verify(coordinator).recoverExpired(3);
    }
}
