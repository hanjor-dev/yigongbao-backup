package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.admin.AdminRestoreController;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.PartialJobFailureException;
import com.yigongbao.backup.remote.RemoteCallException;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;

/** 后台执行线上恢复，每个订单边界都是可取消、可续租的安全点。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestoreJobHandler implements JobHandler {
    private final OrderSnapshotMapper snapshots;
    private final OrderPackageMapper packages;
    private final VolumeArtifactStore store;
    private final ArtifactReplicaResolver replicas;
    private final ObjectProvider<OnlineRestoreClient> clients;
    private final ObjectMapper mapper;
    private final PersistentJobService jobService;
    private final RuntimeConfigService configs;
    private final StorageVolumeReadLeaseService readLeases;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        AdminRestoreController.RestorePayload payload =
                mapper.readValue(job.getConfigSnapshotJson(), AdminRestoreController.RestorePayload.class);
        List<OrderSnapshotEntity> targets;
        if (payload.orderSnapshotIds() != null && !payload.orderSnapshotIds().isEmpty()) {
            targets = snapshots.selectBatchIds(payload.orderSnapshotIds());
        } else {
            targets = snapshots.selectList(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                    .ge(payload.createdFrom() != null, OrderSnapshotEntity::getOrderCreatedAt, payload.createdFrom())
                    .le(payload.createdTo() != null, OrderSnapshotEntity::getOrderCreatedAt, payload.createdTo())
                    .orderByAsc(OrderSnapshotEntity::getOrderCreatedAt));
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("没有匹配的订单快照");
        // 任务级重试、进程恢复或租约接管均只处理未完成订单，严禁再次写回已成功订单。
        Set<Long> completedSnapshotIds = jobService.listAllRestoreOrders(job.getId()).stream()
                .filter(row -> "SUCCEEDED".equals(row.getStatus()) || "SKIPPED".equals(row.getStatus()))
                .map(com.yigongbao.backup.job.JobOrderEntity::getSnapshotId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        targets = targets.stream().filter(snapshot -> !completedSnapshotIds.contains(snapshot.getId())).toList();
        if (targets.isEmpty()) {
            log.info("线上恢复任务续跑跳过｜任务ID={}｜原因=所有订单均已有完成结果", job.getId());
            return;
        }
        OnlineRestoreClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("线上恢复客户端未配置");
        LocalOrderRestoreService service = new LocalOrderRestoreService(client, payload.conflictPolicy());
        long done = 0;
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        int nonRetryableFailed = 0;
        Set<Long> succeededOrderIds = new java.util.LinkedHashSet<>();
        Set<Long> skippedOrderIds = new java.util.LinkedHashSet<>();
        Set<Long> failedOrderIds = new java.util.LinkedHashSet<>();
        int retryLimit = configs.getInt("restore.retry-count", 3, 0);
        // 先固化整批订单及其业务快照，即使任务在首个订单前取消或异常，管理页也能展示完整待恢复范围。
        context.initializeOrders(targets.stream().map(OrderSnapshotEntity::getSourceOrderId).toList());
        for (OrderSnapshotEntity snapshot : targets) {
            context.orderMetadata(snapshot.getSourceOrderId(), snapshot.getId(), snapshot.getOrderCode(),
                    snapshot.getOrderCreatedAt() == null ? null : snapshot.getOrderCreatedAt().toString(),
                    snapshot.getStatusName() == null ? snapshot.getSourceStatus() : snapshot.getStatusName(),
                    businessSnapshot(snapshot));
        }
        List<Map<String, Object>> targetOrders = targets.stream().map(snapshot -> Map.<String, Object>of(
                "orderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                "orderNo", snapshot.getOrderCode() == null ? "-" : snapshot.getOrderCode(),
                "createdAt", snapshot.getOrderCreatedAt() == null ? "-" : snapshot.getOrderCreatedAt().toString()))
                .toList();
        context.taskInfo("RESTORE_TARGETS_CONFIRMED", "任务领取", "恢复任务目标范围已确认｜订单数=" + targets.size()
                        + "｜订单号=" + orderCodes(targets),
                Map.of("orderCount", targets.size(), "orders", targetOrders,
                        "conflictPolicy", payload.conflictPolicy()));
        log.info("线上恢复任务目标范围已确认｜任务ID={}｜订单数={}｜订单号={}",
                job.getId(), targets.size(), orderCodes(targets));
        List<OrderSnapshotEntity> pending = new ArrayList<>(targets);
        for (int phase = 0; !pending.isEmpty(); phase++) {
            List<OrderSnapshotEntity> retryPending = new ArrayList<>();
            if (phase > 0) {
                context.failedOrderRetryPhaseStarted(pending.size());
                context.taskWarn("RESTORE_RETRY_PHASE_STARTED", "失败重试",
                        "失败订单重试阶段开始｜第" + phase + "轮｜订单数=" + pending.size(),
                        Map.of("retryPhase", phase, "orderCount", pending.size(),
                                "sourceOrderIds", pending.stream().map(OrderSnapshotEntity::getSourceOrderId).toList()));
                log.info("线上恢复失败订单重试阶段开始｜任务ID={}｜第{}轮｜订单数={}", job.getId(), phase, pending.size());
            }
            log.info("线上恢复任务开始｜任务ID={}｜本轮订单数={}｜冲突策略={}", job.getId(), pending.size(), payload.conflictPolicy());
        for (OrderSnapshotEntity snapshot : pending) {
            context.safePoint("RESTORE_ORDER_" + snapshot.getId(), done);
            context.taskInfo("RESTORE_ORDER_STARTED", "订单进度",
                    "线上恢复订单开始｜订单号=" + safeOrderCode(snapshot) + "｜订单ID=" + snapshot.getSourceOrderId()
                            + "｜第" + (done + 1) + "次处理｜重试阶段=" + phase,
                    Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                            "orderNo", safeOrderCode(snapshot), "retryPhase", phase, "attemptSequence", done + 1));
            if (isDeleted(snapshot.getSourceStatus())) {
                log.info("线上恢复跳过｜任务ID={}｜订单ID={}｜原因=本地快照已删除", job.getId(), snapshot.getSourceOrderId());
                context.orderStarted(snapshot.getSourceOrderId(), snapshot.getId());
                context.orderCompleted("SKIPPED");
                done++;
                skipped++;
                skippedOrderIds.add(snapshot.getSourceOrderId());
                context.taskInfo("RESTORE_ORDER_SKIPPED", "订单进度",
                        "线上恢复订单跳过｜订单号=" + safeOrderCode(snapshot) + "｜原因=本地快照已删除",
                        Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                                "reason", "SNAPSHOT_DELETED"));
                context.safePoint("RESTORE_ORDER_COMPLETED", done);
                continue;
            }
            StorageVolumeReadLeaseService.LeasedArtifact restoreLease = null;
            com.yigongbao.backup.job.JobOrderEntity sourceLocation = jobService.listAllRestoreOrders(job.getId()).stream()
                    .filter(row -> java.util.Objects.equals(row.getSnapshotId(), snapshot.getId())).findFirst().orElse(null);
            try {
                // 本地包、容量等前置检查失败时也必须落订单失败明细。
                context.orderStarted(snapshot.getSourceOrderId(), snapshot.getId());
                if (sourceLocation != null && sourceLocation.getSourcePackageId() != null
                        && sourceLocation.getSourceStorageVolumeId() != null && sourceLocation.getSourceStorageKey() != null) {
                    restoreLease = readLeases.acquirePinned("ORDER_PACKAGE", sourceLocation.getSourcePackageId(),
                            sourceLocation.getSourceStorageVolumeId(), sourceLocation.getSourceStorageKey(),
                            sourceLocation.getSourcePackageSha256(), "RESTORE",
                            "job:" + job.getId() + ":fencing:" + context.fencingToken(), readLeases.defaultTtl());
                }
                Path path = packagePath(job.getId(), snapshot);
                if (restoreLease != null) restoreLease.heartbeat();
                LocalOrderRestoreService.RestorePackageInventory inventory = service.inspect(path, snapshot.getSourceOrderId().toString());
                List<Map<String, Object>> packageAssets = inventory.assets().stream().map(asset -> Map.<String, Object>of(
                        "assetId", asset.assetIdentity(), "fileName", asset.fileName() == null ? "-" : asset.fileName(),
                        "assetType", asset.assetType() == null ? "UNKNOWN" : asset.assetType(),
                        "relationKinds", asset.relationKinds(), "size", asset.size(),
                        "zipEntryPresent", asset.zipEntryPresent())).toList();
                context.orderInfo(snapshot.getSourceOrderId(), "ORDER_PACKAGE_INSPECTED", "订单开始",
                        "订单恢复文件清单已读取｜文件数=" + inventory.assets().size() + "｜清单版本="
                                + (inventory.manifestRevision() == null ? "历史包" : inventory.manifestRevision()),
                        Map.of("orderNo", snapshot.getOrderCode() == null ? "-" : snapshot.getOrderCode(),
                                "archivePath", path.getFileName().toString(), "archiveBytes", Files.size(path),
                                "manifestRevision", inventory.manifestRevision() == null ? "" : inventory.manifestRevision(),
                                "files", packageAssets));
                List<String> missingAssets = inventory.assets().stream().filter(asset -> !asset.zipEntryPresent())
                        .map(LocalOrderRestoreService.RestorePackageAsset::assetIdentity).toList();
                if (!missingAssets.isEmpty()) {
                    throw new IllegalStateException("恢复压缩包缺少清单文件: " + String.join(",", missingAssets));
                }
                // 无关联文件的订单不需要上传或线上回写；标为跳过而非失败，且不得创建空恢复会话。
                if (inventory.assets().isEmpty()) {
                    context.orderCompleted("SKIPPED");
                    context.orderInfo(snapshot.getSourceOrderId(), "ORDER_RESTORE_SKIPPED_NO_ASSETS", "订单跳过",
                            "订单备份包不含关联文件，无需线上恢复", Map.of(
                                    "orderNo", snapshot.getOrderCode() == null ? "-" : snapshot.getOrderCode(),
                                    "archiveBytes", Files.size(path), "reason", "NO_RESTORABLE_ASSETS"));
                    log.info("线上恢复跳过｜任务ID={}｜订单ID={}｜订单号={}｜原因=备份包不含关联文件",
                            job.getId(), snapshot.getSourceOrderId(), snapshot.getOrderCode());
                    done++;
                    skipped++;
                    skippedOrderIds.add(snapshot.getSourceOrderId());
                    context.taskInfo("RESTORE_ORDER_SKIPPED", "订单进度",
                            "线上恢复订单跳过｜订单号=" + safeOrderCode(snapshot) + "｜原因=备份包不含关联文件",
                            Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                                    "reason", "NO_RESTORABLE_ASSETS"));
                    // 跳过同样代表该订单已处理，及时持久化进度并续租，避免长任务出现进度停滞或租约过期。
                    context.safePoint("RESTORE_ORDER_COMPLETED", done);
                    continue;
                }
                // 恢复只从已固化源卷读取并解压到受控临时目录，不向热备卷写入，
                // 因此不能复用“热备写入”的容量预留入口。
                context.fileStarted(snapshot.getSourceOrderId(), snapshot.getOrderCode(), "RESTORE_UPLOAD", Files.size(path));
                log.info("线上恢复订单开始｜任务ID={}｜订单ID={}｜订单号={}｜包大小={}字节", job.getId(), snapshot.getSourceOrderId(), snapshot.getOrderCode(), Files.size(path));
                service.restore(path, snapshot.getSourceOrderId().toString(), new LocalOrderRestoreService.RestoreAssetListener() {
                    @Override public void started(String assetIdentity, String zipEntryName, String objectKey, long totalBytes) {
                        context.fileStepStarted(snapshot.getSourceOrderId(), assetIdentity,
                                "RESTORE_OBJECT_UPLOAD", totalBytes, objectKey);
                        if (sourceLocation != null && sourceLocation.getSourceStorageVolumeId() != null) {
                            context.fileAtVolume(snapshot.getSourceOrderId(), assetIdentity, "RESTORE_OBJECT_UPLOAD", "RUNNING",
                                    0L, totalBytes, objectKey, sourceLocation.getSourceStorageVolumeId(),
                                    sourceLocation.getSourceStorageKey() + "!/" + zipEntryName, null);
                        }
                        context.orderInfo(snapshot.getSourceOrderId(), "FILE_UPLOAD_STARTED", "文件上传",
                                "开始上传文件｜资产ID=" + assetIdentity + "｜文件=" + zipEntryName,
                                Map.of("assetId", assetIdentity, "fileName", zipEntryName, "bytesTotal", totalBytes));
                    }

                    @Override public void succeeded(String assetIdentity, String zipEntryName, String objectKey, long uploadedBytes) {
                        context.fileStepSucceeded(snapshot.getSourceOrderId(), assetIdentity,
                                "RESTORE_OBJECT_UPLOAD", uploadedBytes);
                        if (sourceLocation != null && sourceLocation.getSourceStorageVolumeId() != null) {
                            context.fileAtVolume(snapshot.getSourceOrderId(), assetIdentity, "RESTORE_OBJECT_UPLOAD", "SUCCEEDED",
                                    uploadedBytes, uploadedBytes, objectKey, sourceLocation.getSourceStorageVolumeId(),
                                    sourceLocation.getSourceStorageKey() + "!/" + zipEntryName, null);
                        }
                        context.orderInfo(snapshot.getSourceOrderId(), "FILE_UPLOAD_SUCCEEDED", "文件上传",
                                "文件上传成功｜资产ID=" + assetIdentity + "｜文件=" + zipEntryName + "｜容量=" + uploadedBytes + "字节",
                                Map.of("assetId", assetIdentity, "fileName", zipEntryName, "bytesUploaded", uploadedBytes));
                    }

                    @Override public void failed(String assetIdentity, String zipEntryName, String objectKey,
                                                 long uploadedBytes, Throwable failure) {
                        context.fileStepFailed(snapshot.getSourceOrderId(), assetIdentity,
                                "RESTORE_OBJECT_UPLOAD", uploadedBytes, failure);
                        if (sourceLocation != null && sourceLocation.getSourceStorageVolumeId() != null) {
                            context.fileAtVolume(snapshot.getSourceOrderId(), assetIdentity, "RESTORE_OBJECT_UPLOAD", "FAILED",
                                    uploadedBytes, 0L, objectKey, sourceLocation.getSourceStorageVolumeId(),
                                    sourceLocation.getSourceStorageKey() + "!/" + zipEntryName, failure);
                        }
                        context.orderError(snapshot.getSourceOrderId(), "FILE_UPLOAD_FAILED", "文件上传",
                                "文件上传失败｜资产ID=" + assetIdentity + "｜文件=" + zipEntryName + "｜原因=" + safe(failure),
                                Map.of("assetId", assetIdentity, "fileName", zipEntryName, "bytesUploaded", uploadedBytes,
                                        "reason", safe(failure)));
                    }
                });
                context.fileSucceeded(snapshot.getSourceOrderId(), Files.size(path));
                context.orderSucceeded();
                succeeded++;
                succeededOrderIds.add(snapshot.getSourceOrderId());
                context.taskInfo("RESTORE_ORDER_SUCCEEDED", "订单进度",
                        "线上恢复订单完成｜订单号=" + safeOrderCode(snapshot) + "｜文件数=" + inventory.assets().size(),
                        Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                                "fileCount", inventory.assets().size(), "archiveBytes", Files.size(path)));
                context.orderInfo(snapshot.getSourceOrderId(), "ORDER_RESTORE_SUCCEEDED", "订单完成",
                        "订单恢复成功｜文件数=" + inventory.assets().size(),
                        Map.of("orderNo", snapshot.getOrderCode() == null ? "-" : snapshot.getOrderCode(),
                                "fileCount", inventory.assets().size(), "archiveBytes", Files.size(path)));
                log.info("线上恢复订单完成｜任务ID={}｜订单ID={}", job.getId(), snapshot.getSourceOrderId());
            } catch (Exception failure) {
                boolean nonRetryable = isNonRetryableRemoteFailure(failure);
                if (nonRetryable || phase >= retryLimit) {
                    failed++;
                    if (nonRetryable) nonRetryableFailed++;
                    failedOrderIds.add(snapshot.getSourceOrderId());
                } else {
                    context.orderQueuedForRetry(snapshot.getSourceOrderId(), failure);
                    retryPending.add(snapshot);
                    context.orderWarn(snapshot.getSourceOrderId(), "ORDER_RETRY_QUEUED", "订单重试",
                            "订单恢复失败，已加入失败订单重试队列｜原因=" + safe(failure),
                            Map.of("retryPhase", phase + 1, "retryLimit", retryLimit, "reason", safe(failure)));
                    context.taskWarn("RESTORE_ORDER_RETRY_QUEUED", "订单进度",
                            "线上恢复订单失败，已加入重试队列｜订单号=" + safeOrderCode(snapshot) + "｜原因=" + safe(failure),
                            Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                                    "retryPhase", phase + 1, "retryLimit", retryLimit, "reason", safe(failure)));
                }
                // 当前订单已经登记了 RESTORE_UPLOAD 文件步骤；必须同时落失败文件和失败订单，
                // 否则任务详情会残留 RUNNING 子项并造成总进度不闭合。
                if (nonRetryable || phase >= retryLimit) context.failure(failure);
                if (nonRetryable || phase >= retryLimit) context.orderError(snapshot.getSourceOrderId(), "ORDER_RESTORE_FAILED", "订单失败",
                        "订单恢复失败｜原因=" + safe(failure), Map.of("reason", safe(failure), "retryPhase", phase));
                if (nonRetryable || phase >= retryLimit) context.taskError("RESTORE_ORDER_FAILED", "订单进度",
                        "线上恢复订单最终失败｜订单号=" + safeOrderCode(snapshot) + "｜原因=" + safe(failure),
                        Map.of("sourceOrderId", snapshot.getSourceOrderId(), "snapshotId", snapshot.getId(),
                                "retryPhase", phase, "reason", safe(failure), "nonRetryable", nonRetryable));
                log.warn("线上恢复订单失败，继续后续订单｜任务ID={}｜订单ID={}｜原因={}", job.getId(), snapshot.getSourceOrderId(), failure.getMessage(), failure);
            } finally {
                if (restoreLease != null) restoreLease.close();
            }
            done++;
            context.safePoint("RESTORE_ORDER_COMPLETED", done);
            context.taskInfo("RESTORE_PROGRESS_UPDATED", "任务进度",
                    "恢复处理进度｜成功=" + succeeded + "｜跳过=" + skipped + "｜失败=" + failed
                            + "｜待重试=" + retryPending.size(),
                    Map.of("attemptedOrders", done, "orderTotal", targets.size(), "succeededOrders", succeeded,
                            "skippedOrders", skipped, "failedOrders", failed, "retryPendingOrders", retryPending.size(),
                            "succeededOrderIds", succeededOrderIds, "skippedOrderIds", skippedOrderIds,
                            "failedOrderIds", failedOrderIds));
            }
            pending = retryPending;
        }
        if (failed > 0) {
            context.taskError("RESTORE_TASK_PARTIAL_FAILED", "任务结束",
                    "线上恢复任务处理完成｜成功=" + succeeded + "｜跳过=" + skipped + "｜失败=" + failed,
                    Map.of("succeededOrders", succeeded, "skippedOrders", skipped, "failedOrders", failed,
                            "succeededOrderIds", succeededOrderIds, "skippedOrderIds", skippedOrderIds,
                            "failedOrderIds", failedOrderIds));
            if (failed == targets.size() && nonRetryableFailed == failed) {
                // 清单摘要、关系冲突等确定性业务拒绝不应被任务级重试放大。
                throw new PartialJobFailureException("部分订单线上恢复失败: " + failed + "/" + targets.size(), true);
            }
            // 失败订单已在本任务内完成所有重试；写为部分失败，禁止任务级再次重跑。
            throw new PartialJobFailureException("部分订单线上恢复失败: " + failed + "/" + targets.size(), false);
        }
        context.taskInfo("RESTORE_TASK_SUCCEEDED", "任务完成", "线上恢复任务完成｜成功订单数=" + succeeded + "｜跳过订单数=" + skipped,
                Map.of("succeededOrders", succeeded, "skippedOrders", skipped,
                        "succeededOrderIds", succeededOrderIds, "skippedOrderIds", skippedOrderIds));
        log.info("线上恢复任务完成｜任务ID={}｜成功订单数={}｜跳过订单数={}", job.getId(), succeeded, skipped);
    }

    private Path packagePath(long jobId, OrderSnapshotEntity snapshot) {
        if (snapshot == null || snapshot.getSourceOrderId() == null) {
            throw new IllegalArgumentException("恢复订单快照不完整");
        }
        // 元数据模拟包中的文件是零字节占位符，绝不能上传到线上覆盖真实业务文件。
        if ("METADATA_SIMULATION".equalsIgnoreCase(snapshot.getBackupMode())) {
            throw new IllegalArgumentException("模拟备份包仅供流程验证和下载查看，禁止用于线上恢复");
        }
        com.yigongbao.backup.job.JobOrderEntity frozen = jobService.listAllRestoreOrders(jobId).stream()
                .filter(row -> java.util.Objects.equals(row.getSnapshotId(), snapshot.getId())).findFirst().orElse(null);
        if (frozen != null && frozen.getSourceStorageVolumeId() != null && frozen.getSourceStorageKey() != null) {
            try {
                Path path = store.resolve(frozen.getSourceStorageVolumeId(), frozen.getSourceStorageKey());
                if (!Files.isRegularFile(path)) throw new IllegalArgumentException("已固化恢复源压缩包不存在");
                verifyFrozenSource(path, frozen.getSourcePackageSha256());
                return path;
            } catch (java.io.IOException exception) { throw new IllegalStateException("读取已固化恢复源失败", exception); }
        }
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshot.getId()).last("limit 1"));
        if (pkg == null || pkg.getId() == null) throw new IllegalArgumentException("订单备份包不存在");
        ArtifactReplicaResolver.ReadableArtifactReplica replica = replicas.resolveReadable("ORDER_PACKAGE", pkg.getId());
        try {
            Path path = store.resolve(replica.storageVolumeId(), replica.storageKey());
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("订单备份包不存在");
            return path;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("读取订单备份卷失败", exception);
        }
    }

    private static boolean isDeleted(String status) {
        return status != null && ("DELETED".equalsIgnoreCase(status) || "DELETE".equalsIgnoreCase(status));
    }

    /**
     * 恢复任务创建时已固化源包摘要；执行前重新核验，避免卷被替换或文件静默损坏后仍回写线上。
     */
    private static void verifyFrozenSource(Path path, String expectedSha256) throws java.io.IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) return;
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("已固化恢复源压缩包校验失败");
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    /** 将备份时固化的业务字段复用到恢复任务明细，避免恢复页面只能显示内部 ID。 */
    private String businessSnapshot(OrderSnapshotEntity snapshot) {
        if (snapshot.getDisplayJson() != null && !snapshot.getDisplayJson().isBlank()) return snapshot.getDisplayJson();
        if (snapshot.getOrderRawJson() != null && !snapshot.getOrderRawJson().isBlank()) return snapshot.getOrderRawJson();
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /** 沿异常链识别线上明确声明不可重试的协议失败，网络/5xx 仍沿用原任务重试策略。 */
    private static boolean isNonRetryableRemoteFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RemoteCallException remote) return !remote.retryable();
        }
        return false;
    }

    /** 控制台正文展示订单号；完整结构化订单清单已记录到任务日志详情。 */
    private static String orderCodes(List<OrderSnapshotEntity> snapshots) {
        String joined = snapshots.stream().map(snapshot -> snapshot.getOrderCode() == null ? String.valueOf(snapshot.getSourceOrderId()) : snapshot.getOrderCode())
                .collect(java.util.stream.Collectors.joining("、"));
        return joined.length() <= 1000 ? joined : joined.substring(0, 1000) + "…（完整清单见日志详情）";
    }

    /** 任务级摘要在订单编号缺失时回退源订单 ID，保证管理员可定位目标。 */
    private static String safeOrderCode(OrderSnapshotEntity snapshot) {
        return snapshot.getOrderCode() == null || snapshot.getOrderCode().isBlank()
                ? String.valueOf(snapshot.getSourceOrderId()) : snapshot.getOrderCode();
    }

    /** 日志内容不得包含预签名 URL、认证参数等敏感远端值。 */
    private static String safe(Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "无错误详情" : failure.getMessage();
        return message.replaceAll("(?i)https?://\\S+", "[URL_REDACTED]")
                .replaceAll("(?i)(signature|secret|token|credential|authorization)\\s*[=:]\\s*[^,;]+", "$1=[REDACTED]");
    }
}
