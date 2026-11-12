package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 共享的“本地暂存 -> 线上回执 -> 本地发布”状态机。 */
@Service
@RequiredArgsConstructor
public class BackupStageService {
    public static final String STAGED = "STAGED";
    public static final String RECEIPT_ACCEPTED = "RECEIPT_ACCEPTED";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String SUPERSEDED = "SUPERSEDED";

    private final BackupStageMapper stages;
    private final BackupEvidenceService evidence;
    private final ObjectMapper mapper;

    @Transactional
    public BackupStageEntity stage(Long jobId, Long orderId, BackupManifest manifest,
                                   OrderBackupPipeline.Result result, Long storageVolumeId,
                                   String idempotencyKey) {
        BackupStageEntity existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) return requireSameArtifact(existing, orderId, manifest, result);
        BackupStageEntity row = new BackupStageEntity();
        row.setJobId(jobId);
        row.setSourceOrderId(orderId);
        row.setIdempotencyKey(idempotencyKey);
        row.setManifestJson(json(manifest));
        row.setResultJson(json(result));
        row.setManifestSha256(manifest.manifestSha256());
        row.setPackageSha256(result.packageSha256());
        row.setPackageSize(result.packageSize());
        row.setStorageVolumeId(storageVolumeId != null && storageVolumeId > 0 ? storageVolumeId : null);
        row.setStorageKey(result.storageKey());
        row.setBackupCompleteness(manifest.backupCompleteness());
        row.setStageStatus(STAGED);
        row.setReceiptCompletedAt(java.time.Instant.now());
        row.setVersion(0);
        try {
            stages.insert(row);
            return row;
        } catch (DuplicateKeyException duplicate) {
            BackupStageEntity raced = findByIdempotencyKey(idempotencyKey);
            if (raced == null) throw duplicate;
            return requireSameArtifact(raced, orderId, manifest, result);
        }
    }

    public BackupStageEntity findByIdempotencyKey(String idempotencyKey) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
    }

    public BackupStageEntity findByJobOrder(Long jobId, Long orderId) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getJobId, jobId)
                .eq(BackupStageEntity::getSourceOrderId, orderId)
                .orderByDesc(BackupStageEntity::getId)
                .last("limit 1"));
    }

    /** 按清单完整性恢复当前模式自己的暂存制品，避免严格与降级制品串用。 */
    public BackupStageEntity findResumableByJobOrder(Long jobId, Long orderId, String backupCompleteness) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getJobId, jobId)
                .eq(BackupStageEntity::getSourceOrderId, orderId)
                .eq(BackupStageEntity::getBackupCompleteness, backupCompleteness)
                .in(BackupStageEntity::getStageStatus, STAGED, RECEIPT_ACCEPTED)
                .orderByDesc(BackupStageEntity::getId)
                .last("limit 1"));
    }

    /** 查找本地已经发布的权威终态，用于远端并发凭证冲突后的安全对账。 */
    public BackupStageEntity findAvailableByOrder(Long orderId, String backupCompleteness) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getSourceOrderId, orderId)
                .eq(BackupStageEntity::getBackupCompleteness, backupCompleteness)
                .eq(BackupStageEntity::getStageStatus, AVAILABLE)
                .isNotNull(BackupStageEntity::getPublishedSnapshotId)
                .orderByDesc(BackupStageEntity::getId)
                .last("limit 1"));
    }

    /** 查找订单最新的任意完整性权威终态，用于跨模式并发对账。 */
    public BackupStageEntity findAvailableByOrder(Long orderId) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getSourceOrderId, orderId)
                .eq(BackupStageEntity::getStageStatus, AVAILABLE)
                .isNotNull(BackupStageEntity::getPublishedSnapshotId)
                .orderByDesc(BackupStageEntity::getId)
                .last("limit 1"));
    }

    /** 进程在发布后、回写任务订单前退出时，直接恢复同任务同模式的终态。 */
    public BackupStageEntity findAvailableByJobOrder(Long jobId, Long orderId, String backupCompleteness) {
        return stages.selectOne(Wrappers.<BackupStageEntity>lambdaQuery()
                .eq(BackupStageEntity::getJobId, jobId)
                .eq(BackupStageEntity::getSourceOrderId, orderId)
                .eq(BackupStageEntity::getBackupCompleteness, backupCompleteness)
                .eq(BackupStageEntity::getStageStatus, AVAILABLE)
                .isNotNull(BackupStageEntity::getPublishedSnapshotId)
                .orderByDesc(BackupStageEntity::getId)
                .last("limit 1"));
    }

    @Transactional
    public void markReceiptAccepted(Long stageId) {
        BackupStageEntity row = required(stageId);
        if (AVAILABLE.equals(row.getStageStatus()) || RECEIPT_ACCEPTED.equals(row.getStageStatus())) return;
        requireStatus(row, STAGED);
        row.setStageStatus(RECEIPT_ACCEPTED);
        row.setReceiptAcceptedAt(LocalDateTime.now());
        stages.updateById(row);
    }

    /** 在一个本地事务中物化全部证据并把暂存记录切换为 AVAILABLE。 */
    @Transactional
    public OrderSnapshotEntity publish(Long stageId) {
        BackupStageEntity row = required(stageId);
        if (AVAILABLE.equals(row.getStageStatus())) {
            return requirePublishedSnapshot(row);
        }
        requireStatus(row, RECEIPT_ACCEPTED);
        Material material = material(row);
        OrderSnapshotEntity snapshot = row.getStorageVolumeId() == null
                ? evidence.save(row.getSourceOrderId(), material.manifest(), material.result())
                : evidence.save(row.getSourceOrderId(), material.manifest(), material.result(), row.getStorageVolumeId());
        evidence.markAvailable(row.getSourceOrderId());
        row.setPublishedSnapshotId(snapshot.getId());
        row.setStageStatus(AVAILABLE);
        row.setPublishedAt(LocalDateTime.now());
        stages.updateById(row);
        return snapshot;
    }

    /** 验证 AVAILABLE 暂存记录确实对应同一订单、同一完整性模式的权威可用快照。 */
    public OrderSnapshotEntity requirePublishedSnapshot(BackupStageEntity row) {
        if (row == null || !AVAILABLE.equals(row.getStageStatus()) || row.getPublishedSnapshotId() == null) {
            throw new IllegalStateException("已发布暂存记录缺少权威快照");
        }
        OrderSnapshotEntity snapshot = evidence.findSnapshot(row.getPublishedSnapshotId());
        if (snapshot == null
                || !Objects.equals(snapshot.getId(), row.getPublishedSnapshotId())
                || !Objects.equals(snapshot.getSourceOrderId(), row.getSourceOrderId())
                || !Objects.equals(snapshot.getBackupCompleteness(), row.getBackupCompleteness())
                || !java.util.Set.of("AVAILABLE", "SUCCESS", "BACKED_UP").contains(snapshot.getSnapshotStatus())) {
            throw new IllegalStateException("已发布暂存记录对应的权威快照不存在或状态不一致: " + row.getId());
        }
        return snapshot;
    }

    @Transactional
    public void supersede(Long stageId) {
        BackupStageEntity row = required(stageId);
        if (SUPERSEDED.equals(row.getStageStatus())) return;
        if (AVAILABLE.equals(row.getStageStatus())) {
            throw new IllegalStateException("已发布的暂存记录不能标记为 SUPERSEDED: " + stageId);
        }
        row.setStageStatus(SUPERSEDED);
        row.setSupersededAt(LocalDateTime.now());
        row.setCleanupStatus("PENDING");
        row.setCleanupRetryCount(0);
        row.setCleanupNextAt(LocalDateTime.now());
        row.setCleanupError(null);
        stages.updateById(row);
    }

    public Material material(BackupStageEntity row) {
        try {
            return new Material(mapper.readValue(row.getManifestJson(), BackupManifest.class),
                    mapper.readValue(row.getResultJson(), OrderBackupPipeline.Result.class));
        } catch (Exception error) {
            throw new IllegalStateException("备份暂存证据反序列化失败: " + row.getId(), error);
        }
    }

    private BackupStageEntity required(Long id) {
        BackupStageEntity row = stages.selectById(id);
        if (row == null) throw new IllegalStateException("备份暂存记录不存在: " + id);
        return row;
    }

    private static void requireStatus(BackupStageEntity row, String expected) {
        if (!expected.equals(row.getStageStatus())) {
            throw new IllegalStateException("备份暂存状态非法: " + row.getStageStatus() + "，期望 " + expected);
        }
    }

    private static BackupStageEntity requireSameArtifact(BackupStageEntity row, Long orderId,
                                                          BackupManifest manifest, OrderBackupPipeline.Result result) {
        if (!Objects.equals(row.getSourceOrderId(), orderId)
                || !Objects.equals(row.getManifestSha256(), manifest.manifestSha256())
                || !Objects.equals(row.getPackageSha256(), result.packageSha256())
                || !Objects.equals(row.getStorageKey(), result.storageKey())) {
            throw new IllegalStateException("相同幂等键对应的备份制品摘要不一致: " + row.getIdempotencyKey());
        }
        return row;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("备份暂存证据序列化失败", error); }
    }

    public record Material(BackupManifest manifest, OrderBackupPipeline.Result result) { }
}
