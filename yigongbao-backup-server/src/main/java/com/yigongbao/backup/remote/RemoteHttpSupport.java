package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 线上适配器共用的 HttpClient、超时、认证和错误处理。
 * HMAC 仅签名请求元数据，不输出密钥；流式上传/下载不在内存中缓存大文件。
 */
public final class RemoteHttpSupport {
    private static final int MAX_ERROR_BODY = 512;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final Duration timeout;
    private final String authMode;
    private final String keyId;
    private final byte[] secret;
    private final String bearerToken;
    private final String scope;

    public RemoteHttpSupport(HttpClient client, ObjectMapper mapper, URI baseUri, Duration timeout,
                             String authMode, String keyId, String secret, String bearerToken) {
        this(client, mapper, baseUri, timeout, authMode, keyId, secret, bearerToken, "BACKUP");
    }

    /** 与 backup-internal-v1.yaml 机器认证契约一致的构造器。 */
    public RemoteHttpSupport(HttpClient client, ObjectMapper mapper, URI baseUri, Duration timeout,
                             String authMode, String keyId, String secret, String bearerToken, String scope) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUri = normalizeBase(baseUri);
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30) : timeout;
        this.authMode = authMode == null || authMode.isBlank() ? "NONE" : authMode.trim().toUpperCase(Locale.ROOT);
        this.keyId = keyId == null ? "" : keyId;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.bearerToken = bearerToken == null ? "" : bearerToken;
        this.scope = scope == null || scope.isBlank() ? "BACKUP" : scope.trim();
        if ("HMAC".equals(this.authMode) && this.secret.length == 0) {
            throw new IllegalArgumentException("HMAC认证未配置密钥");
        }
        if ("BEARER".equals(this.authMode) && this.bearerToken.isBlank()) {
            throw new IllegalArgumentException("Bearer认证未配置令牌");
        }
        if (!this.authMode.equals("NONE") && !this.authMode.equals("HMAC") && !this.authMode.equals("BEARER")) {
            throw new IllegalArgumentException("不支持的线上认证模式");
        }
    }

    public HttpResponse<String> json(String method, String path, String body) {
        return json(method, path, body, null);
    }

    /**
     * 发送带稳定幂等键的 JSON 命令。跨网络重试的 commit/permit 必须复用同一个键，
     * 不能让每次重试都产生新的远程命令。
     */
    public HttpResponse<String> json(String method, String path, String body, String idempotencyKey) {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        boolean noBody = "GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
        HttpRequest.Builder builder = request(method, path, noBody ? "" : hex(payload),
                        noBody ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload),
                        idempotencyKey)
                .header("Content-Type", "application/json");
        return send(builder);
    }

    public HttpResponse<String> emptyJson(String method, String path) {
        return json(method, path, "{}");
    }

    public HttpResponse<String> stream(String method, String path, String contentType, InputStream input) {
        HttpRequest.Builder builder = request(method, path, "UNSIGNED-PAYLOAD", HttpRequest.BodyPublishers.ofInputStream(() -> input))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        return send(builder);
    }

    public HttpResponse<InputStream> download(String path) {
        HttpRequest.Builder builder = request("GET", path, "", HttpRequest.BodyPublishers.noBody());
        return sendDownload(builder);
    }

    /** 访问清单返回的 COS 预签名 URL，不附加线上机器认证头。 */
    public HttpResponse<InputStream> download(URI uri) {
        validateExternalUri(uri, "COS下载地址非法");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/octet-stream")
                .GET();
        return sendDownload(builder);
    }

    private HttpResponse<InputStream> sendDownload(HttpRequest.Builder builder) {
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new RemoteCallException("线上服务通信失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallException("线上服务调用被中断", e);
        }
    }

    /** 直接向 COS 预签名上传目标写入流，不把大文件缓存到内存。 */
    public HttpResponse<String> stream(URI uri, String method, String contentType, InputStream input) {
        validateExternalUri(uri, "COS上传地址非法");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.ofInputStream(() -> input))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .header("X-Content-SHA256", "UNSIGNED-PAYLOAD");
        return send(builder);
    }

    /** 对 COS 预签名对象地址执行删除；调用方必须在外层校验 permit。 */
    public HttpResponse<String> delete(URI uri) {
        validateExternalUri(uri, "COS删除地址非法");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.noBody());
        return send(builder);
    }

    public JsonNode data(String method, String path, String body) {
        HttpResponse<String> response = json(method, path, body);
        return data(response);
    }

    public JsonNode data(String method, String path, String body, String idempotencyKey) {
        HttpResponse<String> response = json(method, path, body, idempotencyKey);
        return data(response);
    }

    public JsonNode data(HttpResponse<String> response) {
        ensureSuccess(response);
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            return data == null || data.isMissingNode() || data.isNull() ? root : data;
        } catch (IOException e) {
            throw new RemoteCallException("线上服务返回数据格式非法", response.statusCode(), e);
        }
    }

    public void ensureSuccess(HttpResponse<?> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        Object body = response.body();
        if (body instanceof InputStream input) {
            try (input) {
                input.readNBytes(MAX_ERROR_BODY);
            } catch (IOException ignored) { }
        }
        // 不回显远端响应体，避免远端错误页意外带出 token、签名或其他敏感信息。
        throw new RemoteCallException("线上服务返回HTTP " + response.statusCode(), response.statusCode());
    }

    public String encodeSegment(String value) {
        if (value == null || value.isBlank() || value.contains("..")) {
            throw new IllegalArgumentException("远程路径参数非法");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest.Builder request(String method, String path, String payloadHash, HttpRequest.BodyPublisher publisher) {
        return request(method, path, payloadHash, publisher, null);
    }

    private HttpRequest.Builder request(String method, String path, String payloadHash,
                                       HttpRequest.BodyPublisher publisher, String idempotencyKey) {
        if (path == null || !path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("远程请求路径非法");
        }
        URI uri = baseUri.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .method(method, publisher)
                .header("Accept", "application/json")
                .header("X-Content-SHA256", payloadHash);
        authenticate(builder, method, uri, payloadHash, idempotencyKey);
        return builder;
    }

    private void authenticate(HttpRequest.Builder builder, String method, URI uri, String payloadHash,
                              String requestedIdempotencyKey) {
        if ("BEARER".equals(authMode)) {
            builder.header("Authorization", "Bearer " + bearerToken);
            return;
        }
        if ("HMAC".equals(authMode)) {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String idempotencyKey = "GET".equalsIgnoreCase(method)
                    ? "" : (requestedIdempotencyKey == null || requestedIdempotencyKey.isBlank()
                    ? UUID.randomUUID().toString() : requestedIdempotencyKey);
            builder.header("X-Key-Id", keyId)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Scope", scope)
                    .header("Idempotency-Key", idempotencyKey);
            // 规范形式必须与线上 CanonicalRequest 完全一致，包含 query 和幂等键。
            String canonical = String.join("\n", method.toUpperCase(Locale.ROOT), uri.getRawPath(),
                    canonicalQuery(uri.getRawQuery()), payloadHash, keyId,
                    timestamp, nonce, scope, idempotencyKey);
            builder.header("X-Signature", hmac(canonical));
        } else if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            // 非 HMAC 模式也生成幂等键，避免网络超时重试造成重复命令。
            builder.header("Idempotency-Key", requestedIdempotencyKey == null || requestedIdempotencyKey.isBlank()
                    ? UUID.randomUUID().toString() : requestedIdempotencyKey);
        }
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RemoteCallException("线上认证签名失败", e);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RemoteCallException("线上服务通信失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallException("线上服务调用被中断", e);
        }
    }

    private static URI normalizeBase(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("线上服务地址未配置或非法");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static void validateExternalUri(URI uri, String message) {
        if (uri == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 与线上 CanonicalRequest 相同的 query 排序、解码再编码规则。 */
    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("远程查询参数非法");
            String name = decodeQuery(pair.substring(0, separator));
            String value = decodeQuery(pair.substring(separator + 1));
            parts.add(percentEncode(name) + "=" + percentEncode(value));
        }
        parts.sort(Comparator.naturalOrder());
        return String.join("&", parts);
    }

    private static String decodeQuery(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String percentEncode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int item = current & 0xff;
            if ((item >= 'a' && item <= 'z') || (item >= 'A' && item <= 'Z')
                    || (item >= '0' && item <= '9') || item == '-' || item == '.'
                    || item == '_' || item == '~') {
                result.append((char) item);
            } else {
                result.append('%').append(Character.toUpperCase(Character.forDigit(item >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(item & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new RemoteCallException("请求摘要计算失败", e);
        }
    }

}
