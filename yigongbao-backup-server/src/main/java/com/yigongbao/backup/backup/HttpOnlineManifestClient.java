package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RemoteCallException;
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
        return fetchAt(orderId, "/internal/backup/v1/orders/" + http.encodeSegment(orderId) + "/manifest", false);
    }

    @Override
    public BackupManifest fetchDegraded(String orderId) {
        return fetchAt(orderId, "/internal/backup/v1/orders/" + http.encodeSegment(orderId) + "/manifest/degraded", true);
    }

    private BackupManifest fetchAt(String orderId, String path, boolean degraded) {
        JsonNode data = http.data("GET", path, "{}");
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
            List<BackupManifest.RelationGap> gaps = new ArrayList<>();
            JsonNode gapItems = data == null ? null : data.get("relationGaps");
            if (gapItems != null && gapItems.isArray()) {
                for (JsonNode gap : gapItems) {
                    gaps.add(new BackupManifest.RelationGap(
                            text(gap, "relationKind"), text(gap, "sourceRecordId"),
                            text(gap, "relationOwnerKey"), text(gap, "relationValue"),
                            text(gap, "originalFileId"), text(gap, "reasonCode"),
                            text(gap, "reason"), text(gap, "relationHash")));
                }
            }
            int schemaVersion = data == null ? 1 : data.path("manifestSchemaVersion").asInt(1);
            String completeness = text(data, "backupCompleteness");
            if (completeness == null) completeness = "COMPLETE";
            int gapCount = data != null && data.has("relationGapCount")
                    ? data.path("relationGapCount").asInt() : gaps.size();
            java.util.Map<String, Object> rawSnapshot = rawOrder == null
                    ? java.util.Map.of() : mapper.convertValue(rawOrder, java.util.Map.class);
            java.util.Map<String, Object> displaySnapshot = displayOrder == null
                    ? rawSnapshot : mapper.convertValue(displayOrder, java.util.Map.class);
            BackupManifest manifest = new BackupManifest(id == null ? orderId : id, created, List.copyOf(assets),
                    revision, manifestSha, relationDigest == null ? relationSetHash(items) : relationDigest,
                    schemaVersion, completeness, gaps, gapCount, text(data, "relationGapHash"),
                    text(data, "relationshipStateHash"), rawSnapshot, displaySnapshot);
            if (degraded) requireDegradedV2(manifest);
            return manifest;
        } catch (RemoteCallException error) {
            throw error;
        } catch (RuntimeException e) {
            throw new IllegalStateException("线上订单清单数据非法", e);
        }
    }

    private static void requireDegradedV2(BackupManifest manifest) {
        boolean valid = manifest.manifestSchemaVersion() == 2
                && "INCOMPLETE".equals(manifest.backupCompleteness())
                && manifest.relationGapCount() > 0
                && manifest.relationGaps().size() == manifest.relationGapCount()
                && sha256(manifest.relationGapHash())
                && sha256(manifest.relationshipStateHash());
        if (!valid) {
            throw new RemoteCallException("降级备份协议版本不兼容", 409, false,
                    "VERSION_INCOMPATIBLE", null,
                    "降级清单必须为 v2 INCOMPLETE 并包含完整关系缺口摘要", null);
        }
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    @Override
    public void confirm(String orderId, BackupManifest manifest) {
        String body;
        try {
            java.util.Map<String, Object> command = new java.util.LinkedHashMap<>();
            command.put("manifestJcsSha256", manifest.manifestSha256());
            if ("INCOMPLETE".equals(manifest.backupCompleteness())) {
                command.put("relationshipStateHash", manifest.relationshipStateHash());
            } else {
                command.put("objectIdentityDigest", manifest.relationSetHash());
            }
            body = mapper.writeValueAsString(command);
        } catch (Exception ex) {
            throw new IllegalStateException("清单确认参数序列化失败", ex);
        }
        String mode = "INCOMPLETE".equals(manifest.backupCompleteness()) ? "/manifest/degraded/" : "/manifest/";
        http.data("POST", "/internal/backup/v1/orders/" + http.encodeSegment(orderId)
                + mode + http.encodeSegment(manifest.manifestRevision()) + "/confirm", body);
    }

    @Override
    public void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                              String idempotencyKey, int receiptVersion) {
        submitReceipt(orderId, manifest, result, idempotencyKey, receiptVersion, java.time.Instant.now());
    }

    @Override
    public void submitReceipt(String orderId, BackupManifest manifest, OrderBackupPipeline.Result result,
                              String idempotencyKey, int receiptVersion, java.time.Instant completedAt) {
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
            java.util.Map<String, Object> receipt = new java.util.LinkedHashMap<>();
            receipt.put("manifestRevision", manifest.manifestRevision());
            receipt.put("manifestSha256", manifest.manifestSha256());
            receipt.put("packageSha256", result.packageSha256());
            receipt.put("fileCount", manifest.assets().size());
            receipt.put("totalSize", sourceSize);
            receipt.put("backupCompleteness", manifest.backupCompleteness());
            receipt.put("relationGapCount", manifest.relationGapCount());
            receipt.put("relationGapHash", manifest.relationGapHash());
            receipt.put("relationshipStateHash", manifest.relationshipStateHash());
            if (completedAt == null) throw new IllegalArgumentException("备份凭证完成时间不能为空");
            receipt.put("completedAt", completedAt.toString());
            receipt.put("objects", objects);
            String body = mapper.writeValueAsString(receipt);
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
