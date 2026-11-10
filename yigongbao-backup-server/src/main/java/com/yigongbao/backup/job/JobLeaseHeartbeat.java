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
    private final Thread executionThread;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private JobLeaseHeartbeat(PersistentJobCoordinator coordinator, long jobId, String owner,
                              long token, long leaseSeconds, Thread executionThread) {
        this.coordinator = coordinator;
        this.jobId = jobId;
        this.owner = owner;
        this.token = token;
        this.leaseSeconds = leaseSeconds;
        this.executionThread = executionThread;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "job-lease-heartbeat-" + jobId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动任务级独立续租；心跳间隔不超过租约的三分之一。 */
    static JobLeaseHeartbeat start(PersistentJobCoordinator coordinator, long jobId, String owner,
                                   long token, long leaseSeconds) {
        JobLeaseHeartbeat heartbeat = new JobLeaseHeartbeat(
                coordinator, jobId, owner, token, leaseSeconds, Thread.currentThread());
        long intervalMillis = Math.max(100L, Math.min(5_000L, leaseSeconds * 1000L / 3L));
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
            if (cause instanceof JobCancelledException cancelled) throw cancelled;
            throw new IllegalStateException("任务租约自动续期失败: " + safeMessage(cause), cause);
        }
    }

    JobCancelledException cancellation() {
        Throwable cause = failure.get();
        return cause instanceof JobCancelledException cancelled ? cancelled : null;
    }

    private void renewSafely() {
        if (failure.get() != null) return;
        try {
            coordinator.renewLease(jobId, owner, token, leaseSeconds);
            log.debug("任务租约自动续期成功: jobId={}, owner={}, fencingToken={}, leaseSeconds={}",
                    jobId, owner, token, leaseSeconds);
        } catch (JobCancelledException cancelled) {
            if (failure.compareAndSet(null, cancelled)) executionThread.interrupt();
            log.info("任务取消已由自动续租检测并中断执行线程: jobId={}, owner={}, token={}",
                    jobId, owner, token);
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
