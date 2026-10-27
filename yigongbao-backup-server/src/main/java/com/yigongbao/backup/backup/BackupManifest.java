package com.yigongbao.backup.backup;

import java.util.List;
import java.util.Map;

/** 订单备份清单，资产列表是订单级整包的唯一输入。 */
public record BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                             String manifestRevision, String manifestSha256, String relationSetHash,
                             Map<String, Object> order) {
    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets) {
        this(orderId, orderCreatedAt, assets, null, null, null, Map.of());
    }

    public BackupManifest(String orderId, String orderCreatedAt, List<Asset> assets,
                          String manifestRevision, String manifestSha256, String relationSetHash) {
        this(orderId, orderCreatedAt, assets, manifestRevision, manifestSha256, relationSetHash, Map.of());
    }

    public BackupManifest {
        assets = List.copyOf(assets == null ? List.of() : assets);
        order = Map.copyOf(order == null ? Map.of() : order);
    }

    public record Asset(String assetId, String fileName, String type, long size, String sourceKey, String sha256) {
        /** 兼容早期未提供摘要的清单构造方式。 */
        public Asset(String assetId, String fileName, String type, long size, String sourceKey) { this(assetId,fileName,type,size,sourceKey,null); }
    }
}
