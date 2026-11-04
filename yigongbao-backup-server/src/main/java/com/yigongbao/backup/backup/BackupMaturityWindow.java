package com.yigongbao.backup.backup;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 按自然日计算订单进入自动备份范围的时间上界。 */
public final class BackupMaturityWindow {
    private BackupMaturityWindow() {
    }

    /**
     * 返回订单创建时间的排他上界。
     * 例如 11 月 4 日执行且配置 7 天时，10 月 28 日全天均应纳入，因此上界为 10 月 29 日零点。
     */
    public static LocalDateTime orderCreatedBefore(LocalDate executionDate, int daysBefore) {
        if (executionDate == null) throw new IllegalArgumentException("执行日期不能为空");
        if (daysBefore < 0) throw new IllegalArgumentException("备份天数不能小于0");
        return executionDate.minusDays(daysBefore).plusDays(1).atStartOfDay();
    }
}
