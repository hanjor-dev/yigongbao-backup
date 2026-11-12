package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigurationTest {

    @Test
    void scheduledMaintenanceUsesAnIndependentWorkerPool() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration().taskScheduler();

        assertThat(scheduler.getPoolSize()).isGreaterThanOrEqualTo(4);
        assertThat(scheduler.getThreadNamePrefix()).startsWith("backup-scheduler-");
        assertThat(scheduler.isRemoveOnCancelPolicy()).isTrue();
    }
}
