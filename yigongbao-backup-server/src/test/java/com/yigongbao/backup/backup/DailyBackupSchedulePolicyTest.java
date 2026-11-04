package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DailyBackupSchedulePolicyTest {
    @Test
    void runsOnlyInsideConfiguredStartMinute() {
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 3, 5, 0, 20),
                "05:00", 1, LocalDateTime.of(2026, 11, 2, 5, 0))).isTrue();
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 3, 8, 0),
                "05:00", 1, LocalDateTime.of(2026, 11, 2, 5, 0))).isFalse();
    }

    @Test
    void respectsIntervalAndPreventsDuplicateSameDayRun() {
        LocalDateTime lastSuccess = LocalDateTime.of(2026, 11, 2, 5, 0);
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 3, 5, 0),
                "05:00", 2, lastSuccess)).isFalse();
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 4, 5, 0),
                "05:00", 2, lastSuccess)).isTrue();
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 2, 5, 0, 40),
                "05:00", 1, lastSuccess)).isFalse();
    }

    @Test
    void runsWhenPollingCrossesScheduledTimeWithoutBackfillingOnFirstObservation() {
        LocalDateTime previousPoll = LocalDateTime.of(2026, 11, 3, 4, 59, 40);
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 3, 5, 0, 35),
                "05:00", 1, LocalDateTime.of(2026, 11, 2, 5, 0), previousPoll)).isTrue();
        assertThat(DailyBackupSchedulePolicy.isDue(LocalDateTime.of(2026, 11, 3, 8, 0),
                "05:00", 1, LocalDateTime.of(2026, 11, 2, 5, 0), null)).isFalse();
    }
}
