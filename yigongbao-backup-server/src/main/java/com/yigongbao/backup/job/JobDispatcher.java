package com.yigongbao.backup.job;

import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.common.BusinessLogFormatter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/** 领取并执行单个到期任务；生产环境在每次领取时读取数据库租约和重试策略。 */
@Slf4j
public final class JobDispatcher {
    private final PersistentJobCoordinator coordinator;
    private final JobExecutorRegistry registry;
    private final JobEvidenceService evidence;
    private final String owner;
    private final long staticLeaseSeconds;
    private final int staticRetries;
    private final RuntimeConfigService configs;

    public JobDispatcher(PersistentJobCoordinator coordinator, JobExecutorRegistry registry,
                         String owner, Duration lease, int retries) {
        this(coordinator, registry, null, owner, lease, retries);
    }

    public JobDispatcher(PersistentJobCoordinator coordinator, JobExecutorRegistry registry,
                         JobEvidenceService evidence, String owner, Duration lease, int retries) {
        this(coordinator, registry, evidence, owner, lease, retries, null);
    }

    /** Spring生产装配使用的数据库动态策略构造器。 */
    public JobDispatcher(PersistentJobCoordinator coordinator, JobExecutorRegistry registry,
                         JobEvidenceService evidence, String owner, RuntimeConfigService configs) {
        this(coordinator, registry, evidence, owner, Duration.ofSeconds(60), 3, configs);
    }

    private JobDispatcher(PersistentJobCoordinator coordinator, JobExecutorRegistry registry,
                          JobEvidenceService evidence, String owner, Duration lease, int retries,
                          RuntimeConfigService configs) {
        this.coordinator = coordinator;
        this.registry = registry;
        this.evidence = evidence;
        this.owner = owner;
        this.staticLeaseSeconds = lease.toSeconds();
        this.staticRetries = retries;
        this.configs = configs;
    }

    public boolean dispatchOne() {
        long leaseSeconds = configs == null ? staticLeaseSeconds
                : configs.getLong("job.lease-seconds", 60L, 10L);
        JobEntity job = coordinator.claimNext(owner, leaseSeconds);
        if (job == null) return false;
        int retries = configs == null ? staticRetries : "RESTORE".equals(job.getJobType())
                ? configs.getInt("restore.retry-count", 3, 0)
                : configs.getInt("job.max-retry-count", 3, 0);
        long token = job.getFencingToken();
        long startedAt = System.nanoTime();
        log.info("任务已领取｜任务ID={}｜任务编号={}｜任务类型={}｜执行器={}｜隔离令牌={}｜租约={}秒｜已重试次数={}",
                job.getId(), job.getJobNo(), jobType(job.getJobType()), owner, token, leaseSeconds,
                value(job.getRetryCount()));
        JobExecutionContext context = new JobExecutionContext(
                coordinator, evidence, job.getId(), owner, token, leaseSeconds);
        JobLeaseHeartbeat heartbeat = JobLeaseHeartbeat.start(
                coordinator, job.getId(), owner, token, leaseSeconds);
        try {
            log.info("任务执行开始｜任务ID={}｜任务编号={}｜任务类型={}｜最大任务级重试次数={}",
                    job.getId(), job.getJobNo(), jobType(job.getJobType()), retries);
            context.safePoint("STARTED", 0);
            registry.required(job.getJobType()).execute(context, job);
            heartbeat.assertHealthy();
            // 先停止后台心跳，再写入终态，避免心跳与成功状态更新发生并发竞争。
            heartbeat.close();
            coordinator.succeed(job.getId(), owner, token);
            log.info("任务执行成功｜任务ID={}｜任务编号={}｜任务类型={}｜最终状态={}｜执行耗时={}",
                    job.getId(), job.getJobNo(), jobType(job.getJobType()),
                    BusinessLogFormatter.status("SUCCEEDED"),
                    BusinessLogFormatter.duration(elapsedMillis(startedAt)));
        } catch (JobCancelledException cancelled) {
            log.warn("任务已在安全点取消: jobId={}, jobNo={}, currentStep={}, durationMs={}, reason={}",
                    job.getId(), job.getJobNo(), job.getCurrentStep(), elapsedMillis(startedAt), safe(cancelled));
            return true;
        } catch (PartialJobFailureException partial) {
            if (partial.allItemsFailed()) {
                coordinator.failOrRetry(job.getId(), owner, token, 0, partial);
                log.error("任务全部订单失败: jobId={}, jobNo={}, jobType={}, durationMs={}, error={}",
                        job.getId(), job.getJobNo(), job.getJobType(), elapsedMillis(startedAt), safe(partial));
            } else {
                coordinator.partial(job.getId(), owner, token, partial.getMessage());
                log.warn("任务部分失败: jobId={}, jobNo={}, jobType={}, durationMs={}, error={}",
                        job.getId(), job.getJobNo(), job.getJobType(), elapsedMillis(startedAt), safe(partial));
            }
        } catch (Throwable failure) {
            context.failure(failure);
            coordinator.failOrRetry(job.getId(), owner, token, retries, failure);
            int nextRetry = value(job.getRetryCount()) + 1;
            boolean willRetry = nextRetry <= retries && !Boolean.TRUE.equals(job.getCancelRequested());
            if (willRetry) {
                log.warn("任务执行失败，已进入任务级重试｜任务ID={}｜任务编号={}｜任务类型={}｜重试进度={}/{}｜执行耗时={}｜异常类型={}｜异常原因={}",
                        job.getId(), job.getJobNo(), jobType(job.getJobType()), nextRetry, retries,
                        BusinessLogFormatter.duration(elapsedMillis(startedAt)),
                        failure.getClass().getSimpleName(), safe(failure), safeThrowable(failure));
            } else {
                log.error("任务执行终态失败｜任务ID={}｜任务编号={}｜任务类型={}｜最终状态={}｜累计尝试次数={}｜执行耗时={}｜异常类型={}｜异常原因={}",
                        job.getId(), job.getJobNo(), jobType(job.getJobType()),
                        BusinessLogFormatter.status("FAILED"), nextRetry,
                        BusinessLogFormatter.duration(elapsedMillis(startedAt)),
                        failure.getClass().getSimpleName(), safe(failure), safeThrowable(failure));
            }
        } finally {
            heartbeat.close();
            log.debug("任务执行器释放完成: jobId={}, owner={}, fencingToken={}", job.getId(), owner, token);
        }
        return true;
    }

    private static int value(Integer number) { return number == null ? 0 : number; }
    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }

    /** 日志仅保留可诊断文本，不输出远程 URL 或认证参数。 */
    private static String safe(Throwable error) {
        String message = error == null || error.getMessage() == null ? "无错误详情" : error.getMessage();
        return message.replaceAll("(?i)https?://\\S+", "[URL_REDACTED]")
                .replaceAll("(?i)(signature|secret|token|credential|authorization)\\s*[=:]\\s*[^,;]+", "$1=[REDACTED]");
    }

    private static Throwable safeThrowable(Throwable error) {
        RuntimeException sanitized = new RuntimeException(error.getClass().getSimpleName() + ": " + safe(error));
        sanitized.setStackTrace(error.getStackTrace());
        return sanitized;
    }

    private static String jobType(String type) {
        return switch (type == null ? "" : type) {
            case "ORDER_BACKUP", "MANUAL_BACKUP" -> type + "（订单备份）";
            case "RESTORE" -> type + "（线上恢复）";
            case "COLD_WRITE" -> type + "（写入冷备）";
            case "COLD_IMPORT" -> type + "（冷备导入）";
            case "CLEANUP_EXECUTE" -> type + "（云端清理）";
            default -> type + "（其他任务）";
        };
    }
}
