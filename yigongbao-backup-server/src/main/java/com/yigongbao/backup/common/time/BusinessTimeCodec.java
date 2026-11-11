package com.yigongbao.backup.common.time;

import java.time.*;

/** MySQL DATETIME（上海本地时间）与绝对时间的唯一转换入口。 */
public final class BusinessTimeCodec {
    private BusinessTimeCodec() { }
    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(BusinessClock.ZONE).toInstant();
    }
    public static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BusinessClock.ZONE);
    }
    public static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(BusinessClock.ZONE).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
    }
}
