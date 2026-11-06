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
        java.util.concurrent.atomic.AtomicLong packageBytes = new java.util.concurrent.atomic.AtomicLong();
        java.util.Map<Long, Throwable> retryQueue = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Throwable> terminalFailures = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Integer> attempts = new java.util.HashMap<>();
        context.initializeOrders(payload.sourceOrderIds());
        int maxRetries = configs.getInt("backup.retry.max-retries", 3, 0);
        OrderBackupRetryPolicy retryPolicy = new OrderBackupRetryPolicy(maxRetries,
                configs.get("backup.retry.delays-seconds", "60,300,900"));
        log.info("订单备份任务开始｜任务ID={}｜任务编号={}｜执行模式={}｜订单总数={}｜单订单最大重试次数={}",
                job.getId(), job.getJobNo(), executionMode, payload.sourceOrderIds().size(), maxRetries);
        context.taskInfo("BACKUP_TARGETS_CONFIRMED", "任务领取",
                "订单备份任务目标范围已确认｜订单数=" + payload.sourceOrderIds().size(),
                java.util.Map.of("executionMode", executionMode.name(), "orderCount", payload.sourceOrderIds().size(),
                        "sourceOrderIds", payload.sourceOrderIds(), "backupDate", payload.backupDate()));
        context.taskInfo("BACKUP_TASK_STARTED", "任务执行",
                "订单备份任务开始｜执行模式=" + executionMode + "｜单订单最大重试次数=" + maxRetries,
                java.util.Map.of("executionMode", executionMode.name(), "maxOrderRetries", maxRetries));
        int orderIndex = 0;
        for (Long orderId : payload.sourceOrderIds()) {
            orderIndex++;
            long orderStartedAt = System.nanoTime();
            context.safePoint("BACKUP_ORDER_" + orderId, done);
            log.info("订单备份开始｜任务ID={}｜订单ID={}｜订单进度={}/{}｜已处理订单数={}",
                    job.getId(), orderId, orderIndex, payload.sourceOrderIds().size(), done);
            // 任务日志只保留订单生命周期摘要；文件、清单和打包明细写入订单日志，避免任务控制台被大量文件事件淹没。
            context.taskInfo("BACKUP_ORDER_STARTED", "订单进度",
                    "订单备份开始｜第" + orderIndex + "/" + payload.sourceOrderIds().size()
                            + "个｜订单ID=" + orderId + "｜已处理=" + done,
                    java.util.Map.of("sourceOrderId", orderId, "orderIndex", orderIndex,
                            "orderTotal", payload.sourceOrderIds().size(), "processedOrders", done,
                            "attempt", attempts.getOrDefault(orderId, 0) + 1));
            try {
                SimulationArtifactResult simulated = executeAttempt(context, job, orderId, payload, executionMode,
                        manifestsClient, cos, attempts, maxRetries, done);
                accumulate(simulated, expectedFiles, expectedBytes, packageBytes);
                context.taskInfo("BACKUP_ORDER_SUCCEEDED", "订单进度",
                        "订单备份完成｜订单ID=" + orderId + "｜尝试次数=" + attempts.get(orderId),
                        java.util.Map.of("sourceOrderId", orderId, "attempt", attempts.get(orderId),
                                "expectedFiles", simulated == null ? 0 : simulated.expectedFileCount(),
                                "sourceBytes", simulated == null ? 0 : simulated.expectedBytes(),
                                "packageBytes", simulated == null ? 0 : simulated.packageSize()));
                log.info("订单备份成功｜任务ID={}｜订单ID={}｜实际尝试次数={}｜执行耗时={}",
                        job.getId(), orderId, attempts.get(orderId),
                        BusinessLogFormatter.duration(elapsedMillis(orderStartedAt)));
            } catch (Exception failure) {
                if (retryable(failure) && maxRetries > 0) {
                    retryQueue.put(orderId, failure);
                    context.orderQueuedForRetry(orderId, failure);
                    context.orderWarn(orderId, "BACKUP_ORDER_RETRY_QUEUED", "失败重试",
                            "订单首轮备份失败，已移至任务末尾等待重试｜异常原因=" + safe(failure), failureDetails(failure, attempts.get(orderId)));
                    context.taskWarn("BACKUP_ORDER_RETRY_QUEUED", "订单进度",
                            "订单备份失败，已加入失败重试队列｜订单ID=" + orderId + "｜队列数=" + retryQueue.size()
                                    + "｜原因=" + safe(failure),
                            taskFailureDetails(orderId, failure, attempts.get(orderId), retryQueue.size()));
                    log.warn("订单首轮备份失败，已移至任务末尾重试｜任务ID={}｜订单ID={}｜当前失败队列长度={}｜异常类型={}｜异常原因={}",
                            job.getId(), orderId, retryQueue.size(), failure.getClass().getSimpleName(), safe(failure));
                } else {
                    terminalFailures.put(orderId, failure);
                    context.orderFailed(executionMode == BackupExecutionMode.METADATA_SIMULATION ? "SIMULATION_FAILED" : "FAILED", failure);
                    context.orderError(orderId, "BACKUP_ORDER_FAILED", "订单结束",
                            "订单备份不可重试失败｜异常原因=" + safe(failure), failureDetails(failure, attempts.get(orderId)));
                    context.taskError("BACKUP_ORDER_FAILED", "订单进度",
                            "订单备份不可重试失败｜订单ID=" + orderId + "｜原因=" + safe(failure),
                            taskFailureDetails(orderId, failure, attempts.get(orderId), terminalFailures.size()));
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
            context.taskInfo("BACKUP_PROGRESS_UPDATED", "任务进度",
                    "订单处理进度=" + done + "/" + payload.sourceOrderIds().size() + "｜成功="
                            + (done - retryQueue.size() - terminalFailures.size()) + "｜待重试=" + retryQueue.size()
                            + "｜终态失败=" + terminalFailures.size(),
                    progressDetails(done, payload.sourceOrderIds().size(), retryQueue.size(), terminalFailures.size()));
        }

        if (!retryQueue.isEmpty() && retryPolicy.maxRetries() > 0) {
            context.failedOrderRetryPhaseStarted(retryQueue.size());
            context.taskWarn("BACKUP_RETRY_PHASE_STARTED", "失败重试",
                    "首轮处理完成，开始集中重试失败订单｜订单数=" + retryQueue.size(),
                    java.util.Map.of("retryOrderIds", new java.util.ArrayList<>(retryQueue.keySet()), "maxRetryRounds", retryPolicy.maxRetries()));
            log.warn("订单首轮处理完成，进入失败订单集中重试阶段｜任务ID={}｜待重试订单数={}｜最大重试轮数={}",
                    job.getId(), retryQueue.size(), retryPolicy.maxRetries());
            for (int retryRound = 1; retryRound <= retryPolicy.maxRetries() && !retryQueue.isEmpty(); retryRound++) {
                long delay = retryPolicy.delaySeconds(retryRound);
                log.warn("失败订单重试轮次等待｜任务ID={}｜重试轮次={}/{}｜待重试订单数={}｜等待时间={}秒",
                        job.getId(), retryRound, retryPolicy.maxRetries(), retryQueue.size(), delay);
                context.taskWarn("BACKUP_RETRY_ROUND_STARTED", "失败重试",
                        "失败订单重试第" + retryRound + "/" + retryPolicy.maxRetries() + "轮开始｜订单数=" + retryQueue.size()
                                + "｜等待=" + delay + "秒",
                        java.util.Map.of("retryRound", retryRound, "maxRetryRounds", retryPolicy.maxRetries(),
                                "waitSeconds", delay, "sourceOrderIds", new java.util.ArrayList<>(retryQueue.keySet())));
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
                        accumulate(simulated, expectedFiles, expectedBytes, packageBytes);
                        context.taskInfo("BACKUP_ORDER_RETRY_SUCCEEDED", "失败重试",
                                "失败订单重试成功｜订单ID=" + orderId + "｜第" + retryRound + "轮｜累计尝试=" + attempts.get(orderId),
                                java.util.Map.of("sourceOrderId", orderId, "retryRound", retryRound,
                                        "attempt", attempts.get(orderId)));
                        log.info("失败订单重试成功｜任务ID={}｜订单ID={}｜重试轮次={}/{}｜累计尝试次数={}",
                                job.getId(), orderId, retryRound, retryPolicy.maxRetries(), attempts.get(orderId));
                    } catch (Exception failure) {
                        retryQueue.put(orderId, failure);
                        if (retryRound < retryPolicy.maxRetries()) context.orderQueuedForRetry(orderId, failure);
                        context.orderWarn(orderId, "BACKUP_ORDER_RETRY_FAILED", "失败重试",
                                "订单重试未成功｜第" + retryRound + "轮｜异常原因=" + safe(failure), failureDetails(failure, attempts.get(orderId)));
                        context.taskWarn("BACKUP_ORDER_RETRY_FAILED", "失败重试",
                                "失败订单重试未成功｜订单ID=" + orderId + "｜第" + retryRound + "轮｜原因=" + safe(failure),
                                taskFailureDetails(orderId, failure, attempts.get(orderId), retryQueue.size()));
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
            context.orderError(orderId, "BACKUP_ORDER_TERMINAL_FAILED", "订单结束",
                    "订单备份最终失败｜异常原因=" + safe(failure), failureDetails(failure, attempts.getOrDefault(orderId, 1)));
            context.taskError("BACKUP_ORDER_FAILED", "订单进度",
                    "订单备份最终失败｜订单ID=" + orderId + "｜累计尝试=" + attempts.getOrDefault(orderId, 1)
                            + "｜原因=" + safe(failure),
                    taskFailureDetails(orderId, failure, attempts.getOrDefault(orderId, 1), terminalFailures.size()));
            failures.add(orderId + ": " + safe(failure));
            log.error("订单备份终态失败｜任务ID={}｜订单ID={}｜累计尝试次数={}｜异常类型={}｜异常原因={}",
                    job.getId(), orderId, attempts.getOrDefault(orderId, 1),
                    failure.getClass().getSimpleName(), safe(failure), safeThrowable(failure));
        });
        java.util.Map<String, Object> resultSummary = new java.util.LinkedHashMap<>();
        resultSummary.put("executionMode", executionMode.name());
        resultSummary.put("orderTotal", payload.sourceOrderIds().size());
        resultSummary.put("orderSucceeded", payload.sourceOrderIds().size() - failures.size());
        resultSummary.put("orderFailed", failures.size());
        resultSummary.put("manifestSucceeded", payload.sourceOrderIds().size() - failures.size());
        resultSummary.put("manifestFailed", failures.size());
        resultSummary.put("expectedFileTotal", expectedFiles.get());
        resultSummary.put("expectedBytes", expectedBytes.get());
        resultSummary.put("sourceBytes", expectedBytes.get());
        resultSummary.put("packageBytes", packageBytes.get());
        resultSummary.put("failures", failures);
        context.result(mapper.writeValueAsString(resultSummary));
        context.taskInfo("BACKUP_TASK_SUMMARY", "任务结束",
                "订单备份任务处理完成｜成功=" + resultSummary.get("orderSucceeded") + "｜失败=" + resultSummary.get("orderFailed"), resultSummary);
        log.info("订单备份任务汇总｜任务ID={}｜任务编号={}｜处理进度={}/{}｜成功订单数={}｜失败订单数={}｜源文件总容量（压缩前）={}｜ZIP实际容量={}｜总执行耗时={}",
                job.getId(), job.getJobNo(), done, payload.sourceOrderIds().size(),
                payload.sourceOrderIds().size() - failures.size(), failures.size(),
                BusinessLogFormatter.bytes(expectedBytes.get()), BusinessLogFormatter.bytes(packageBytes.get()),
                BusinessLogFormatter.duration(elapsedMillis(taskStartedAt)));
        if (!failures.isEmpty()) {
            boolean allFailed = failures.size() == payload.sourceOrderIds().size();
            throw new PartialJobFailureException(allFailed
                    ? "全部订单备份失败: " + String.join("; ", failures)
                    : "部分订单备份失败: " + String.join("; ", failures), allFailed);
        }
    }

    private SimulationArtifactResult backupOnce(JobExecutionContext context, JobEntity job, Long orderId, String backupDate,
                            String simulationRoot, BackupExecutionMode executionMode,
                            OnlineManifestClient manifestsClient, CosObjectClient cos) {
        log.info("读取线上订单清单开始｜任务ID={}｜订单ID={}", job.getId(), orderId);
        BackupManifest manifest = manifestsClient.fetch(String.valueOf(orderId));
        long sourceBytes = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum();
        log.info("读取线上订单清单完成｜任务ID={}｜订单ID={}｜订单号={}｜医院={}｜文件数量={}｜源文件总容量（压缩前）={}｜清单版本={}",
                job.getId(), orderId, businessValue(manifest, "orderCode", "publicOrderCode"),
                businessValue(manifest, "hospitalName"), manifest.assets().size(),
                BusinessLogFormatter.bytes(sourceBytes), BusinessLogFormatter.shortHash(manifest.manifestRevision()));
        // 真实模式也固化 Manifest 的源文件规模，任务统计中的“压缩前”容量才不会显示为 0。
        context.orderSimulationMetadata(manifest.assets().size(), sourceBytes, null);
        context.orderInfo(orderId, "BACKUP_MANIFEST_RECEIVED", "订单清单",
                "线上订单清单读取完成｜订单号=" + businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code")
                        + "｜文件数=" + manifest.assets().size() + "｜源文件总容量=" + BusinessLogFormatter.bytes(sourceBytes),
                manifestDetails(manifest, sourceBytes));
        // 候选扫描阶段只有线上订单 ID；订单号必须在清单读取后才能可靠获得，因此同步补充任务级目标订单识别日志。
        context.taskInfo("BACKUP_TARGET_ORDER_IDENTIFIED", "订单清单",
                "已识别任务目标订单｜订单ID=" + orderId + "｜订单号=" + businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code"),
                java.util.Map.of("sourceOrderId", orderId, "orderNo", businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code")));
        if (executionMode == BackupExecutionMode.METADATA_SIMULATION) {
            MetadataSimulationArtifactWriter writer = new MetadataSimulationArtifactWriter(
                    resolveSimulationRoot(simulationRoot), store, mapper);
            String orderNo = businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code");
            // Manifest 已成功获取后立即固化业务快照与预计规模；即使本地制品生成失败，详情页仍可定位具体订单。
            context.orderMetadata(orderId, null, orderNo, manifest.orderCreatedAt(),
                    businessValue(manifest, "status", "statusCode"), json(manifest.order()));
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
            context.orderInfo(orderId, "BACKUP_SIMULATION_SUCCEEDED", "订单结束",
                    "模拟备份制品已生成｜ZIP实际容量=" + BusinessLogFormatter.bytes(result.packageSize()),
                    java.util.Map.of("storageKey", result.storageKey(), "packageBytes", result.packageSize(), "snapshotId", snapshot.getId()));
            log.info("订单元数据模拟完成｜任务ID={}｜订单ID={}｜快照ID={}｜预计文件数={}｜源文件预计容量（压缩前）={}｜ZIP实际容量={}｜热备压缩包={}",
                    job.getId(), orderId, snapshot.getId(), result.expectedFileCount(),
                    BusinessLogFormatter.bytes(result.expectedBytes()), BusinessLogFormatter.bytes(result.packageSize()), result.storageKey());
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
        OrderBackupPipeline.Result generatedPackage = null;
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
                            context.orderInfo(orderId, "BACKUP_FILE_DOWNLOAD_STARTED", "文件下载",
                                    "开始下载文件｜文件名=" + safeText(asset.fileName()) + "｜容量=" + BusinessLogFormatter.bytes(asset.size()), assetDetails(asset));
                        }
                        @Override public void succeeded(BackupManifest.Asset asset) {
                            context.fileSucceeded(orderId, asset.assetId(), "BACKUP_DOWNLOAD", asset.size());
                            context.orderInfo(orderId, "BACKUP_FILE_DOWNLOAD_SUCCEEDED", "文件下载",
                                    "文件下载完成｜文件名=" + safeText(asset.fileName()) + "｜容量=" + BusinessLogFormatter.bytes(asset.size()), assetDetails(asset));
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
                            context.orderError(orderId, "BACKUP_FILE_DOWNLOAD_FAILED", "文件下载",
                                    "文件下载失败｜文件名=" + safeText(asset.fileName()) + "｜异常原因=" + safe(failure), failureDetails(failure, null));
                            log.warn("任务文件处理失败｜任务ID={}｜订单ID={}｜文件ID={}｜异常类型={}｜异常原因={}",
                                    job.getId(), orderId, asset.assetId(), failure.getClass().getSimpleName(), safe(failure));
                        }
                    }, () -> context.safePoint("BACKUP_COMMIT_" + orderId, 0));
            generatedPackage = result;
            log.info("订单本地制品生成完成｜任务ID={}｜订单ID={}｜压缩包容量={}｜本地存储键={}｜包摘要={}",
                    job.getId(), orderId, BusinessLogFormatter.bytes(result.packageSize()), result.storageKey(),
                    BusinessLogFormatter.shortHash(result.packageSha256()));
            com.yigongbao.backup.order.OrderSnapshotEntity snapshot = backupEvidence.save(orderId, manifest, result);
            context.orderInfo(orderId, "BACKUP_PACKAGE_CREATED", "本地打包",
                    "订单压缩包生成完成｜ZIP实际容量=" + BusinessLogFormatter.bytes(result.packageSize()),
                    java.util.Map.of("storageKey", result.storageKey(), "packageBytes", result.packageSize(), "packageSha256", result.packageSha256(), "snapshotId", snapshot.getId()));
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
            context.orderInfo(orderId, "BACKUP_ORDER_SUCCEEDED", "订单结束",
                    "订单热备完成并发布为可用", java.util.Map.of("snapshotId", snapshot.getId(), "orderNo", safeText(snapshot.getOrderCode())));
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
        // 真实备份也必须回传文件和 ZIP 容量，否则任务级汇总会错误显示为 0。
        return new SimulationArtifactResult(null, manifest.assets().size(), sourceBytes,
                generatedPackage.storageKey(), generatedPackage.packageSha256(), generatedPackage.packageSize(),
                generatedPackage.downloadedHashes(), generatedPackage.packageEntryNames());
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
                                   java.util.concurrent.atomic.AtomicLong bytes,
                                   java.util.concurrent.atomic.AtomicLong packageBytes) {
        if (result == null) return;
        files.addAndGet(result.expectedFileCount());
        bytes.addAndGet(result.expectedBytes());
        packageBytes.addAndGet(result.packageSize());
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

    private static java.util.Map<String, Object> manifestDetails(BackupManifest manifest, long sourceBytes) {
        java.util.List<java.util.Map<String, Object>> assets = manifest.assets().stream().map(ManualBackupJobHandler::assetDetails).toList();
        return java.util.Map.of("orderNo", businessValue(manifest, "orderNo", "orderCode", "publicOrderCode", "code"),
                "manifestRevision", safeText(manifest.manifestRevision()), "assetCount", manifest.assets().size(), "sourceBytes", sourceBytes, "assets", assets);
    }

    private static java.util.Map<String, Object> assetDetails(BackupManifest.Asset asset) {
        return java.util.Map.of("assetId", safeText(asset.assetId()), "fileName", safeText(asset.fileName()),
                "fileType", safeText(asset.type()), "size", asset.size(), "relationKinds", asset.relations() == null ? java.util.List.of() : asset.relations().stream().map(r -> safeText(String.valueOf(r.getOrDefault("kind", r.getOrDefault("type", "-"))))).toList());
    }

    private static java.util.Map<String, Object> failureDetails(Throwable failure, Integer attempt) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("errorType", failure == null ? "-" : failure.getClass().getSimpleName()); details.put("errorMessage", safe(failure));
        if (attempt != null) details.put("attempt", attempt); return details;
    }

    /** 构造任务视图使用的订单失败摘要，不写入可能含敏感信息的原始异常对象。 */
    private static java.util.Map<String, Object> taskFailureDetails(Long orderId, Throwable failure, Integer attempt, int queueSize) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>(failureDetails(failure, attempt));
        details.put("sourceOrderId", orderId);
        details.put("queueSize", queueSize);
        return details;
    }

    /** 任务级进度事件的结构化字段，便于前端后续展示进度趋势而无需解析正文。 */
    private static java.util.Map<String, Object> progressDetails(long processed, int total, int retryPending, int terminalFailed) {
        return java.util.Map.of("processedOrders", processed, "orderTotal", total,
                "succeededOrders", processed - retryPending - terminalFailed,
                "retryPendingOrders", retryPending, "failedOrders", terminalFailed,
                "progressPercent", percent(processed, total));
    }

    private static String safeText(String value) { return value == null || value.isBlank() ? "-" : value; }

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
