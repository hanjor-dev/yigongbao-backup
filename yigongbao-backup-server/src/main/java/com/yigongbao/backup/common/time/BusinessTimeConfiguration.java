package com.yigongbao.backup.common.time;

import java.util.TimeZone;

/** 在 Spring 和任何业务 Bean 初始化前固定进程业务时区。 */
public final class BusinessTimeConfiguration {
    private BusinessTimeConfiguration() { }
    public static void initializeProcessTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone(BusinessClock.ZONE));
        System.setProperty("user.timezone", BusinessClock.ZONE.getId());
    }
}
