package com.yigongbao.backup.config;

import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Map;

/** 从调度异常调用栈中恢复具体的定时任务身份，避免统一异常日志丢失业务入口。 */
record ScheduledTaskFailureDetails(String taskName, String taskType, String executionMethod) {
    private static final String APPLICATION_PACKAGE = "com.yigongbao.backup.";
    private static final ScheduledTaskFailureDetails UNKNOWN = new ScheduledTaskFailureDetails(
            "未识别后台调度任务", "UNKNOWN", "未知");
    private static final Map<String, TaskIdentity> IDENTITIES = Map.ofEntries(
            identity("com.yigongbao.backup.job.JobDispatchScheduler#dispatch",
                    "持久化任务派发", "JOB_DISPATCH"),
            identity("com.yigongbao.backup.job.ExpiredLeaseRecoveryScheduler#recover",
                    "过期任务租约回收", "EXPIRED_JOB_LEASE_RECOVERY"),
            identity("com.yigongbao.backup.backup.ScheduledBackupService#runScheduled",
                    "订单自动备份扫描", "SCHEDULED_ORDER_BACKUP_SCAN"),
            identity("com.yigongbao.backup.backup.ScheduledBackupService#reconcileCandidates",
                    "订单备份漏单对账", "BACKUP_CANDIDATE_RECONCILIATION"),
            identity("com.yigongbao.backup.cleanup.AutomaticCleanupScheduler#schedule",
                    "自动清理任务创建", "AUTOMATIC_CLEANUP_SCHEDULE"),
            identity("com.yigongbao.backup.account.AccountSyncScheduler#sync",
                    "账号同步", "ACCOUNT_SYNC"),
            identity("com.yigongbao.backup.storage.volume.StorageVolumeHealthScheduler#probeManagedVolumes",
                    "存储卷健康巡检", "STORAGE_VOLUME_HEALTH_PROBE"),
            identity("com.yigongbao.backup.storage.reservation.SpaceReservationLeaseScheduler#renewActiveReservations",
                    "空间预留租约续期", "SPACE_RESERVATION_LEASE_RENEWAL"),
            identity("com.yigongbao.backup.backup.SupersededBackupStageCleanupService#scheduledCleanup",
                    "过期备份暂存清理", "SUPERSEDED_BACKUP_STAGE_CLEANUP"),
            identity("com.yigongbao.backup.download.DownloadTicketService#purgeExpired",
                    "过期下载凭证清理", "EXPIRED_DOWNLOAD_TICKET_CLEANUP"),
            identity("com.yigongbao.backup.order.SupplementStagingService#cleanupExpired",
                    "过期补传暂存清理", "EXPIRED_SUPPLEMENT_STAGING_CLEANUP"),
            identity("com.yigongbao.backup.account.LoginThrottleService#cleanupExpired",
                    "登录限流记录清理", "EXPIRED_LOGIN_THROTTLE_CLEANUP")
    );

    static ScheduledTaskFailureDetails from(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String method = frame.getClassName() + "#" + frame.getMethodName();
                TaskIdentity identity = IDENTITIES.get(method);
                if (identity != null) {
                    return new ScheduledTaskFailureDetails(identity.name(), identity.type(), method);
                }
                if (isScheduledMethod(frame)) {
                    return new ScheduledTaskFailureDetails(
                            frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                                    + "#" + frame.getMethodName(),
                            "SCHEDULED_TASK",
                            method);
                }
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return UNKNOWN;
    }

    private static boolean isScheduledMethod(StackTraceElement frame) {
        if (!frame.getClassName().startsWith(APPLICATION_PACKAGE)) return false;
        try {
            Class<?> type = Class.forName(frame.getClassName(), false,
                    ScheduledTaskFailureDetails.class.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(frame.getMethodName())
                        && method.isAnnotationPresent(Scheduled.class)) return true;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // 未知类仍由兜底信息记录原始异常，不影响异常处理线程。
        }
        return false;
    }

    private static Map.Entry<String, TaskIdentity> identity(String method, String name, String type) {
        return Map.entry(method, new TaskIdentity(name, type));
    }

    private record TaskIdentity(String name, String type) {}
}
