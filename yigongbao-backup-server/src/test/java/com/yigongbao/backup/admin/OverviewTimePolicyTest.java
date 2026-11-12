package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class OverviewTimePolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 11, 17, 30);
    private final OverviewTimePolicy policy = new OverviewTimePolicy(new BusinessClock(
            Clock.fixed(now.atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"))));

    @Test void usesShanghaiNaturalDayBoundariesAndFreshness() {
        assertThat(policy.today()).isEqualTo(new OverviewTimePolicy.DateWindow(
                LocalDateTime.of(2026, 11, 11, 0, 0), LocalDateTime.of(2026, 11, 12, 0, 0)));
        assertThat(policy.sevenDays().from()).isEqualTo(LocalDateTime.of(2026, 11, 5, 0, 0));
        assertThat(policy.capacityFresh(now.minusMinutes(15), 5)).isTrue();
        assertThat(policy.capacityFresh(now.minusMinutes(16), 5)).isFalse();
    }

    @Test void exposesCalendarWindowsForWeekMonthAndYearTrends() {
        assertThat(policy.trendWindow(OverviewTrendPeriod.WEEK)).isEqualTo(new OverviewTimePolicy.DateWindow(
                LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 16, 0, 0)));
        assertThat(policy.trendWindow(OverviewTrendPeriod.MONTH)).isEqualTo(new OverviewTimePolicy.DateWindow(
                LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0)));
        assertThat(policy.trendWindow(OverviewTrendPeriod.YEAR)).isEqualTo(new OverviewTimePolicy.DateWindow(
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0)));
    }

    @Test void anchorsForcedSuccessToTheConfiguredMinuteOnTheFutureCycleDate() {
        var state = policy.backupSchedule(new OverviewTimePolicy.BackupScheduleInput(true,
                LocalDateTime.of(2026, 11, 1, 9, 0), "05:00", 3,
                LocalDateTime.of(2026, 11, 10, 12, 0), "SUCCESS"));
        assertThat(state.nextExpectedAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 5, 0));
        assertThat(state.missed()).isFalse();
    }

    @Test void returnsUnknownWhenTheEnablementFactIsMissing() {
        var state = policy.backupSchedule(new OverviewTimePolicy.BackupScheduleInput(true, null, "05:00", 1, null, null));
        assertThat(state.state()).isEqualTo("UNKNOWN");
    }

    @Test void suppressesMissingCheckpointUntilTheFirstScheduledSlot() {
        var before = new OverviewTimePolicy(new BusinessClock(Clock.fixed(
                LocalDateTime.of(2026,11,11,4,0).atZone(BusinessClock.ZONE).toInstant(),BusinessClock.ZONE)));
        var input = new OverviewTimePolicy.BackupScheduleInput(true,
                LocalDateTime.of(2026,11,10,9,0),"05:00",1,null,null);
        assertThat(before.backupSchedule(input).state()).isEqualTo("READY");
        assertThat(policy.backupSchedule(input).state()).isEqualTo("CHECKPOINT_MISSING");
        assertThat(policy.backupSchedule(input).nextExpectedAt()).isEqualTo(LocalDateTime.of(2026,11,12,5,0));
    }

    @Test void reportsFirstFailedCheckpointBeforeMissingCheckpoint() {
        var state = policy.backupSchedule(new OverviewTimePolicy.BackupScheduleInput(true,
                LocalDateTime.of(2026,11,10,9,0),"05:00",1,null,"FAILED"));
        assertThat(state.state()).isEqualTo("FAILED");
        assertThat(state.missed()).isFalse();
        assertThat(state.nextExpectedAt()).isEqualTo(LocalDateTime.of(2026,11,11,5,0));
    }
}
