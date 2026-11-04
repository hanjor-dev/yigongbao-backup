package com.yigongbao.backup.backup;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** 按服务器本地时间判断自动备份是否恰好到达执行分钟，错过后不补跑。 */
public final class DailyBackupSchedulePolicy {
    private DailyBackupSchedulePolicy() { }

    public static boolean isDue(LocalDateTime now, String startTime, long intervalDays,
                                LocalDateTime lastSuccessAt) {
        return isDue(now, startTime, intervalDays, lastSuccessAt, null);
    }

    /** 首次观察不补跑；后续轮询只要跨过计划时间即执行，避免 fixed-delay 漂移漏跑。 */
    public static boolean isDue(LocalDateTime now, String startTime, long intervalDays,
                                LocalDateTime lastSuccessAt, LocalDateTime previousPollAt) {
        if (now == null || intervalDays < 1) return false;
        LocalTime configured;
        try {
            configured = LocalTime.parse(startTime);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("自动备份开始时间必须为HH:mm格式", exception);
        }
        LocalDateTime scheduled = now.toLocalDate().atTime(configured);
        boolean reached = previousPollAt == null
                ? now.getHour() == configured.getHour() && now.getMinute() == configured.getMinute()
                : previousPollAt.isBefore(scheduled) && !now.isBefore(scheduled);
        if (!reached) return false;
        return lastSuccessAt == null
                || !lastSuccessAt.toLocalDate().plusDays(intervalDays).isAfter(scheduled.toLocalDate());
    }
}
