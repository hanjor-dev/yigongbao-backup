package com.yigongbao.backup.backup;

import java.util.List;
import java.util.Map;

/** 订单备份清单，资产列表是订单级整包的唯一输入。 */
public record BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                             String manifestRevision, String manifestSha256, String relationSetHash,
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

    public BackupManifest {
        assets = List.copyOf(assets == null ? List.of() : assets);
        // 订单全量业务快照天然包含可空列，Map.copyOf 会因 null 值抛异常。
        orderRaw = immutableNullableMap(orderRaw);
        orderDisplay = immutableNullableMap(orderDisplay == null || orderDisplay.isEmpty() ? orderRaw : orderDisplay);
    }

    /** 兼容既有业务代码；展示场景优先返回完整列表快照。 */
    public Map<String, Object> order() { return orderDisplay; }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                value == null ? Map.of() : value));
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
