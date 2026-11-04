package com.yigongbao.backup.job;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在耗时处理与业务安全点之间独立续租，避免大文件下载、压缩等长操作被误判为执行器失联。
 */
@Slf4j
final class JobLeaseHeartbeat implements AutoCloseable {
    private final PersistentJobCoordinator coordinator;
    private final long jobId;
    private final String owner;
    private final long token;
    private final long leaseSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private JobLeaseHeartbeat(PersistentJobCoordinator coordinator, long jobId, String owner,
                              long token, long leaseSeconds) {
        this.coordinator = coordinator;
        this.jobId = jobId;
        this.owner = owner;
        this.token = token;
        this.leaseSeconds = leaseSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "job-lease-heartbeat-" + jobId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动任务级独立续租；心跳间隔不超过租约的三分之一。 */
    static JobLeaseHeartbeat start(PersistentJobCoordinator coordinator, long jobId, String owner,
                                   long token, long leaseSeconds) {
        JobLeaseHeartbeat heartbeat = new JobLeaseHeartbeat(coordinator, jobId, owner, token, leaseSeconds);
        long intervalMillis = Math.max(100L, Math.min(30_000L, leaseSeconds * 1000L / 3L));
        log.debug("任务租约自动续期已启动: jobId={}, owner={}, fencingToken={}, leaseSeconds={}, intervalMillis={}",
                jobId, owner, token, leaseSeconds, intervalMillis);
        heartbeat.scheduler.scheduleWithFixedDelay(
                heartbeat::renewSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return heartbeat;
    }

    /** 在任务提交最终状态前暴露续租故障，避免把失去租约的执行误记为成功。 */
    void assertHealthy() {
        Throwable cause = failure.get();
        if (cause != null) {
            throw new IllegalStateException("任务租约自动续期失败: " + safeMessage(cause), cause);
        }
    }

    private void renewSafely() {
        if (failure.get() != null) return;
        try {
            coordinator.renewLease(jobId, owner, token, leaseSeconds);
            log.debug("任务租约自动续期成功: jobId={}, owner={}, fencingToken={}, leaseSeconds={}",
                    jobId, owner, token, leaseSeconds);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            log.error("任务租约自动续期失败: jobId={}, owner={}, token={}", jobId, owner, token, error);
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        log.debug("任务租约自动续期已停止: jobId={}, owner={}, fencingToken={}", jobId, owner, token);
    }
}
