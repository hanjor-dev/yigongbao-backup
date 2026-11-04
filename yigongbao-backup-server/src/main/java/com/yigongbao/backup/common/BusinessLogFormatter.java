package com.yigongbao.backup.common;

import java.util.Locale;
import java.util.Map;

/** 统一将容量、耗时、摘要和状态转换为便于人工排障的中文日志文本。 */
public final class BusinessLogFormatter {
    private static final Map<String, String> STATUS_NAMES = Map.ofEntries(
            Map.entry("CREATED", "已创建"), Map.entry("PENDING", "等待执行"),
            Map.entry("RUNNING", "执行中"), Map.entry("RETRYING_FAILED_ORDERS", "失败订单重试中"), Map.entry("SUCCEEDED", "已成功"),
            Map.entry("FAILED", "已失败"), Map.entry("PARTIAL", "部分失败"),
            Map.entry("CANCELLED", "已取消"), Map.entry("ACTIVE", "有效"),
            Map.entry("AVAILABLE", "可用"), Map.entry("RELEASED", "已释放"));

    private BusinessLogFormatter() { }

    public static String bytes(long value) {
        double amount = value;
        String unit = "字节";
        if (value >= 1024L * 1024L * 1024L) { amount = value / (1024d * 1024d * 1024d); unit = "GB"; }
        else if (value >= 1024L * 1024L) { amount = value / (1024d * 1024d); unit = "MB"; }
        else if (value >= 1024L) { amount = value / 1024d; unit = "KB"; }
        if ("字节".equals(unit)) return value + " 字节";
        return String.format(Locale.ROOT, "%.2f %s（%d 字节）", amount, unit, value);
    }

    public static String duration(long millis) {
        if (millis < 1000L) return millis + " 毫秒";
        return String.format(Locale.ROOT, "%.2f 秒（%d 毫秒）", millis / 1000d, millis);
    }

    public static String shortHash(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.substring(0, Math.min(12, value.length()));
    }

    public static String status(String value) {
        if (value == null || value.isBlank()) return "未知";
        return value + "（" + STATUS_NAMES.getOrDefault(value.toUpperCase(Locale.ROOT), "未知状态") + "）";
    }
}
