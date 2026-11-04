package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RemoteHttpSupport;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/** 通过线上清单接口读取正式订单及其资产元数据。 */
public final class HttpOnlineManifestClient implements OnlineManifestClient {
    private final RemoteHttpSupport http;
    private final ObjectMapper mapper;

    public HttpOnlineManifestClient(RemoteHttpSupport http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public BackupManifest fetch(String orderId) {
        JsonNode data = http.data("GET", "/internal/backup/v1/orders/" + http.encodeSegment(orderId) + "/manifest", "{}");
        try {
            // YAML 中订单业务快照位于 data.order，不能再从旧版扁平字段读取。
            JsonNode legacyOrder = data == null ? null : data.get("order");
            JsonNode rawOrder = data != null && data.has("orderRaw") ? data.get("orderRaw") : legacyOrder;
            JsonNode displayOrder = data != null && data.has("orderDisplay") ? data.get("orderDisplay") : legacyOrder;
            String id = text(data, "orderId", "sourceOrderId");
            if (id == null) id = text(rawOrder, "id", "orderId");
            String created = text(data, "orderCreatedAt", "createdAt");
            if (created == null) created = text(rawOrder, "createdAt", "createTime", "orderCreatedAt");
            List<BackupManifest.Asset> assets = new ArrayList<>();
            JsonNode items = data == null ? null : data.get("assets");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode source = item.get("sourceObject");
                    String sourceKey = text(source, "url", "objectKey", "sourceKey");
                    if (sourceKey == null) sourceKey = text(item, "sourceKey", "objectKey");
                    List<java.util.Map<String, Object>> relations = item.has("relations")
                            ? mapper.convertValue(item.get("relations"), java.util.List.class) : java.util.List.of();
                    String assetIdentity = text(item, "assetIdentity", "assetId", "id");
                    assets.add(new BackupManifest.Asset(assetIdentity,
                            text(item, "originalFilename", "fileName", "filename"),
                            text(item, "assetType", "type"), item.path("size").asLong(),
                            sourceKey, text(item, "sha256"), text(source, "platform"),
                            text(source, "bucket"), text(source, "objectKey"),
                            text(source, "versionIdentity"), longValue(item, "sourceFileId"), relations));
                }
            }
            String revision = text(data, "manifestRevision", "revision");
            String manifestSha = text(data, "manifestJcsSha256", "manifestSha256");
            String relationDigest = text(data, "objectIdentityDigest");
            java.util.Map<String, Object> rawSnapshot = rawOrder == null
                    ? java.util.Map.of() : mapper.convertValue(rawOrder, java.util.Map.class);
            java.util.Map<String, Object> displaySnapshot = displayOrder == null
                    ? rawSnapshot : mapper.convertValue(displayOrder, java.util.Map.class);
            return new BackupManifest(id == null ? orderId : id, created, List.copyOf(assets),
                    revision, manifestSha, relationDigest == null ? relationSetHash(items) : relationDigest,
                    rawSnapshot, displaySnapshot);
        } catch (RuntimeException e) {
            throw new IllegalStateException("线上订单清单数据非法", e);
        }
    }

    @Override
    public void confirm(String orderId, BackupManifest manifest) {
        String body;
        try {
            body = mapper.writeValueAsString(java.util.Map.of(
                    "manifestJcsSha256", manifest.manifestSha256(),
                    "objectIdentityDigest", manifest.relationSetHash()));
        } catch (Exception ex) {
            throw new IllegalStateException("清单确认参数序列化失败", ex);
        }
        http.data("POST", "/internal/backup/v1/orders/" + http.encodeSegment(orderId)
                + "/manifest/" + http.encodeSegment(manifest.manifestRevision()) + "/confirm", body);
    }

    @Override
    public void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                              String idempotencyKey, int receiptVersion) {
        try {
            long sourceSize = manifest.assets().stream().mapToLong(BackupManifest.Asset::size).sum();
            java.util.List<java.util.Map<String, Object>> objects = manifest.assets().stream().map(asset -> {
                java.util.Map<String, Object> object = new java.util.LinkedHashMap<>();
                object.put("assetIdentity", asset.assetId());
                object.put("fileId", asset.sourceFileId());
                object.put("assetType", asset.type());
                object.put("platform", valueOr(asset.platform(), "COS"));
                object.put("bucket", valueOr(asset.bucket(), ""));
                object.put("objectKey", asset.objectKey());
                object.put("versionIdentity", valueOr(asset.versionIdentity(), "*"));
                object.put("sizeBytes", asset.size());
                object.put("sha256", result.downloadedHashes().get(asset.assetId()));
                return object;
            }).toList();
            String body = mapper.writeValueAsString(java.util.Map.ofEntries(
                    java.util.Map.entry("manifestRevision", manifest.manifestRevision()),
                    java.util.Map.entry("manifestSha256", manifest.manifestSha256()),
                    java.util.Map.entry("packageSha256", result.packageSha256()),
                    java.util.Map.entry("fileCount", manifest.assets().size()),
                    java.util.Map.entry("totalSize", sourceSize),
                    java.util.Map.entry("completedAt", java.time.OffsetDateTime.now().toString()),
                    java.util.Map.entry("objects", objects)));
            http.data("PUT", "/internal/backup/v1/receipts/" + http.encodeSegment(orderId), body, idempotencyKey);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("备份凭证序列化失败", ex);
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
    }

    private static Long longValue(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.asLong();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String relationSetHash(JsonNode items) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> relations = new ArrayList<>();
            if (items != null && items.isArray()) {
                for (JsonNode item : items) relations.add(item.path("relations"));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(relations)));
        } catch (Exception ex) {
            throw new IllegalStateException("线上关系摘要计算失败", ex);
        }
    }
}
