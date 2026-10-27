package com.yigongbao.backup.config;

import java.util.Locale;

/** 对管理员配置进行结构化校验；未纳入本期约束的扩展键保持向后兼容。 */
public final class ConfigValueValidator {
    private ConfigValueValidator() { }

    /** 校验配置值，避免非法阈值或空路径进入任务调度。 */
    public static void validate(String key, String value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("配置键为空");
        if (value == null) throw new IllegalArgumentException("配置值为空");
        String normalized = value.trim();
        if (key.startsWith("storage.threshold.") && key.endsWith(".percent")) {
            int percent = integer(key, normalized);
            if (percent < 1 || percent > 100) throw new IllegalArgumentException("容量阈值必须在1到100之间");
            return;
        }
        if (key.equals("backup.schedule.interval-minutes")) {
            if (integer(key, normalized) < 1) throw new IllegalArgumentException("备份周期必须大于0分钟");
            return;
        }
        if (key.equals("cleanup.retention-days")) {
            if (integer(key, normalized) < 1) throw new IllegalArgumentException("清理保留天数必须大于0");
            return;
        }
        if (key.endsWith(".bytes") || key.equals("cold.max-import-bytes")) {
            if (longValue(key, normalized) < 0) throw new IllegalArgumentException("字节数不能为负数");
            return;
        }
        if (key.endsWith(".root") || key.endsWith(".path")) {
            if (normalized.isBlank()) throw new IllegalArgumentException("存储路径不能为空");
            return;
        }
        if (key.endsWith(".mode")) {
            String mode = normalized.toUpperCase(Locale.ROOT);
            if (!mode.equals("DRY_RUN") && !mode.equals("EXECUTE") && !mode.equals("MANUAL")) {
                throw new IllegalArgumentException("模式值非法");
            }
        }
    }

    private static int integer(String key, String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + "必须是整数", ex); }
    }

    private static long longValue(String key, String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + "必须是整数", ex); }
    }
}
