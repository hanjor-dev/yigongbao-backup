package com.yigongbao.backup.cold;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.order.ColdSupplementCoverageService;
import com.yigongbao.backup.order.OrderPackageEntity;
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 将订单 ZIP 复制到已纳管的冷备卷，并为每份 ZIP 建立独立的 COLD 副本台账。
 * 冷盘重新挂载且在线时，恢复和下载直接通过副本台账读取，不需要导入回热备卷。
 */
@Component
@RequiredArgsConstructor
public class ColdWriteJobHandler implements JobHandler {
    private final ColdBatchMapper batches;
    private final ColdBatchArtifactMapper batchArtifacts;
    private final ColdBatchWorkflowService workflow;
    private final ColdSupplementCoverageService coverage;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;
    private final StorageVolumeMapper volumes;
    private final OrderPackageMapper packages;
    private final ArtifactCopyMapper copies;
    private final ArtifactReplicaResolver replicas;
    private final VolumeArtifactStore store;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        ColdBatchEntity batch = requiredBatch(payload.batchId());
        StorageVolumeEntity target = requiredWritableColdVolume(payload.targetStorageVolumeId());
        List<OrderPackageEntity> packageRows = resolvePackages(payload.orderPackages());
        if ("COMPLETED".equals(batch.getStatus()) || "VERIFIED".equals(batch.getStatus())) {
            context.safePoint("COLD_WRITE_ALREADY_COMPLETED", packageRows.size());
            return;
        }
        long estimated = packageRows.stream().map(OrderPackageEntity::getPackageSize)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
        SpaceReservation reservation = reservations.reserve(target.getId(), Math.max(1L, estimated), job.getId());
        long copiedBytes = 0L;
        try {
            context.safePoint("COLD_WRITE_START", 0);
            if ("CREATED".equals(batch.getStatus())) workflow.start(batch.getId());
            else if (!"WRITING".equals(batch.getStatus())) throw new IllegalStateException("冷备批次状态不可写入");
            for (int index = 0; index < packageRows.size(); index++) {
                OrderPackageEntity orderPackage = packageRows.get(index);
                long size = orderPackage.getPackageSize() == null ? 0L : orderPackage.getPackageSize();
                context.fileStarted(null, orderPackage.getStorageKey(), "COLD_WRITE", size);
                copiedBytes += copyOne(batch, target, orderPackage);
                context.fileSucceeded(null, size);
                context.safePoint("COLD_WRITE_COPY", index + 1L);
            }
            workflow.complete(batch.getId(), packageRows.size(), packageRows.size(), copiedBytes,
                    payload.sha256() == null ? "" : payload.sha256());
            coverage.markCovered(batch.getId(), packageRows.stream()
                    .map(row -> String.valueOf(row.getSnapshotId())).distinct().toList(), job.getRequestedBy());
            context.safePoint("COLD_WRITE_COMPLETED", packageRows.size());
        } finally {
            reservations.release(reservation);
        }
    }

    /** 冷卷路径以包 ID 命名，避免 A/B 中相同原始 storage key 相互覆盖。 */
    private long copyOne(ColdBatchEntity batch, StorageVolumeEntity target, OrderPackageEntity orderPackage) throws Exception {
        ArtifactReplicaResolver.ReadableArtifactReplica source = replicas.resolveReadable("ORDER_PACKAGE", orderPackage.getId());
        String targetKey = "cold/" + batch.getBatchNo() + "/packages/" + orderPackage.getId() + ".zip";
        ArtifactCopyEntity existing = copies.selectOne(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getArtifactType, "ORDER_PACKAGE")
                .eq(ArtifactCopyEntity::getArtifactId, orderPackage.getId())
                .eq(ArtifactCopyEntity::getStorageVolumeId, target.getId())
                .eq(ArtifactCopyEntity::getStorageKey, targetKey).last("limit 1"));
        if (existing != null && "ACTIVE".equals(existing.getLifecycle())) return existing.getSize() == null ? 0L : existing.getSize();
        try (InputStream input = store.open(source.storageVolumeId(), source.storageKey())) {
            store.put(target.getId(), targetKey, input);
        }
        Path written = store.resolve(target.getId(), targetKey);
        if (!Files.isRegularFile(written)) throw new IllegalStateException("冷备副本写入后不存在");
        String actualSha256 = sha256(written);
        if (orderPackage.getPackageSha256() != null && !orderPackage.getPackageSha256().isBlank()
                && !orderPackage.getPackageSha256().equalsIgnoreCase(actualSha256)) {
            throw new IllegalStateException("冷备副本校验失败：压缩包摘要不一致");
        }
        ArtifactCopyEntity copy = existing == null ? new ArtifactCopyEntity() : existing;
        copy.setArtifactType("ORDER_PACKAGE"); copy.setArtifactId(orderPackage.getId());
        copy.setLocationType("COLD_BATCH"); copy.setLocationRefId(batch.getId());
        copy.setStorageVolumeId(target.getId()); copy.setStorageKey(targetKey);
        copy.setState("PRESENT"); copy.setLifecycle("ACTIVE"); copy.setSize(Files.size(written));
        copy.setSha256(actualSha256); copy.setLastVerifiedAt(LocalDateTime.now()); copy.setErrorCode(null);
        if (existing == null) copies.insert(copy); else copies.updateById(copy);
        // 批次明细保留来源包、目标卷和校验摘要，供管理员在冷备批次中追溯。
        ColdBatchArtifactEntity batchArtifact = batchArtifacts.selectOne(Wrappers.<ColdBatchArtifactEntity>lambdaQuery()
                .eq(ColdBatchArtifactEntity::getBatchId, batch.getId())
                .eq(ColdBatchArtifactEntity::getArtifactType, "ORDER_PACKAGE")
                .eq(ColdBatchArtifactEntity::getArtifactId, orderPackage.getId()).last("limit 1"));
        if (batchArtifact == null) batchArtifact = new ColdBatchArtifactEntity();
        batchArtifact.setBatchId(batch.getId()); batchArtifact.setStorageVolumeId(target.getId());
        batchArtifact.setArtifactType("ORDER_PACKAGE"); batchArtifact.setArtifactId(orderPackage.getId());
        batchArtifact.setRelativePath(targetKey); batchArtifact.setSize(copy.getSize());
        batchArtifact.setSha256(copy.getSha256()); batchArtifact.setWriteStatus("VERIFIED"); batchArtifact.setFailureMessage(null);
        if (batchArtifact.getId() == null) batchArtifacts.insert(batchArtifact); else batchArtifacts.updateById(batchArtifact);
        return copy.getSize();
    }

    private List<OrderPackageEntity> resolvePackages(List<String> keys) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("冷备订单包不能为空");
        List<OrderPackageEntity> rows = new ArrayList<>();
        for (String key : keys.stream().distinct().sorted().toList()) {
            OrderPackageEntity row = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                    .eq(OrderPackageEntity::getStorageKey, key).eq(OrderPackageEntity::getStatus, "AVAILABLE").last("limit 1"));
            if (row == null || row.getId() == null) throw new IllegalArgumentException("订单包不存在或不可用: " + key);
            rows.add(row);
        }
        return rows;
    }

    private ColdBatchEntity requiredBatch(Long batchId) {
        ColdBatchEntity batch = batchId == null ? null : batches.selectById(batchId);
        if (batch == null) throw new IllegalArgumentException("批次不存在");
        return batch;
    }

    private StorageVolumeEntity requiredWritableColdVolume(Long volumeId) {
        StorageVolumeEntity volume = volumeId == null ? null : volumes.selectById(volumeId);
        if (volume == null || !StorageVolumeStatus.Tier.COLD.name().equals(volume.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())
                || !(StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus())
                || StorageVolumeStatus.Write.STANDBY.name().equals(volume.getWriteStatus()))) {
            throw new IllegalStateException("目标冷备卷不可写入");
        }
        return volume;
    }

    /** 写入冷卷后以实际文件重新计算摘要，防止复制中断或介质异常被误标为可清理。 */
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record Payload(Long batchId, Long targetStorageVolumeId, List<String> orderPackages, String sha256) { }
}
