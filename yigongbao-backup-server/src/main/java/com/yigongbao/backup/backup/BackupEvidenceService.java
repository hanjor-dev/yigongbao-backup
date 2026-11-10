package com.yigongbao.backup.backup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.order.*;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/** 将订单快照、文件资产、业务关系和订单包作为同一个本地证据事务提交。 */
@Service
@RequiredArgsConstructor
public class BackupEvidenceService {
    private final OrderSnapshotMapper snapshots;
    private final AssetMapper assets;
    private final AssetRelationMapper relations;
    private final RelationGapMapper relationGaps;
    private final OrderPackageMapper packages;
    private final ArtifactCopyMapper artifactCopies;
    private final ObjectMapper mapper;

    /** 幂等覆盖同一订单的当前备份证据；外部文件已完整提交后才调用。 */
    @Transactional
    public OrderSnapshotEntity save(Long orderId, BackupManifest manifest, OrderBackupPipeline.Result result) {
        return save(orderId, manifest, result, BackupExecutionMode.REAL, null);
    }

    /** 保存订单包时固化实际写入卷，不能在下载或恢复时重新推导默认路径。 */
    @Transactional
    public OrderSnapshotEntity save(Long orderId, BackupManifest manifest, OrderBackupPipeline.Result result, long storageVolumeId) {
        return save(orderId, manifest, result, BackupExecutionMode.REAL, storageVolumeId);
    }

    /** 保存完整可查询、可下载但不可用于线上恢复的模拟证据。 */
    @Transactional
    public OrderSnapshotEntity saveSimulation(Long orderId, BackupManifest manifest, OrderBackupPipeline.Result result) {
        return save(orderId, manifest, result, BackupExecutionMode.METADATA_SIMULATION, null);
    }

    /** 模拟模式同样保存实际 ZIP 所属卷，确保下载链路与真实模式一致。 */
    @Transactional
    public OrderSnapshotEntity saveSimulation(Long orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                                              long storageVolumeId) {
        return save(orderId, manifest, result, BackupExecutionMode.METADATA_SIMULATION, storageVolumeId);
    }

    private OrderSnapshotEntity save(Long orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                                     BackupExecutionMode mode, Long storageVolumeId) {
        Map<String, Object> rawOrder = manifest.orderRaw() == null ? Map.of() : manifest.orderRaw();
        Map<String, Object> displayOrder = manifest.orderDisplay() == null ? rawOrder : manifest.orderDisplay();
        OrderSnapshotEntity snapshot = snapshots.selectOne(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId).last("limit 1"));
        if (snapshot == null) snapshot = new OrderSnapshotEntity();
        new OrderSnapshotFieldMapper(mapper).apply(snapshot, rawOrder, displayOrder);
        snapshot.setSourceOrderId(orderId);
        snapshot.setBackupMode(mode.name());
        snapshot.setBackupCompleteness(mode == BackupExecutionMode.METADATA_SIMULATION
                ? "NOT_APPLICABLE" : manifest.backupCompleteness());
        snapshot.setManifestSchemaVersion(manifest.manifestSchemaVersion());
        snapshot.setRelationGapCount(mode == BackupExecutionMode.METADATA_SIMULATION ? 0 : manifest.relationGapCount());
        snapshot.setRelationGapHash(mode == BackupExecutionMode.METADATA_SIMULATION ? null : manifest.relationGapHash());
        snapshot.setRelationshipStateHash(mode == BackupExecutionMode.METADATA_SIMULATION
                ? null : manifest.relationshipStateHash());
        if (snapshot.getOrderCode() == null) {
            snapshot.setOrderCode(text(rawOrder, "orderCode", "orderNo", "code", "id"));
        }
        if (snapshot.getOrderCreatedAt() == null) {
            snapshot.setOrderCreatedAt(parseTime(manifest.orderCreatedAt(), rawOrder.get("createTime")));
        }
        snapshot.setOrderRawJson(json(rawOrder));
        snapshot.setDisplayJson(json(displayOrder));
        snapshot.setRelatedBusinessJson(json(manifest.assets()));
        snapshot.setManifestRevision(manifest.manifestRevision());
        snapshot.setManifestSha256(manifest.manifestSha256());
        // 线上凭证尚未接受前不可对外宣称备份完成，回执失败时允许安全重试。
        snapshot.setSnapshotStatus("PENDING_RECEIPT");
        snapshot.setBackupCompletedAt(LocalDateTime.now());
        snapshot.setLatestVerifiedAt(LocalDateTime.now());
        snapshot.setCloudRollup("PRESENT");
        snapshot.setHotRollup("PRESENT");
        snapshot.setColdRollup("ABSENT");
        if (snapshot.getId() == null) snapshots.insert(snapshot); else snapshots.updateById(snapshot);

        java.util.List<AssetEntity> oldAssets = assets.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getSnapshotId, snapshot.getId()));
        for (AssetEntity old : oldAssets) {
            relations.delete(Wrappers.<AssetRelationEntity>lambdaQuery().eq(AssetRelationEntity::getAssetId, old.getId()));
        }
        assets.delete(Wrappers.<AssetEntity>lambdaQuery().eq(AssetEntity::getSnapshotId, snapshot.getId()));
        for (BackupManifest.Asset item : manifest.assets()) saveAsset(snapshot.getId(), item, result, mode);
        relationGaps.delete(Wrappers.<RelationGapEntity>lambdaQuery()
                .eq(RelationGapEntity::getSnapshotId, snapshot.getId()));
        if (mode == BackupExecutionMode.REAL) {
            for (BackupManifest.RelationGap gap : manifest.relationGaps()) saveGap(snapshot.getId(), gap);
        }

        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshot.getId()).last("limit 1"));
        if (pkg == null) pkg = new OrderPackageEntity();
        pkg.setSnapshotId(snapshot.getId());
        // 0 是历史单根目录测试适配器的内部哨兵，不得落入真实的存储卷外键。
        if (storageVolumeId != null && storageVolumeId > 0) pkg.setStorageVolumeId(storageVolumeId);
        pkg.setBackupMode(mode.name());
        pkg.setStorageKey(result.storageKey());
        pkg.setPackageFormat("ORDER_ZIP64_V1");
        pkg.setFileCount(manifest.assets().size());
        pkg.setSourceTotalSize(manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum());
        pkg.setPackageSize(result.packageSize());
        pkg.setPackageSha256(result.packageSha256());
        pkg.setManifestSha256(manifest.manifestSha256());
        pkg.setStatus("PENDING_RECEIPT");
        pkg.setVerifiedAt(LocalDateTime.now());
        if (pkg.getVersion() == null) pkg.setVersion(0);
        if (pkg.getId() == null) packages.insert(pkg); else packages.updateById(pkg);
        if (pkg.getStorageVolumeId() != null && pkg.getStorageVolumeId() > 0) {
            upsertHotCopy(pkg);
        }
        return snapshot;
    }

    /** 订单包主记录保存后，同步登记物理副本，供后续冷热副本选择器统一读取。 */
    private void upsertHotCopy(OrderPackageEntity pkg) {
        ArtifactCopyEntity copy = artifactCopies.selectOne(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getArtifactType, "ORDER_PACKAGE")
                .eq(ArtifactCopyEntity::getArtifactId, pkg.getId())
                .eq(ArtifactCopyEntity::getStorageVolumeId, pkg.getStorageVolumeId())
                .eq(ArtifactCopyEntity::getStorageKey, pkg.getStorageKey())
                .eq(ArtifactCopyEntity::getIsDeleted, 0).last("limit 1"));
        if (copy == null) copy = new ArtifactCopyEntity();
        copy.setArtifactType("ORDER_PACKAGE"); copy.setArtifactId(pkg.getId());
        copy.setLocationType("HOT_NAS"); copy.setLocationRefId(pkg.getId());
        copy.setStorageVolumeId(pkg.getStorageVolumeId()); copy.setStorageKey(pkg.getStorageKey());
        copy.setState("VERIFIED"); copy.setLifecycle("ACTIVE"); copy.setSize(pkg.getPackageSize());
        copy.setSha256(pkg.getPackageSha256()); copy.setLastVerifiedAt(LocalDateTime.now());
        if (copy.getId() == null) artifactCopies.insert(copy); else artifactCopies.updateById(copy);
    }

    /** 发布模拟快照供订单文件页查询下载；该状态不会被恢复流程接受。 */
    @Transactional
    public void markSimulationAvailable(Long orderId) {
        OrderSnapshotEntity snapshot = snapshots.selectOne(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId).last("limit 1"));
        if (snapshot == null) throw new IllegalStateException("待发布模拟订单快照不存在: " + orderId);
        snapshot.setBackupMode(BackupExecutionMode.METADATA_SIMULATION.name());
        snapshot.setSnapshotStatus("SIMULATED");
        snapshot.setLatestVerifiedAt(LocalDateTime.now());
        snapshots.updateById(snapshot);
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshot.getId()).last("limit 1"));
        if (pkg == null) throw new IllegalStateException("待发布模拟订单包不存在: " + orderId);
        pkg.setBackupMode(BackupExecutionMode.METADATA_SIMULATION.name());
        pkg.setStatus("AVAILABLE");
        pkg.setVerifiedAt(LocalDateTime.now());
        packages.updateById(pkg);
    }

    /** 仅在线上回执成功后发布本地快照和订单包。 */
    @Transactional
    public void markAvailable(Long orderId) {
        OrderSnapshotEntity snapshot = snapshots.selectOne(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId).last("limit 1"));
        if (snapshot == null) throw new IllegalStateException("待发布订单快照不存在: " + orderId);
        snapshot.setSnapshotStatus("AVAILABLE");
        snapshot.setLatestVerifiedAt(LocalDateTime.now());
        snapshots.updateById(snapshot);
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshot.getId()).last("limit 1"));
        if (pkg == null) throw new IllegalStateException("待发布订单包不存在: " + orderId);
        pkg.setStatus("AVAILABLE");
        pkg.setVerifiedAt(LocalDateTime.now());
        packages.updateById(pkg);
    }

    private void saveAsset(Long snapshotId, BackupManifest.Asset item, OrderBackupPipeline.Result result,
                           BackupExecutionMode mode) {
        AssetEntity asset = new AssetEntity();
        asset.setSnapshotId(snapshotId);
        asset.setAssetIdentity(item.assetId());
        asset.setSourceFileId(item.sourceFileId());
        asset.setAssetType(item.type());
        asset.setOriginalFilename(item.fileName());
        asset.setExtension(extension(item.fileName()));
        asset.setSourceSize(item.size());
        asset.setSourceSha256(item.sha256());
        asset.setDownloadSha256(result.downloadedHashes().get(item.assetId()));
        asset.setSourcePlatform(string(item.platform(), "COS"));
        // 预签名地址包含短期凭据且会过期，只用于本次下载，不持久化。
        asset.setSourceUrl(null);
        asset.setSourceObjectKey(item.objectKey());
        asset.setSourceVersionId(string(item.versionIdentity(), "*"));
        String entryName = result.packageEntryNames().get(item.assetId());
        asset.setPackageEntryName(entryName == null
                ? "files/" + item.assetId() + "-" + java.nio.file.Paths.get(item.fileName()).getFileName()
                : entryName);
        asset.setBackupStatus(mode == BackupExecutionMode.METADATA_SIMULATION ? "SIMULATED" : "AVAILABLE");
        assets.insert(asset);
        for (Map<String, Object> value : item.relations()) {
            AssetRelationEntity relation = new AssetRelationEntity();
            relation.setAssetId(asset.getId());
            relation.setRelationKind(string(value.get("relationKind"), "UNKNOWN"));
            relation.setSourceRecordId(longValue(value.get("sourceRecordId")));
            relation.setRelationVersion(string(value.get("relationVersion"), "0"));
            relation.setRelationHash(string(value.get("relationHash"), manifestHash(value)));
            relation.setOriginalFileId(longValue(value.get("originalFileId")));
            relation.setOriginalUrl(string(value.get("originalUrl"), null));
            relation.setRelationPayloadJson(json(value));
            relations.insert(relation);
        }
    }

    private void saveGap(Long snapshotId, BackupManifest.RelationGap item) {
        RelationGapEntity gap = new RelationGapEntity();
        gap.setSnapshotId(snapshotId);
        gap.setRelationKind(item.relationKind());
        gap.setSourceRecordId(item.sourceRecordId());
        gap.setRelationOwnerKey(item.relationOwnerKey());
        gap.setRelationValue(item.relationValue());
        gap.setOriginalFileId(item.originalFileId());
        gap.setReasonCode(item.reasonCode());
        gap.setReason(item.reason());
        gap.setRelationHash(item.relationHash());
        relationGaps.insert(gap);
    }

    /** 序列化本地证据中的 JSON 字段。 */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("备份证据序列化失败", ex); }
    }
    /** 计算资产关系的稳定摘要。 */
    private String manifestHash(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("关系摘要计算失败", ex); }
    }
    /** 从线上订单对象中按候选字段读取文本值。 */
    private static String text(Map<String,Object> map, String... keys) {
        for (String key : keys) { Object value = map.get(key); if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value); }
        return null;
    }
    /** 将对象转换为长整型关系标识，非法值按空处理。 */
    private static Long longValue(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
    /** 返回非空文本，否则使用回退值。 */
    private static String string(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    /** 从文件名提取小写扩展名。 */
    private static String extension(String name) { if (name == null) return null; int index = name.lastIndexOf('.'); return index < 0 ? null : name.substring(index + 1).toLowerCase(java.util.Locale.ROOT); }
    /** 兼容线上清单常见的 ISO-8601 和本地日期时间格式。 */
    private static LocalDateTime parseTime(String primary, Object fallback) { String value = primary == null && fallback != null ? String.valueOf(fallback) : primary; if (value == null || value.isBlank()) return null; try { return OffsetDateTime.parse(value).toLocalDateTime(); } catch (Exception ignored) { try { return LocalDateTime.parse(value); } catch (Exception ignoredAgain) { return null; } } }
}
