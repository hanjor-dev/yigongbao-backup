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
            JsonNode order = data == null ? null : data.get("order");
            String id = text(data, "orderId", "sourceOrderId");
            if (id == null) id = text(order, "id", "orderId");
            String created = text(data, "orderCreatedAt", "createdAt");
            if (created == null) created = text(order, "createdAt", "createTime", "orderCreatedAt");
            List<BackupManifest.Asset> assets = new ArrayList<>();
            JsonNode items = data == null ? null : data.get("assets");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode source = item.get("sourceObject");
                    String sourceKey = text(source, "url", "objectKey", "sourceKey");
                    if (sourceKey == null) sourceKey = text(item, "sourceKey", "objectKey");
                    assets.add(new BackupManifest.Asset(text(item, "assetIdentity", "assetId", "id"),
                            text(item, "originalFilename", "fileName", "filename"),
                            text(item, "assetType", "type"), item.path("size").asLong(),
                            sourceKey, text(item, "sha256")));
                }
            }
            String revision = text(data, "manifestRevision", "revision");
            String manifestSha = text(data, "manifestJcsSha256", "manifestSha256");
            java.util.Map<String, Object> orderSnapshot = data != null && data.has("order")
                    ? mapper.convertValue(data.get("order"), java.util.Map.class) : java.util.Map.of();
            return new BackupManifest(id == null ? orderId : id, created, List.copyOf(assets),
                    revision, manifestSha, relationSetHash(items), orderSnapshot);
        } catch (RuntimeException e) {
            throw new IllegalStateException("线上订单清单数据非法", e);
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
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
