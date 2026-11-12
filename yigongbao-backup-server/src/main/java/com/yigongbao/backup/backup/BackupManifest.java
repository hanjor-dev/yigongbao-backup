package com.yigongbao.backup.backup;

import java.util.List;
import java.util.Map;

/** 订单备份清单，资产列表是订单级整包的唯一输入。 */
public record BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                             String manifestRevision, String manifestSha256, String relationSetHash,
                             int manifestSchemaVersion, String backupCompleteness,
                             List<RelationGap> relationGaps, int relationGapCount,
                             String relationGapHash, String relationshipStateHash,
                             Map<String, Object> orderRaw, Map<String, Object> orderDisplay) {
    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets) {
        this(orderId, orderCreatedAt, assets, null, null, null, Map.of(), Map.of());
    }

    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                          String manifestRevision, String manifestSha256, String relationSetHash) {
        this(orderId, orderCreatedAt, assets, manifestRevision, manifestSha256, relationSetHash,
                Map.of(), Map.of());
    }

    /** 兼容旧调用方：单一订单快照同时作为原始和展示数据。 */
    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                          String manifestRevision, String manifestSha256, String relationSetHash,
                          Map<String, Object> order) {
        this(orderId, orderCreatedAt, assets, manifestRevision, manifestSha256, relationSetHash, order, order);
    }

    /** 历史 v1 构造器：只表示正式完整备份，不补造 v2 关系状态摘要。 */
    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                          String manifestRevision, String manifestSha256, String relationSetHash,
                          Map<String, Object> orderRaw, Map<String, Object> orderDisplay) {
        this(orderId, orderCreatedAt, assets, manifestRevision, manifestSha256, relationSetHash,
                1, "COMPLETE", List.of(), 0, null, null, orderRaw, orderDisplay);
    }

    public BackupManifest {
        assets = List.copyOf(assets == null ? List.of() : assets);
        manifestSchemaVersion = manifestSchemaVersion <= 0 ? 1 : manifestSchemaVersion;
        backupCompleteness = backupCompleteness == null || backupCompleteness.isBlank()
                ? "COMPLETE" : backupCompleteness;
        if (!List.of("COMPLETE", "INCOMPLETE", "NOT_APPLICABLE").contains(backupCompleteness)) {
            throw new IllegalArgumentException("未知备份完整性: " + backupCompleteness);
        }
        relationGaps = List.copyOf(relationGaps == null ? List.of() : relationGaps);
        if (relationGapCount < 0 || relationGapCount != relationGaps.size()) {
            throw new IllegalArgumentException("关系缺口数量与明细不一致");
        }
        // 订单全量业务快照天然包含可空列，Map.copyOf 会因 null 值抛异常。
        orderRaw = immutableNullableMap(orderRaw);
        orderDisplay = immutableNullableMap(orderDisplay == null || orderDisplay.isEmpty() ? orderRaw : orderDisplay);
    }

    /** 兼容既有业务代码；展示场景优先返回完整列表快照。 */
    public Map<String, Object> order() { return orderDisplay; }

    /** 预签名下载地址是短期凭据，只允许存在于下载过程，不得入包或持久化。 */
    public BackupManifest withoutTransientSourceKeys() {
        List<Asset> durableAssets = assets.stream().map(asset -> new Asset(
                asset.assetId(), asset.fileName(), asset.type(), asset.size(), null, asset.sha256(),
                asset.platform(), asset.bucket(), asset.objectKey(), asset.versionIdentity(),
                asset.sourceFileId(), asset.relations())).toList();
        return new BackupManifest(orderId, orderCreatedAt, durableAssets, manifestRevision, manifestSha256,
                relationSetHash, manifestSchemaVersion, backupCompleteness, relationGaps, relationGapCount,
                relationGapHash, relationshipStateHash, orderRaw, orderDisplay);
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                value == null ? Map.of() : value));
    }

    public record RelationGap(String relationKind, String sourceRecordId, String relationOwnerKey,
                              String relationValue, String originalFileId, String reasonCode,
                              String reason, String relationHash) {
    }

    public record Asset(String assetId, String fileName, String type, long size, String sourceKey, String sha256,
                        String platform, String bucket, String objectKey, String versionIdentity,
                        Long sourceFileId, List<Map<String, Object>> relations) {
        public Asset(String assetId, String fileName, String type, long size, String sourceKey, String sha256) {
            this(assetId, fileName, type, size, sourceKey, sha256,
                    "COS", "", null, "*", numericId(assetId), List.of());
        }
        /** 兼容早期未提供摘要的清单构造方式。 */
        public Asset(String assetId, String fileName, String type, long size, String sourceKey) { this(assetId,fileName,type,size,sourceKey,null); }
        public Asset { relations = List.copyOf(relations == null ? List.of() : relations); }

        private static Long numericId(String value) {
            try { return value == null ? null : Long.valueOf(value); }
            catch (NumberFormatException ignored) { return null; }
        }
    }
}
