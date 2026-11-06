package com.yigongbao.backup.restore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RemoteHttpSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于线上备份接口的恢复客户端；接口路径固定为可审计的恢复会话协议。 */
public final class HttpOnlineRestoreClient implements OnlineRestoreClient {
    private static final Logger log = LoggerFactory.getLogger(HttpOnlineRestoreClient.class);
    private final RemoteHttpSupport http;
    private final ObjectMapper mapper;
    private final RestoreEvidenceStore evidenceStore;
    private final Map<String, List<RestoreEvidenceStore.UploadEvidence>> uploaded = new ConcurrentHashMap<>();

    public HttpOnlineRestoreClient(RemoteHttpSupport http, ObjectMapper mapper) {
        this(http, mapper, RestoreEvidenceStore.noOp());
    }

    public HttpOnlineRestoreClient(RemoteHttpSupport http, ObjectMapper mapper, RestoreEvidenceStore evidenceStore) {
        this.http = http;
        this.mapper = mapper;
        this.evidenceStore = evidenceStore == null ? RestoreEvidenceStore.noOp() : evidenceStore;
    }

    @Override
    public Session prepare(String orderId) {
        // 旧抽象没有携带清单摘要，只能生成稳定的兼容请求；正式调用应使用带清单参数的重载。
        String digest = sha256(orderId);
        return prepare(orderId, new PrepareRequest(digest, digest, "REJECT", digest, digest, digest, List.of()));
    }

    /** 使用清单确认阶段生成的不可变摘要调用 YAML 定义的 prepare 请求。 */
    @Override
    public Session prepare(String orderId, OnlineRestoreClient.RestoreRequest request) {
        if (request == null) throw new IllegalArgumentException("恢复准备参数不能为空");
        return prepare(orderId, new PrepareRequest(request.manifestRevision(), request.manifestSha256(),
                request.conflictPolicy(), request.requestDigest(), request.assetSetDigest(), request.relationSetHash(), request.assets()));
    }

    /** 保留具体客户端的请求类型，方便需要显式配置冲突策略的调用方。 */
    public Session prepare(String orderId, PrepareRequest request) {
        if (request == null) throw new IllegalArgumentException("恢复准备参数不能为空");
        try {
            String body = mapper.writeValueAsString(request);
            JsonNode data = http.data("POST", "/internal/restore/v1/orders/" + http.encodeSegment(orderId) + "/prepare", body,
                    stableKey("restore-prepare", orderId, request.requestDigest(), request.manifestSha256()));
            Session session = parseSession(data);
            try {
                evidenceStore.prepared(new RestoreEvidenceStore.PreparedSession(session.id(), orderId,
                        request.manifestRevision(), session.signedPlanDigest(), request.relationSetHash(),
                        request.conflictPolicy()));
            } catch (RuntimeException persistenceFailure) {
                // 本地证据无法落库时不能继续上传；尽力终止线上会话，避免遗留可提交的半成品会话。
                try {
                    http.ensureSuccess(http.json("POST", "/internal/restore/v1/sessions/"
                            + http.encodeSegment(session.id()) + "/abort", "{\"reason\":\"local evidence persistence failed\"}"));
                } catch (RuntimeException abortFailure) {
                    log.warn("恢复证据落库失败且线上会话终止失败, sessionId={}", session.id());
                }
                throw new IllegalStateException("恢复会话证据持久化失败", persistenceFailure);
            }
            return session;
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("线上恢复准备请求构造失败", ex);
        }
    }

    private Session parseSession(JsonNode data) {
        String id = text(data, "sessionId", "id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("线上恢复会话响应缺少sessionId");
        }
        List<String> expected = strings(data, "expectedAssets", "assets", "uploadTargets");
        Map<String, String> targets = uploadTargets(data);
        Map<String, String> objectKeys = uploadObjectKeys(data);
        if (expected.isEmpty() && !targets.isEmpty()) expected = List.copyOf(targets.keySet());
        return new Session(id, expected, targets, objectKeys, text(data, "signedPlanDigest"),
                text(data, "status", "state") == null ? "PREPARED" : text(data, "status", "state"));
    }

    @Override
    public void upload(Session session, String assetName, InputStream data) throws IOException {
        requireSession(session);
        String target = session.uploadTargets().get(assetName);
        if (target == null || target.isBlank()) {
            throw new IOException("恢复计划缺少资产上传目标: " + assetName);
        }
        CountingDigestInputStream tracked = new CountingDigestInputStream(data);
        try {
            HttpResponse<String> response = http.stream(URI.create(target), "PUT", "application/octet-stream", tracked);
            http.ensureSuccess(response);
            // prepare 返回的是预签名 COS URL；提交时必须把每个上传对象的证据一并回传线上。
            long size = tracked.count();
            String digest = tracked.digest();
            String etag = response.headers().firstValue("ETag").orElse("");
            String version = response.headers().firstValue("x-version-id").orElse("");
            RestoreEvidenceStore.UploadEvidence evidence = new RestoreEvidenceStore.UploadEvidence(
                    assetName, plannedObjectKey(session, assetName), version, etag, Math.max(size, 0L), digest);
            evidenceStore.uploaded(session.id(), evidence);
            uploaded.computeIfAbsent(session.id(), ignored -> new ArrayList<>()).add(evidence);
            log.info("线上恢复对象上传完成｜会话ID={}｜资产ID={}｜对象键={}｜容量={}字节｜ETag={}",
                    session.id(), assetName, evidence.objectKey(), evidence.size(), etag);
        } catch (RuntimeException failure) {
            // PUT 超时可能已经在 COS 产生对象；记录不完整证据，abort 后转入孤儿对象人工确认队列。
            RestoreEvidenceStore.UploadEvidence uncertain = new RestoreEvidenceStore.UploadEvidence(
                    assetName, plannedObjectKey(session, assetName), "", "", tracked.count(), safeDigest(tracked));
            try { evidenceStore.uploadFailed(session.id(), uncertain); }
            catch (RuntimeException persistFailure) { log.warn("恢复上传结果未知且证据落库失败, sessionId={}, asset={}", session.id(), assetName); }
            throw failure;
        }
    }

    @Override
    public void commit(Session session) {
        requireSession(session);
        if ("COMMITTED".equalsIgnoreCase(evidenceStore.currentStatus(session.id()))) {
            return;
        }
        try {
            String localStatus = evidenceStore.currentStatus(session.id());
            if ("COMMIT_UNKNOWN".equalsIgnoreCase(localStatus)
                    || "COMMIT_RETRYABLE".equalsIgnoreCase(localStatus)) {
                SessionStatus known;
                try {
                    known = query(session.id());
                } catch (RuntimeException queryFailure) {
                    markStatusBestEffort(session.id(), "COMMIT_UNKNOWN");
                    throw unknown(session.id(), queryFailure);
                }
                if ("COMMITTED".equalsIgnoreCase(known.status())) {
                    markCommitted(session.id());
                    return;
                }
                if (isUnknownStatus(known.status())) {
                    throw unknown(session.id(), new IllegalStateException("线上会话仍处于未知提交状态"));
                }
            }
            List<RestoreEvidenceStore.UploadEvidence> persisted = evidenceStore.uploads(session.id());
            List<RestoreEvidenceStore.UploadEvidence> evidence = persisted == null || persisted.isEmpty()
                    ? uploaded.getOrDefault(session.id(), List.of()) : persisted;
            Map<String, Object> request = Map.of(
                    "signedPlanDigest", session.signedPlanDigest() == null ? "" : session.signedPlanDigest(),
                    "uploads", evidence);
            String body = mapper.writeValueAsString(request);
            try {
                http.ensureSuccess(http.json("POST", "/internal/restore/v1/sessions/"
                        + http.encodeSegment(session.id()) + "/commit", body,
                        stableKey("restore-commit", session.id(), session.signedPlanDigest(), body)));
            } catch (RuntimeException remoteFailure) {
                // commit 的 HTTP 超时、连接断开和部分网关错误都不能直接判定为失败。
                SessionStatus known;
                try {
                    known = query(session.id());
                } catch (RuntimeException queryFailure) {
                    markStatusBestEffort(session.id(), "COMMIT_UNKNOWN");
                    throw unknown(session.id(), remoteFailure);
                }
                if ("COMMITTED".equalsIgnoreCase(known.status())) {
                    markCommitted(session.id());
                    uploaded.remove(session.id());
                    return;
                }
                if (isUnknownStatus(known.status())) {
                    markStatusBestEffort(session.id(), "COMMIT_UNKNOWN");
                    throw unknown(session.id(), remoteFailure);
                }
                markStatusBestEffort(session.id(), "COMMIT_RETRYABLE");
                throw remoteFailure;
            }
            markCommitted(session.id());
            uploaded.remove(session.id());
            log.info("线上恢复会话提交完成｜会话ID={}｜上传对象数={}", session.id(), evidence.size());
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("恢复提交请求构造失败", ex);
        }
    }

    @Override
    public void abort(Session session) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            return;
        }
        String current;
        try { current = evidenceStore.currentStatus(session.id()); }
        catch (RuntimeException ignored) { current = null; }
        if ("COMMITTED".equalsIgnoreCase(current) || "ABORTED".equalsIgnoreCase(current)) {
            return;
        }
        RuntimeException abortFailure = null;
        try {
            http.ensureSuccess(http.json("POST", "/internal/restore/v1/sessions/"
                    + http.encodeSegment(session.id()) + "/abort", "{\"reason\":\"local restore aborted\"}",
                    stableKey("restore-abort", session.id())));
            try {
                evidenceStore.status(session.id(), "ABORTED");
            } catch (RuntimeException persistenceFailure) {
                log.warn("线上恢复已终止但本地会话状态更新失败, sessionId={}", session.id());
            }
        } catch (RuntimeException failure) {
            abortFailure = failure;
            markStatusBestEffort(session.id(), "ABORT_UNKNOWN");
        }
        try { evidenceStore.markOrphans(session.id()); }
        catch (RuntimeException orphanFailure) { log.warn("恢复终止后孤儿对象登记失败, sessionId={}", session.id()); }
        uploaded.remove(session.id());
        if (abortFailure != null) throw abortFailure;
    }

    @Override
    public SessionStatus query(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("恢复会话标识不能为空");
        }
        JsonNode data = http.data("GET", "/internal/restore/v1/sessions/"
                + http.encodeSegment(sessionId), "", null);
        return new SessionStatus(sessionId, text(data, "status", "state") == null
                ? "UNKNOWN" : text(data, "status", "state"));
    }

    private void markCommitted(String sessionId) {
        try { evidenceStore.status(sessionId, "COMMITTED"); }
        catch (RuntimeException persistenceFailure) {
            log.warn("线上恢复已提交但本地会话状态更新失败, sessionId={}", sessionId);
        }
    }

    private void markStatusBestEffort(String sessionId, String status) {
        try { evidenceStore.status(sessionId, status); }
        catch (RuntimeException persistenceFailure) {
            log.warn("恢复会话状态持久化失败, sessionId={}, status={}", sessionId, status);
        }
    }

    private static boolean isUnknownStatus(String status) {
        return status == null || status.isBlank() || "UNKNOWN".equalsIgnoreCase(status)
                || "COMMITTING".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status);
    }

    private static CommitOutcomeUnknownException unknown(String sessionId, Throwable failure) {
        return new CommitOutcomeUnknownException(sessionId, failure);
    }

    private static String stableKey(String prefix, String... parts) {
        String value = String.join("\n", parts == null ? new String[0] : parts);
        return prefix + "-" + sha256(value);
    }

    private static String safeDigest(CountingDigestInputStream tracked) {
        try { return tracked.digest(); } catch (RuntimeException ignored) { return ""; }
    }

    private static void requireSession(Session session) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            throw new IllegalArgumentException("恢复会话非法");
        }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private static List<String> strings(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode array = node == null ? null : node.get(name);
            if (array != null && array.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode item : array) {
                    if (item.isTextual()) {
                        result.add(item.asText());
                    } else if (item.isObject()) {
                        String itemName = text(item, "assetIdentity", "assetId", "fileName", "filename");
                        if (itemName != null) result.add(itemName);
                    }
                }
                return List.copyOf(result);
            }
        }
        return List.of();
    }

    private static Map<String, String> uploadTargets(JsonNode data) {
        JsonNode array = data == null ? null : data.get("uploadTargets");
        if (array == null || !array.isArray()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode target : array) {
            if (!target.isObject()) continue;
            String asset = text(target, "assetIdentity", "assetId", "fileName", "filename");
            String url = text(target, "url", "uploadUrl", "signedUrl");
            if (asset != null && url != null && !url.isBlank()) result.put(asset, url);
        }
        return Map.copyOf(result);
    }

    /** 解析由线上恢复计划显式给出的对象键，杜绝 COS endpoint/bucket 前缀导致的 URL 反推歧义。 */
    private static Map<String, String> uploadObjectKeys(JsonNode data) {
        JsonNode array = data == null ? null : data.get("uploadTargets");
        if (array == null || !array.isArray()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode target : array) {
            if (!target.isObject()) continue;
            String asset = text(target, "assetIdentity", "assetId", "fileName", "filename");
            String objectKey = text(target, "objectKey");
            if (asset != null && objectKey != null && !objectKey.isBlank()) result.put(asset, objectKey);
        }
        return Map.copyOf(result);
    }

    private static String plannedObjectKey(Session session, String assetName) throws IOException {
        String objectKey = session.uploadObjectKeys().get(assetName);
        if (objectKey == null || objectKey.isBlank()) {
            throw new IOException("线上恢复计划缺少对象键: " + assetName);
        }
        return objectKey;
    }

    public record PrepareRequest(String manifestRevision, String manifestSha256, String conflictPolicy,
                                 String requestDigest, String assetSetDigest, String relationSetHash,
                                 List<OnlineRestoreClient.RestoreAsset> assets) {}

    private static final class CountingDigestInputStream extends InputStream {
        private final InputStream delegate;
        private final MessageDigest digest;
        private long count;

        private CountingDigestInputStream(InputStream delegate) throws IOException {
            this.delegate = delegate;
            try { this.digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception ex) { throw new IOException("SHA-256不可用", ex); }
        }

        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) { digest.update((byte) value); count++; }
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) { digest.update(bytes, offset, read); count += read; }
            return read;
        }

        long count() { return count; }
        String digest() { return HexFormat.of().formatHex(digest.digest()); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }
}
