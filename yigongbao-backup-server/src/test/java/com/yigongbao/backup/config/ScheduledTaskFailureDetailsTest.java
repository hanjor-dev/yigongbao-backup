package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskFailureDetailsTest {

    @Test
    void identifiesPersistentJobDispatchFromTheScheduledCallFrame() {
        IllegalStateException failure = failureAt(
                "com.yigongbao.backup.job.JobDispatchScheduler", "dispatch");

        ScheduledTaskFailureDetails details = ScheduledTaskFailureDetails.from(failure);

        assertThat(details.taskName()).isEqualTo("持久化任务派发");
        assertThat(details.taskType()).isEqualTo("JOB_DISPATCH");
        assertThat(details.executionMethod())
                .isEqualTo("com.yigongbao.backup.job.JobDispatchScheduler#dispatch");
    }

    @Test
    void identifiesScheduledTaskFromTheRootCauseWhenFailureIsWrapped() {
        IllegalStateException rootCause = failureAt(
                "com.yigongbao.backup.storage.volume.StorageVolumeHealthScheduler",
                "probeManagedVolumes");
        RuntimeException failure = new RuntimeException("wrapper", rootCause);

        ScheduledTaskFailureDetails details = ScheduledTaskFailureDetails.from(failure);

        assertThat(details.taskName()).isEqualTo("存储卷健康巡检");
        assertThat(details.taskType()).isEqualTo("STORAGE_VOLUME_HEALTH_PROBE");
    }

    @Test
    void keepsActionableFallbackFieldsWhenNoScheduledCallFrameExists() {
        RuntimeException failure = failureAt("com.yigongbao.backup.config.RuntimeConfigService", "getLong");

        ScheduledTaskFailureDetails details = ScheduledTaskFailureDetails.from(failure);

        assertThat(details.taskName()).isEqualTo("未识别后台调度任务");
        assertThat(details.taskType()).isEqualTo("UNKNOWN");
        assertThat(details.executionMethod()).isEqualTo("未知");
    }

    private static IllegalStateException failureAt(String className, String methodName) {
        IllegalStateException failure = new IllegalStateException("test failure");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(className, methodName, "Test.java", 23)
        });
        return failure;
    }
}
