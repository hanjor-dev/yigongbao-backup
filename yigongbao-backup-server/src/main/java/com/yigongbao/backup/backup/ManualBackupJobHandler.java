package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.common.BusinessLogFormatter;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.PartialJobFailureException;
import com.yigongbao.backup.remote.RemoteCallException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 执行自动订单热备任务；任一文件失败时当前订单整体失败。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManualBackupJobHandler implements JobHandler {
    private final ObjectProvider<OnlineManifestClient> manifests;
    private final ObjectProvider<CosObjectClient> cosClients;
    private final ArtifactStore store;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;
    private final BackupEvidenceService backupEvidence;
    private final RuntimeConfigService configs;
    private final BackupExecutionModeProvider executionModes;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        long taskStartedAt = System.nanoTime();
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        BackupExecutionMode executionMode = payload.resolvedExecutionMode();
        OnlineManifestClient manifestsClient = manifests.getIfAvailable();
        CosObjectClient cos = executionMode == BackupExecutionMode.REAL ? cosClients.getIfAvailable() : null;
        if (manifestsClient == null || (executionMode == BackupExecutionMode.REAL && cos == null)) {
            throw new IllegalStateException("线上热备客户端未配置");
        }
        long done = 0;
        java.util.concurrent.atomic.AtomicLong expectedFiles = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong expectedBytes = new java.util.concurrent.atomic.AtomicLong();
        java.util.Map<Long, Throwable> retryQueue = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Throwable> terminalFailures = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Integer> attempts = new java.util.HashMap<>();
        context.initializeOrders(payload.sourceOrderIds());
        int maxRetries = configs.getInt("backup.retry.max-retries", 3, 0);
        OrderBackupRetryPolicy retryPolicy = new OrderBackupRetryPolicy(maxRetries,
                configs.get("backup.retry.delays-seconds", "60,300,900"));
        log.info("订单备份任务开始｜任务ID={}｜任务编号={}｜执行模式={}｜订单总数={}｜单订单最大重试次数={}",
                job.getId(), job.getJobNo(), executionMode, payload.sourceOrderIds().size(), maxRetries);
        int orderIndex = 0;
        for (Long orderId : payload.sourceOrderIds()) {
            orderIndex++;
            long orderStartedAt = System.nanoTime();
            context.safePoint("BACKUP_ORDER_" + orderId, done);
            log.info("订单备份开始｜任务ID={}｜订单ID={}｜订单进度={}/{}｜已处理订单数={}",
                    job.getId(), orderId, orderIndex, payload.sourceOrderIds().size(), done);
            try {
                SimulationArtifactResult simulated = executeAttempt(context, job, orderId, payload, executionMode,
                        manifestsClient, cos, attempts, maxRetries, done);
                accumulate(simulated, expectedFiles, expectedBytes);
                log.info("订单备份成功｜任务ID={}｜订单ID={}｜实际尝试次数={}｜执行耗时={}",
                        job.getId(), orderId, attempts.get(orderId),
                        BusinessLogFormatter.duration(elapsedMillis(orderStartedAt)));
            } catch (Exception failure) {
                if (retryable(failure) && maxRetries > 0) {
                    retryQueue.put(orderId, failure);
                    context.orderQueuedForRetry(orderId, failure);
                    log.warn("订单首轮备份失败，已移至任务末尾重试｜任务ID={}｜订单ID={}｜当前失败队列长度={}｜异常类型={}｜异常原因={}",
                            job.getId(), orderId, retryQueue.size(), failure.getClass().getSimpleName(), safe(failure));
                } else {
                    terminalFailures.put(orderId, failure);
                    context.orderFailed(executionMode == BackupExecutionMode.METADATA_SIMULATION ? "SIMULATION_FAILED" : "FAILED", failure);
                    log.error("订单首轮备份发生不可重试错误，继续处理后续订单｜任务ID={}｜订单ID={}｜异常类型={}｜异常原因={}",
                            job.getId(), orderId, failure.getClass().getSimpleName(), safe(failure));
                }
            }
            done++;
            context.safePoint("BACKUP_ORDER_COMPLETED", done);
            log.info("订单备份任务进度｜任务ID={}｜最近订单ID={}｜处理进度={}/{}｜完成比例={}％｜成功订单数={}｜失败订单数={}",
                    job.getId(), orderId, done, payload.sourceOrderIds().size(),
                    percent(done, payload.sourceOrderIds().size()), done - retryQueue.size() - terminalFailures.size(),
                    retryQueue.size() + terminalFailures.size());
        }

        if (!retryQueue.isEmpty() && retryPolicy.maxRetries() > 0) {
            context.failedOrderRetryPhaseStarted(retryQueue.size());
            log.warn("订单首轮处理完成，进入失败订单集中重试阶段｜任务ID={}｜待重试订单数={}｜最大重试轮数={}",
                    job.getId(), retryQueue.size(), retryPolicy.maxRetries());
            for (int retryRound = 1; retryRound <= retryPolicy.maxRetries() && !retryQueue.isEmpty(); retryRound++) {
                long delay = retryPolicy.delaySeconds(retryRound);
                log.warn("失败订单重试轮次等待｜任务ID={}｜重试轮次={}/{}｜待重试订单数={}｜等待时间={}秒",
                        job.getId(), retryRound, retryPolicy.maxRetries(), retryQueue.size(), delay);
                waitForRetry(context, null, delay);
                java.util.Map<Long, Throwable> currentRound = new java.util.LinkedHashMap<>(retryQueue);
                retryQueue.clear();
                int retryIndex = 0;
                for (Long orderId : currentRound.keySet()) {
                    retryIndex++;
                    context.safePoint("RETRY_BACKUP_ORDER_" + orderId, retryIndex);
                    try {
                        SimulationArtifactResult simulated = executeAttempt(context, job, orderId, payload, executionMode,
                                manifestsClient, cos, attempts, maxRetries, payload.sourceOrderIds().size());
                        accumulate(simulated, expectedFiles, expectedBytes);
                        log.info("失败订单重试成功｜任务ID={}｜订单ID={}｜重试轮次={}/{}｜累计尝试次数={}",
                                job.getId(), orderId, retryRound, retryPolicy.maxRetries(), attempts.get(orderId));
                    } catch (Exception failure) {
                        retryQueue.put(orderId, failure);
                        if (retryRound < retryPolicy.maxRetries()) context.orderQueuedForRetry(orderId, failure);
                        log.warn("失败订单重试仍未成功｜任务ID={}｜订单ID={}｜重试轮次={}/{}｜异常类型={}｜异常原因={}",
                                job.getId(), orderId, retryRound, retryPolicy.maxRetries(),
                                failure.getClass().getSimpleName(), safe(failure));
                    }
                }
            }
        }

        terminalFailures.putAll(retryQueue);
        java.util.List<String> failures = new java.util.ArrayList<>();
        terminalFailures.forEach((orderId, failure) -> {
            context.orderStarted(orderId, null);
            context.orderFailed(executionMode == BackupExecutionMode.METADATA_SIMULATION ? "SIMULATION_FAILED" : "FAILED", failure);
            failures.add(orderId + ": " + safe(failure));
            log.error("订单备份终态失败｜任务ID={}｜订单ID={}｜累计尝试次数={}｜异常类型={}｜异常原因={}",
                    job.getId(), orderId, attempts.getOrDefault(orderId, 1),
                    failure.getClass().getSimpleName(), safe(failure), safeThrowable(failure));
        });
        context.result(mapper.writeValueAsString(java.util.Map.of(
                "executionMode", executionMode.name(),
                "orderTotal", payload.sourceOrderIds().size(),
                "orderSucceeded", payload.sourceOrderIds().size() - failures.size(),
                "orderFailed", failures.size(), "manifestSucceeded", payload.sourceOrderIds().size() - failures.size(),
                "manifestFailed", failures.size(), "expectedFileTotal", expectedFiles.get(),
                "expectedBytes", expectedBytes.get(), "failures", failures)));
        log.info("订单备份任务汇总｜任务ID={}｜任务编号={}｜处理进度={}/{}｜成功订单数={}｜失败订单数={}｜总执行耗时={}",
                job.getId(), job.getJobNo(), done, payload.sourceOrderIds().size(),
                payload.sourceOrderIds().size() - failures.size(), failures.size(),
                BusinessLogFormatter.duration(elapsedMillis(taskStartedAt)));
        if (!failures.isEmpty()) {
            throw new PartialJobFailureException("部分订单备份失败: " + String.join("; ", failures));
        }
    }

    private SimulationArtifactResult backupOnce(JobExecutionContext context, JobEntity job, Long orderId, String backupDate,
                            String simulationRoot, BackupExecutionMode executionMode,
                            OnlineManifestClient manifestsClient, CosObjectClient cos) {
        log.info("读取线上订单清单开始｜任务ID={}｜订单ID={}", job.getId(), orderId);
        BackupManifest manifest = manifestsClient.fetch(String.valueOf(orderId));
        long sourceBytes = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum();
        log.info("读取线上订单清单完成｜任务ID={}｜订单ID={}｜订单号={}｜医院={}｜文件数量={}｜文件总容量={}｜清单版本={}",
                job.getId(), orderId, businessValue(manifest, "orderCode", "publicOrderCode"),
                businessValue(manifest, "hospitalName"), manifest.assets().size(),
                BusinessLogFormatter.bytes(sourceBytes), BusinessLogFormatter.shortHash(manifest.manifestRevision()));
        if (executionMode == BackupExecutionMode.METADATA_SIMULATION) {
            MetadataSimulationArtifactWriter writer = new MetadataSimulationArtifactWriter(
                    resolveSimulationRoot(simulationRoot), store, mapper);
            String orderNo = businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code");
            // Manifest 已成功获取后立即固化业务快照与预计规模；即使本地制品生成失败，详情页仍可定位具体订单。
            context.orderMetadata(orderId, null, orderNo, manifest.orderCreatedAt(),
                    businessValue(manifest, "status", "statusCode"), json(manifest.order()));
            context.orderSimulationMetadata(manifest.assets().size(), sourceBytes, null);
            SimulationArtifactResult result = writer.write(job.getId(), job.getJobNo(), backupDate,
                    orderId, orderNo, manifest);
            com.yigongbao.backup.order.OrderSnapshotEntity snapshot = backupEvidence.saveSimulation(
                    orderId, manifest, result.packageResult());
            backupEvidence.markSimulationAvailable(orderId);
            // simulationPath 字段保存最终热备 ZIP 的 storageKey，不保存已清理的临时解压目录。
            context.orderSimulationMetadata(result.expectedFileCount(), result.expectedBytes(), result.storageKey());
            context.orderMetadata(orderId, snapshot.getId(), snapshot.getOrderCode(),
                    snapshot.getOrderCreatedAt() == null ? null : snapshot.getOrderCreatedAt().toString(),
                    snapshot.getSourceStatus(), json(manifest.order()));
            context.orderCompleted("SIMULATED");
            log.info("订单元数据模拟完成｜任务ID={}｜订单ID={}｜快照ID={}｜预计文件数={}｜预计容量={}｜热备压缩包={}",
                    job.getId(), orderId, snapshot.getId(), result.expectedFileCount(),
                    BusinessLogFormatter.bytes(result.expectedBytes()), result.storageKey());
            return result;
        }
        int reservationPercent = configs.getInt("storage.backup-reservation-percent", 300, 100);
        long reserveBytes = BackupWorkingSpaceEstimator.estimate(manifest, reservationPercent);
        SpaceReservation reservation = reservations.reserve("hot",
                reserveBytes, job.getId());
        log.info("订单备份空间预留完成｜任务ID={}｜订单ID={}｜预留容量={}｜预留比例={}％",
                job.getId(), orderId, BusinessLogFormatter.bytes(reserveBytes), reservationPercent);
        java.util.Set<String> failedAssets = new java.util.HashSet<>();
        RuntimeException businessFailure = null;
        try {
            OrderBackupPipeline.Result result = new OrderBackupPipeline(manifestsClient, cos, store, mapper)
                    .backupWithResult(String.valueOf(orderId), manifest, backupDate,
                            businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code"),
                            new OrderBackupPipeline.AssetProgressListener() {
                        @Override public void started(BackupManifest.Asset asset) {
                            log.info("任务文件处理开始｜任务ID={}｜订单ID={}｜文件ID={}｜业务类型={}｜预计容量={}",
                                    job.getId(), orderId, asset.assetId(), asset.type(),
                                    BusinessLogFormatter.bytes(asset.size()));
                            context.fileStarted(orderId, asset.assetId(), "BACKUP_DOWNLOAD", asset.size());
                        }
                        @Override public void succeeded(BackupManifest.Asset asset) {
                            context.fileSucceeded(orderId, asset.assetId(), "BACKUP_DOWNLOAD", asset.size());
                            log.info("任务文件处理成功｜任务ID={}｜订单ID={}｜文件ID={}｜文件容量={}",
                                    job.getId(), orderId, asset.assetId(),
                                    BusinessLogFormatter.bytes(asset.size()));
                        }
                        @Override public void progress(BackupManifest.Asset asset, long downloadedBytes) {
                            context.fileProgress(orderId, asset.assetId(), "BACKUP_DOWNLOAD", downloadedBytes, asset.size());
                            log.info("任务文件处理进度｜任务ID={}｜订单ID={}｜文件ID={}｜已下载容量={}/{}｜完成比例={}％",
                                    job.getId(), orderId, asset.assetId(),
                                    BusinessLogFormatter.bytes(downloadedBytes), BusinessLogFormatter.bytes(asset.size()),
                                    percent(downloadedBytes, asset.size()));
                        }
                        @Override public void failed(BackupManifest.Asset asset, Throwable failure) {
                            failedAssets.add(asset.assetId());
                            context.fileFailed(orderId, asset.assetId(), "BACKUP_DOWNLOAD", failure);
                            log.warn("任务文件处理失败｜任务ID={}｜订单ID={}｜文件ID={}｜异常类型={}｜异常原因={}",
                                    job.getId(), orderId, asset.assetId(), failure.getClass().getSimpleName(), safe(failure));
                        }
                    }, () -> context.safePoint("BACKUP_COMMIT_" + orderId, 0));
            log.info("订单本地制品生成完成｜任务ID={}｜订单ID={}｜压缩包容量={}｜本地存储键={}｜包摘要={}",
                    job.getId(), orderId, BusinessLogFormatter.bytes(result.packageSize()), result.storageKey(),
                    BusinessLogFormatter.shortHash(result.packageSha256()));
            com.yigongbao.backup.order.OrderSnapshotEntity snapshot = backupEvidence.save(orderId, manifest, result);
            log.info("订单备份证据持久化完成｜任务ID={}｜订单ID={}｜快照ID={}",
                    job.getId(), orderId, snapshot.getId());
            context.orderMetadata(orderId, snapshot.getId(), snapshot.getOrderCode(),
                    snapshot.getOrderCreatedAt() == null ? null : snapshot.getOrderCreatedAt().toString(),
                    snapshot.getSourceStatus(), json(manifest.order()));
            manifestsClient.submitReceipt(String.valueOf(orderId), manifest, result,
                    "backup:" + orderId + ":" + manifest.manifestRevision(), 1);
            log.info("订单线上备份回执提交完成｜任务ID={}｜订单ID={}｜订单号={}｜清单版本={}｜回执版本=1",
                    job.getId(), orderId, snapshot.getOrderCode(),
                    BusinessLogFormatter.shortHash(manifest.manifestRevision()));
            backupEvidence.markAvailable(orderId);
            context.orderSucceeded();
            log.info("订单热备快照已发布｜任务ID={}｜订单ID={}｜快照ID={}｜热备状态=AVAILABLE（可用）",
                    job.getId(), orderId, snapshot.getId());
        } catch (RuntimeException failure) {
            businessFailure = failure;
            // 保留真正失败文件的底层原因，其他文件明确标记为随整单回滚。
            for (BackupManifest.Asset asset : manifest.assets()) {
                if (!failedAssets.contains(asset.assetId())) {
                    context.fileFailed(orderId, asset.assetId(), "BACKUP_ROLLBACK",
                            new IllegalStateException("订单内其他文件失败，本文件随整单回滚"));
                }
            }
            throw failure;
        } finally {
            releaseReservation(reservations, reservation, businessFailure,
                    job.getId(), orderId, reserveBytes);
        }
        return null;
    }

    /** 执行单次订单备份；重试编排由任务级两阶段队列负责，避免当前订单阻塞后续订单。 */
    private SimulationArtifactResult executeAttempt(JobExecutionContext context, JobEntity job, Long orderId,
                                                     Payload payload, BackupExecutionMode executionMode,
                                                     OnlineManifestClient manifestsClient, CosObjectClient cos,
                                                     java.util.Map<Long, Integer> attempts, int maxRetries,
                                                     long completedOrders) {
        int currentAttempt = attempts.merge(orderId, 1, Integer::sum);
        log.info("订单备份尝试开始｜任务ID={}｜订单ID={}｜尝试次数={}/{}｜首轮已处理订单数={}/{}",
                job.getId(), orderId, currentAttempt, maxRetries + 1,
                completedOrders, payload.sourceOrderIds().size());
        context.orderStarted(orderId, null);
        try {
            return backupOnce(context, job, orderId, payload.backupDate(), payload.simulationRoot(),
                    executionMode, manifestsClient, cos);
        } catch (RuntimeException failure) {
            log.warn("订单备份尝试失败｜任务ID={}｜订单ID={}｜尝试次数={}/{}｜异常类型={}｜异常原因={}",
                    job.getId(), orderId, currentAttempt, maxRetries + 1,
                    failure.getClass().getSimpleName(), safe(failure));
            throw failure;
        }
    }

    private static void accumulate(SimulationArtifactResult result,
                                   java.util.concurrent.atomic.AtomicLong files,
                                   java.util.concurrent.atomic.AtomicLong bytes) {
        if (result == null) return;
        files.addAndGet(result.expectedFileCount());
        bytes.addAndGet(result.expectedBytes());
    }

    private static boolean retryable(Throwable failure) {
        return !(failure instanceof RemoteCallException remote) || remote.retryable();
    }

    /**
     * 释放容量预留。主业务已经失败时，释放异常只能作为 suppressed 原因附加，
     * 不能覆盖真正的下载、证据或线上回执异常；主业务成功时释放失败仍向上抛出。
     */
    static void releaseReservation(SpaceReservationService reservations, SpaceReservation reservation,
                                   RuntimeException businessFailure, long jobId, long orderId,
                                   long reserveBytes) {
        try {
            reservations.release(reservation);
            log.debug("订单备份空间预留已释放: jobId={}, orderId={}, reservedBytes={}",
                    jobId, orderId, reserveBytes);
        } catch (RuntimeException releaseFailure) {
            log.error("订单备份空间预留释放失败: jobId={}, orderId={}, reservedBytes={}, errorType={}, error={}",
                    jobId, orderId, reserveBytes, releaseFailure.getClass().getSimpleName(), safe(releaseFailure));
            if (businessFailure == null) throw releaseFailure;
            businessFailure.addSuppressed(releaseFailure);
        }
    }

    private static void waitForRetry(JobExecutionContext context, Long orderId, long seconds) {
        long remaining = seconds;
        try {
            while (remaining > 0) {
                context.heartbeat();
                long step = Math.min(30, remaining);
                Thread.sleep(step * 1000L);
                remaining -= step;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("订单重试等待被中断", interrupted);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("订单业务快照序列化失败", error); }
    }

    private static String businessValue(BackupManifest manifest, String... keys) {
        if (manifest == null || manifest.order() == null) return "-";
        for (String key : keys) {
            Object value = manifest.order().get(key);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "-";
    }

    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
    private static long percent(long done, long total) { return total <= 0 ? 100 : Math.min(100, done * 100 / total); }

    /** 防止远端异常中的预签名 URL 或认证参数进入日志。 */
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

    private java.nio.file.Path resolveSimulationRoot(String snapshotRoot) {
        if (snapshotRoot != null && !snapshotRoot.isBlank()) {
            return java.nio.file.Path.of(snapshotRoot).toAbsolutePath().normalize();
        }
        if (executionModes.simulationRoot() != null) return executionModes.simulationRoot();
        throw new IllegalStateException("模拟任务缺少制品目录快照，且当前环境未配置 BACKUP_SIMULATION_ROOT");
    }

    public record Payload(List<Long> sourceOrderIds, int orderTotal, String backupDate,
                          Integer daysBefore, String orderCreatedBefore, String executionMode,
                          String simulationRoot) {
        public Payload(List<Long> sourceOrderIds) {
            this(sourceOrderIds, sourceOrderIds == null ? 0 : sourceOrderIds.size(),
                    LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), null, null, BackupExecutionMode.REAL.name(), null);
        }
        public Payload(List<Long> sourceOrderIds, int orderTotal) {
            this(sourceOrderIds, orderTotal, LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), null, null, BackupExecutionMode.REAL.name(), null);
        }
        public Payload(List<Long> sourceOrderIds, int orderTotal, String backupDate) {
            this(sourceOrderIds, orderTotal, backupDate, null, null, BackupExecutionMode.REAL.name(), null);
        }
        public Payload {
            sourceOrderIds = sourceOrderIds == null ? List.of() : List.copyOf(sourceOrderIds);
            backupDate = backupDate == null || backupDate.isBlank()
                    ? LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) : backupDate;
            executionMode = executionMode == null || executionMode.isBlank()
                    ? BackupExecutionMode.REAL.name() : executionMode;
        }

        /** 旧任务缺少字段时安全回退到真实模式。 */
        public BackupExecutionMode resolvedExecutionMode() {
            try { return BackupExecutionMode.valueOf(executionMode); }
            catch (RuntimeException invalid) { throw new IllegalStateException("任务执行模式非法: " + executionMode, invalid); }
        }
    }
}
