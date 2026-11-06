package com.yigongbao.backup.config;

import java.util.Locale;
import java.util.Set;

/** 对管理员配置进行结构化校验；未纳入本期约束的扩展键保持向后兼容。 */
public final class ConfigValueValidator {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "backup.schedule.enabled", "backup.schedule.interval-days", "backup.schedule.start-time",
            "backup.schedule.reconcile-enabled", "backup.schedule.reconcile-interval-hours",
            "backup.collection.days-before",
            "backup.retry.max-retries", "backup.retry.delays-seconds",
            "storage.hot-root", "storage.managed-roots", "storage.backup-reservation-percent",
            "storage.threshold.hot.percent", "storage.threshold.cold.percent",
            "cold.import-root", "cold.max-import-bytes",
            "restore.enabled", "restore.conflict-policy", "restore.retry-count",
            "cleanup.schedule.enabled", "cleanup.schedule.interval-hours", "cleanup.retention-days",
            "cleanup.batch-size", "supplement.allowed-extensions", "supplement.max-file-size-bytes",
            "account.sync-enabled", "account.sync-delay-minutes", "account.sync-page-size",
            "online.enabled", "online.base-url", "online.connect-timeout-seconds",
            "online.request-timeout-seconds", "job.dispatch-interval-ms",
            "job.lease-seconds", "job.recovery-interval-ms", "job.max-retry-count");
    private ConfigValueValidator() { }

    /** 校验配置值，避免非法阈值或空路径进入任务调度。 */
    public static void validate(String key, String value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("配置键为空");
        if (!ALLOWED_KEYS.contains(key)) throw new IllegalArgumentException("不允许创建或修改未登记的配置键: " + key);
        if (value == null) throw new IllegalArgumentException("配置值为空");
        String normalized = value.trim();
        if (key.startsWith("storage.threshold.") && key.endsWith(".percent")) {
            int percent = integer(key, normalized);
            if (percent < 1 || percent > 100) throw new IllegalArgumentException("容量阈值必须在1到100之间");
            return;
        }
        if (key.equals("storage.backup-reservation-percent")) {
            int percent = integer(key, normalized);
            if (percent < 100 || percent > 500) throw new IllegalArgumentException("备份工作空间预留比例必须在100到500之间");
            return;
        }
        if (key.equals("backup.schedule.interval-days")) {
            int days = integer(key, normalized);
            if (days < 1 || days > 365) throw new IllegalArgumentException("备份周期必须在1到365天之间");
            return;
        }
        if (key.equals("backup.collection.days-before")) {
            int days = integer(key, normalized);
            if (days < 0 || days > 3650) throw new IllegalArgumentException("备份订单天数必须在0到3650天之间");
            return;
        }
        if (key.equals("backup.schedule.start-time")) {
            try { java.time.LocalTime.parse(normalized); }
            catch (java.time.format.DateTimeParseException exception) {
                throw new IllegalArgumentException("自动备份开始时间必须为HH:mm格式", exception);
            }
            if (normalized.length() != 5) throw new IllegalArgumentException("自动备份开始时间必须为HH:mm格式");
            return;
        }
        if (key.equals("backup.retry.max-retries")) {
            int attempts = integer(key, normalized);
            if (attempts < 0 || attempts > 10) throw new IllegalArgumentException("订单备份最大重试次数必须在0到10之间");
            return;
        }
        if (key.equals("backup.retry.delays-seconds")) {
            String[] values = normalized.split(",");
            if (values.length == 0 || values.length > 9) throw new IllegalArgumentException("订单备份重试延迟配置非法");
            for (String item : values) if (integer(key, item.trim()) < 1) throw new IllegalArgumentException("重试延迟必须大于0秒");
            return;
        }
        if (key.equals("cleanup.retention-days")) {
            if (integer(key, normalized) < 1) throw new IllegalArgumentException("清理保留天数必须大于0");
            return;
        }
        if (key.equals("restore.retry-count") || key.equals("job.max-retry-count")) {
            int retries = integer(key, normalized);
            if (retries < 0 || retries > 10) throw new IllegalArgumentException("重试次数必须在0到10之间");
            return;
        }
        if (key.equals("job.lease-seconds")) {
            if (integer(key, normalized) < 10) throw new IllegalArgumentException("任务租约不能小于10秒");
            return;
        }
        if (key.equals("cleanup.batch-size") || key.endsWith("page-size")) {
            int size = integer(key, normalized);
            if (size < 1 || size > 500) throw new IllegalArgumentException(key + "必须在1到500之间");
            return;
        }
        if (key.equals("job.dispatch-interval-ms") && integer(key, normalized) < 200) {
            throw new IllegalArgumentException("任务派发间隔不能小于200毫秒");
        }
        if (key.equals("job.recovery-interval-ms") && integer(key, normalized) < 1000) {
            throw new IllegalArgumentException("租约回收间隔不能小于1000毫秒");
        }
        if (key.endsWith("-minutes") || key.endsWith("-hours") || key.endsWith("-seconds")
                || key.endsWith("-count") || key.endsWith("-size")) {
            if (integer(key, normalized) < 1) throw new IllegalArgumentException(key + "必须大于0");
            return;
        }
        if (key.endsWith("-enabled") || key.endsWith("-required") || key.endsWith("-protection")
                || key.endsWith("-deleted") || key.equals("backup.schedule.enabled")
                || key.equals("account.sync-enabled") || key.equals("cleanup.schedule.enabled")
                || key.equals("restore.enabled") || key.equals("online.enabled")) {
            if (!normalized.equalsIgnoreCase("true") && !normalized.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException(key + "必须是 true 或 false");
            }
            return;
        }
        if (key.equals("restore.conflict-policy")) {
            if (!normalized.equals("REJECT") && !normalized.equals("CONFIRM_OVERWRITE")
                    && !normalized.equals("FORCE_OVERWRITE")) throw new IllegalArgumentException("恢复冲突策略非法");
            return;
        }
        if (key.equals("online.base-url") && !normalized.isBlank()
                && !(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new IllegalArgumentException("线上系统地址必须是HTTP URL");
        }
        if (key.endsWith(".bytes") || key.equals("cold.max-import-bytes")) {
            if (longValue(key, normalized) < 0) throw new IllegalArgumentException("字节数不能为负数");
            return;
        }
        if (key.endsWith(".root") || key.endsWith(".path") || key.endsWith("-root")
                || key.equals("storage.managed-roots")) {
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
