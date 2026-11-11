package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import org.springframework.stereotype.Component;
import java.time.*;

/** 概览日期窗口、计划槽位与新鲜度的纯策略。 */
@Component
public final class OverviewTimePolicy {
    private final BusinessClock clock;
    public OverviewTimePolicy(BusinessClock clock) { this.clock = clock; }

    public DateWindow today() {
        LocalDateTime from = clock.today().atStartOfDay();
        return new DateWindow(from, from.plusDays(1));
    }
    public DateWindow sevenDays() {
        LocalDateTime to = clock.today().plusDays(1).atStartOfDay();
        return new DateWindow(to.minusDays(7), to);
    }
    public boolean capacityFresh(LocalDateTime observedAt, int intervalMinutes) {
        if (observedAt == null) return false;
        long freshness = Math.max(Math.max(intervalMinutes, 1) * 3L, 15L);
        return !observedAt.isBefore(clock.now().minusMinutes(freshness));
    }
    public boolean accountSyncStale(AccountSyncInput input) {
        if (input == null || !input.enabled() || input.lastSuccessAt() == null) return false;
        long minutes = Math.max(Math.max(input.syncDelayMinutes(), 1) * 3L, 60L);
        return input.lastSuccessAt().isBefore(clock.now().minusMinutes(minutes));
    }
    public BackupScheduleState backupSchedule(BackupScheduleInput input) {
        if (input == null || !input.enabled()) return new BackupScheduleState("DISABLED", null, null, false, null);
        if (input.enabledAt() == null || input.startTime() == null) {
            return new BackupScheduleState("UNKNOWN", null, null, false, "自动备份启用时间或计划时间缺失");
        }
        LocalTime start;
        try { start = LocalTime.parse(input.startTime()); }
        catch (RuntimeException invalid) { return new BackupScheduleState("UNKNOWN", null, null, false, "自动备份计划时间无效"); }
        int days = Math.max(input.intervalDays(), 1);
        LocalDateTime first = input.enabledAt().toLocalDate().atTime(start);
        if (first.isBefore(input.enabledAt())) first = first.plusDays(1);
        LocalDateTime now = clock.now();
        if ("FAILED".equals(input.checkpointStatus())) {
            LocalDateTime next = input.lastSuccessAt() == null
                    ? first : input.lastSuccessAt().toLocalDate().plusDays(days).atTime(start);
            return new BackupScheduleState("FAILED", null, next, false, null);
        }
        if (input.lastSuccessAt() == null) {
            if (now.isBefore(first)) return new BackupScheduleState("READY", null, first, false, null);
            long elapsedDays = Math.max(0, Duration.between(first, now).toDays());
            LocalDateTime lastExpected = first.plusDays((elapsedDays / days) * days);
            if (lastExpected.isAfter(now)) lastExpected = lastExpected.minusDays(days);
            return new BackupScheduleState("CHECKPOINT_MISSING", lastExpected,
                    lastExpected.plusDays(days), true, null);
        }
        LocalDateTime next = input.lastSuccessAt().toLocalDate().plusDays(days).atTime(start);
        boolean missed = next.isBefore(now);
        String state = missed ? "MISSED" : "READY";
        return new BackupScheduleState(state, missed ? next : null, next, missed, null);
    }

    public record DateWindow(LocalDateTime from, LocalDateTime to) { }
    public record BackupScheduleInput(boolean enabled, LocalDateTime enabledAt, String startTime,
                                      int intervalDays, LocalDateTime lastSuccessAt, String checkpointStatus) { }
    public record BackupScheduleState(String state, LocalDateTime lastExpectedAt,
                                      LocalDateTime nextExpectedAt, boolean missed, String qualityMessage) { }
    public record AccountSyncInput(boolean enabled, LocalDateTime enabledAt,
                                   LocalDateTime lastSuccessAt, int syncDelayMinutes) { }
}
