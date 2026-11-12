package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RemoteHttpSupport;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** 基于线上清理授权接口的客户端；删除只能携带线上签发的 permit。 */
public final class HttpCleanupClient implements CleanupClient {
    private final RemoteHttpSupport http;
    private final ObjectMapper mapper;

    public HttpCleanupClient(RemoteHttpSupport http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public Evaluation evaluate(CleanupRequest request) {
        if (request == null || request.olderThanDays() < 0) {
            throw new IllegalArgumentException("清理周期非法");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "policyVersion", request.olderThanDays(),
                    "mode", request.dryRun() ? "DRY_RUN" : "ACTUAL",
                    "batchSize", request.batchSize());
            String body = mapper.writeValueAsString(payload);
            JsonNode data = http.data("POST", "/internal/cleanup/v1/evaluate", body,
                    stableKey("cleanup-evaluate", body));
            String taskId = text(data, "taskId", "id");
            return new Evaluation(required(taskId, "清理评估响应缺少taskId"), evaluatedObjects(data),
                    longValue(data, "estimatedBytes", "bytes"), text(data, "snapshotHash"), text(data, "objectSetHash"));
        } catch (Exception e) {
            throw translate(e, "线上清理评估失败");
        }
    }

    @Override
    public Permit pollPermit(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("清理任务标识不能为空");
        }
        String digest = sha256(taskId);
        return pollPermit(taskId, new PermitRequest(1, digest, digest, "0", "0", 1, digest));
    }

    /** 提交完整清理证据；线上协议不支持无证据的 GET permit 查询。 */
    @Override
    public Permit pollPermit(String taskId, PermitRequest request) {
        if (taskId == null || taskId.isBlank() || request == null) {
            throw new IllegalArgumentException("清理任务或授权证据不能为空");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "attemptNo", request.attemptNo(),
                    "snapshotHash", request.snapshotHash(),
                    "objectSetHash", request.objectSetHash(),
                    "fencingToken", request.fencingToken(),
                    "receiptId", request.receiptId(),
                    "receiptVersion", request.receiptVersion(),
                    "verificationDigest", request.verificationDigest());
            String body = mapper.writeValueAsString(payload);
            JsonNode data = http.data("POST", "/internal/cleanup/v1/tasks/" + http.encodeSegment(taskId) + "/permits",
                    body, stableKey("cleanup-permit", taskId, Integer.toString(request.attemptNo()),
                            request.snapshotHash(), request.objectSetHash()));
            return new Permit(required(text(data, "permitId", "id"), "清理授权响应缺少permitId"),
                    required(text(data, "taskId"), "清理授权响应缺少taskId"), permitObjects(data),
                    text(data, "status"), instant(text(data, "expiresAt", "expireAt", "expires_at")),
                    text(data, "objectSetHash"));
        } catch (Exception e) {
            throw translate(e, "线上清理授权查询失败");
        }
    }

    @Override
    public void delete(Permit permit, String objectKey) {
        if (permit == null || permit.permitId() == null || permit.taskId() == null
                || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("清理授权或对象标识非法");
        }
        // YAML 没有线上“按对象删除”接口；删除必须交给本地 COS 客户端并携带 permit。
        throw new UnsupportedOperationException("HttpCleanupClient不执行COS对象删除，请注入CosObjectClient");
    }

    private static RuntimeException translate(Exception e, String message) {
        return e instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, e);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
    }

    private static long longValue(JsonNode node, String... names) {
        String value = text(node, names);
        try { return value == null ? 0L : Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IllegalStateException("线上清理字节数非法", e); }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) {
            try { return Instant.ofEpochSecond(Long.parseLong(value)); }
            catch (RuntimeException invalid) { throw new IllegalStateException("线上清理授权过期时间非法", invalid); }
        }
    }

    private static List<String> strings(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode array = node == null ? null : node.get(name);
            if (array != null && array.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode item : array) if (item.isTextual()) result.add(item.asText());
                return List.copyOf(result);
            }
        }
        return List.of();
    }

    private static List<PermitObject> permitObjects(JsonNode data) {
        JsonNode objects = data == null ? null : data.get("objects");
        if (objects != null && objects.isArray()) {
            List<PermitObject> result = new ArrayList<>();
            for (JsonNode item : objects) {
                String key = text(item, "objectKey", "key");
                String version = text(item, "versionIdentity", "versionId", "version");
                if (key != null) result.add(new PermitObject(key, version));
            }
            return List.copyOf(result);
        }
        return strings(data, "objectKeys", "keys").stream().map(key -> new PermitObject(key, null)).toList();
    }

    private static List<EvaluatedObject> evaluatedObjects(JsonNode data) {
        JsonNode objects = data == null ? null : data.get("objects");
        if (objects != null && objects.isArray()) {
            List<EvaluatedObject> result = new ArrayList<>();
            for (JsonNode item : objects) {
                String key = text(item, "objectKey", "key");
                if (key != null) result.add(new EvaluatedObject(
                        text(item, "platform"), text(item, "bucket"), key,
                        text(item, "versionIdentity", "versionId", "version"),
                        nullableLong(item, "sourceFileId"), text(item, "sharedReferenceStatus"),
                        longValue(item, "versionBytes", "bytes")));
            }
            return List.copyOf(result);
        }
        return strings(data, "objectKeys", "keys").stream()
                .map(key -> new EvaluatedObject("COS", "UNKNOWN", key, null, null, "UNVERIFIED", 0L)).toList();
    }

    private static Long nullableLong(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private static String stableKey(String prefix, String... values) {
        return prefix + "-" + sha256(String.join("\n", values == null ? new String[0] : values));
    }
}
