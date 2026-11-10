package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class RemoteHttpSupport {
    private static final int MAX_ERROR_BODY = 512;
    private static final String EMPTY_BODY_HASH = hex(new byte[0]);
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final Duration timeout;
    private final Duration fileTransferTimeout;
    private final byte[] secret;
    private final java.util.function.Supplier<OnlineSettings> dynamicSettings;
    private volatile OnlineSettings cachedSettings;
    private volatile RemoteHttpSupport cachedDelegate;

    /** 生产共享 HMAC 构造器；密钥只保存在进程内存中。 */
    public RemoteHttpSupport(HttpClient client, ObjectMapper mapper, URI baseUri, Duration timeout,
                             String sharedSecret) {
        this(client, mapper, baseUri, timeout, Duration.ofHours(2), sharedSecret);
    }

    /** 普通接口和大文件传输分别使用独立超时，避免慢速文件流被短请求超时中断。 */
    public RemoteHttpSupport(HttpClient client, ObjectMapper mapper, URI baseUri, Duration timeout,
                             Duration fileTransferTimeout, String sharedSecret) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUri = normalizeBase(baseUri);
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30) : timeout;
        this.fileTransferTimeout = fileTransferTimeout == null || fileTransferTimeout.isNegative()
                || fileTransferTimeout.isZero() ? Duration.ofHours(2) : fileTransferTimeout;
        this.secret = sharedSecret == null ? new byte[0] : sharedSecret.getBytes(StandardCharsets.UTF_8);
        this.dynamicSettings = null;
        if (this.secret.length < 32) throw new IllegalArgumentException("共享HMAC密钥不能小于32位");
    }

    /** 数据库驱动构造器；每个远程调用使用当时的配置快照。 */
    public RemoteHttpSupport(ObjectMapper mapper, java.util.function.Supplier<OnlineSettings> dynamicSettings) {
        // 外层对象仅负责按配置构建并缓存真实客户端。不能在应用启动阶段创建无用的
        // HttpClient，否则线上能力关闭时仍会初始化网络 Selector，并可能阻断本地服务启动。
        this.client = null;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUri = URI.create("http://127.0.0.1/");
        this.timeout = Duration.ofSeconds(30);
        this.fileTransferTimeout = Duration.ofHours(2);
        this.secret = new byte[0];
        this.dynamicSettings = Objects.requireNonNull(dynamicSettings, "dynamicSettings");
    }

    public HttpResponse<String> json(String method, String path, String body) {
        return json(method, path, body, null);
    }

    /**
     * 发送带稳定幂等键的 JSON 命令。跨网络重试的 commit/permit 必须复用同一个键，
     * 不能让每次重试都产生新的远程命令。
     */
    public HttpResponse<String> json(String method, String path, String body, String idempotencyKey) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.json(method, path, body, idempotencyKey);
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        boolean noBody = "GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
        HttpRequest.Builder builder = request(method, path, noBody ? EMPTY_BODY_HASH : hex(payload),
                        noBody ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload),
                        idempotencyKey)
                .header("Content-Type", "application/json");
        return send(builder);
    }

    public HttpResponse<String> emptyJson(String method, String path) {
        return json(method, path, "{}");
    }

    public HttpResponse<String> stream(String method, String path, String contentType, InputStream input) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.stream(method, path, contentType, input);
        HttpRequest.Builder builder = request(method, path, "UNSIGNED-PAYLOAD", HttpRequest.BodyPublishers.ofInputStream(() -> input))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        return send(builder);
    }

    public HttpResponse<InputStream> download(String path) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.download(path);
        HttpRequest.Builder builder = request("GET", path, EMPTY_BODY_HASH, HttpRequest.BodyPublishers.noBody());
        return sendDownload(builder);
    }

    /** 访问清单返回的 COS 预签名 URL，不附加线上机器认证头。 */
    public HttpResponse<InputStream> download(URI uri) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.download(uri);
        validateExternalUri(uri, "COS下载地址非法");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(fileTransferTimeout)
                .header("Accept", "application/octet-stream")
                .GET();
        return sendDownload(builder);
    }

    private HttpResponse<InputStream> sendDownload(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            logResponse(request, response.statusCode(), startedAt);
            return response;
        } catch (IOException e) {
            logFailure(request, startedAt, e);
            throw new RemoteCallException("线上服务通信失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailure(request, startedAt, e);
            throw new RemoteCallException("线上服务调用被中断", e);
        }
    }

    /** 直接向 COS 预签名上传目标写入流，不把大文件缓存到内存。 */
    public HttpResponse<String> stream(URI uri, String method, String contentType, InputStream input) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.stream(uri, method, contentType, input);
        validateExternalUri(uri, "COS上传地址非法");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(fileTransferTimeout)
                .method(method, HttpRequest.BodyPublishers.ofInputStream(() -> input))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .header("X-Content-SHA256", "UNSIGNED-PAYLOAD");
        return send(builder);
    }

    /** 对 COS 预签名对象地址执行删除；调用方必须在外层校验 permit。 */
    public HttpResponse<String> delete(URI uri) {
        RemoteHttpSupport dynamic = dynamicDelegate();
        if (dynamic != null) return dynamic.delete(uri);
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
            // 线上系统可能以 HTTP 200 返回统一错误信封。必须在这里识别业务失败，
            // 不能让各业务客户端将空 data 误解析为“缺少字段”的协议错误。
            if (root != null && root.isObject() && root.has("code")
                    && !"OK".equalsIgnoreCase(root.path("code").asText())) {
                throw structuredException("线上服务业务失败", response.statusCode(), false, response.body());
            }
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
        String diagnostic = "";
        boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429;
        if (body instanceof String text) {
            diagnostic = structuredErrorDiagnostic(text);
            retryable = structuredRetryable(text, retryable);
        }
        if (body instanceof InputStream input) {
            try (input) {
                input.readNBytes(MAX_ERROR_BODY);
            } catch (IOException ignored) { }
        }
        // 只提取约定错误 JSON 的 code/message/requestId，不回显原始响应体，避免泄露凭据。
        if (body instanceof String text) {
            throw structuredException("线上服务返回HTTP " + response.statusCode(),
                    response.statusCode(), retryable, text);
        }
        throw new RemoteCallException("线上服务返回HTTP " + response.statusCode() + diagnostic,
                response.statusCode(), retryable);
    }

    private RemoteCallException structuredException(String prefix, int status,
                                                     boolean fallbackRetryable, String body) {
        StructuredError error = structuredError(body);
        boolean retryable = error.retryable() == null ? fallbackRetryable : error.retryable();
        return new RemoteCallException(prefix + error.diagnostic(), status, retryable,
                emptyToNull(error.code()), emptyToNull(error.requestId()), emptyToNull(error.reason()), null);
    }

    private boolean structuredRetryable(String body, boolean fallback) {
        Boolean value = structuredError(body).retryable();
        return value == null ? fallback : value;
    }

    private String structuredErrorDiagnostic(String body) {
        return structuredError(body).diagnostic();
    }

    private StructuredError structuredError(String body) {
        if (body == null || body.isBlank()) return StructuredError.EMPTY;
        try {
            JsonNode root = mapper.readTree(body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) : body);
            if (root == null || !root.isObject()) return StructuredError.EMPTY;
            String code = safeDiagnostic(root.path("code").asText(""));
            String message = safeDiagnostic(root.path("message").asText(""));
            String requestId = safeDiagnostic(root.path("requestId").asText(""));
            String reason = "";
            List<String> values = new ArrayList<>();
            if (!code.isBlank()) values.add("code=" + code);
            if (!message.isBlank()) values.add("message=" + message);
            if (!requestId.isBlank()) values.add("requestId=" + requestId);
            JsonNode details = root.path("details");
            if (details.isArray() && !details.isEmpty()) {
                reason = safeDiagnostic(details.get(0).path("reason").asText(""));
                if (!reason.isBlank()) values.add("reason=" + reason);
            }
            Boolean retryable = root.has("retryable") ? root.path("retryable").asBoolean() : null;
            return new StructuredError(code, requestId, reason, retryable,
                    values.isEmpty() ? "" : " (" + String.join(", ", values) + ")");
        } catch (IOException ignored) {
            return StructuredError.EMPTY;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record StructuredError(String code, String requestId, String reason,
                                   Boolean retryable, String diagnostic) {
        private static final StructuredError EMPTY = new StructuredError("", "", "", null, "");
    }

    private static String safeDiagnostic(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("(?i)(secret|token|credential|authorization)\\s*[=:]\\s*[^,; )]+", "$1=[REDACTED]");
        return sanitized.substring(0, Math.min(sanitized.length(), 160));
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
        // path 是各业务适配器定义的相对接口路径。去掉开头的斜杠后再解析，
        // 可保留线上服务基础地址中的 context-path（例如 /api）。
        URI uri = baseUri.resolve(path.substring(1));
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
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String idempotencyKey = "GET".equalsIgnoreCase(method)
                ? "" : (requestedIdempotencyKey == null || requestedIdempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : requestedIdempotencyKey);
        builder.header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("Idempotency-Key", idempotencyKey);
        // 规范形式必须与线上 CanonicalRequest.createShared 完全一致。
        String canonical = String.join("\n", method.toUpperCase(Locale.ROOT), canonicalPath(uri),
                canonicalQuery(uri.getRawQuery()), payloadHash, timestamp, nonce, idempotencyKey);
        builder.header("X-Signature", hmac(canonical));
    }

    /** 签名路径相对于配置的服务 context-path，与线上 Servlet 验签规则保持一致。 */
    private String canonicalPath(URI uri) {
        String basePath = baseUri.getRawPath();
        String requestPath = uri.getRawPath();
        if (basePath == null || !basePath.endsWith("/") || !requestPath.startsWith(basePath)) {
            throw new IllegalArgumentException("远程请求路径不属于线上服务基础地址");
        }
        return "/" + requestPath.substring(basePath.length());
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
        HttpRequest request = builder.build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            logResponse(request, response.statusCode(), startedAt);
            return response;
        } catch (IOException e) {
            logFailure(request, startedAt, e);
            throw new RemoteCallException("线上服务通信失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailure(request, startedAt, e);
            throw new RemoteCallException("线上服务调用被中断", e);
        }
    }

    /** 仅记录接口路径，不记录查询参数、认证头或响应体。 */
    private void logResponse(HttpRequest request, int statusCode, long startedAt) {
        long durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        if (statusCode >= 200 && statusCode < 300) {
            log.info("远程接口调用完成｜请求类型={}｜请求方法={}｜接口路径={}｜HTTP状态={}｜执行耗时={}毫秒",
                    requestType(request.uri()), request.method(), safeEndpoint(request.uri()), statusCode,
                    durationMillis);
        } else {
            log.warn("远程接口返回异常｜请求类型={}｜请求方法={}｜接口路径={}｜HTTP状态={}｜执行耗时={}毫秒",
                    requestType(request.uri()), request.method(), safeEndpoint(request.uri()), statusCode,
                    durationMillis);
        }
    }

    private void logFailure(HttpRequest request, long startedAt, Exception exception) {
        long durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.error("远程接口通信失败｜请求类型={}｜请求方法={}｜接口路径={}｜执行耗时={}毫秒｜异常类型={}｜异常原因={}",
                requestType(request.uri()), request.method(), safeEndpoint(request.uri()), durationMillis,
                exception.getClass().getSimpleName(), safeDiagnostic(exception.getMessage()),
                safeThrowable(exception));
    }

    private String requestType(URI uri) {
        return isOnlineEndpoint(uri) ? "线上医工宝接口" : "对象存储预签名接口";
    }

    private String safeEndpoint(URI uri) {
        return isOnlineEndpoint(uri) ? uri.getPath() : "外部对象存储地址（已隐藏）";
    }

    private boolean isOnlineEndpoint(URI uri) {
        return Objects.equals(baseUri.getScheme(), uri.getScheme())
                && Objects.equals(baseUri.getHost(), uri.getHost())
                && effectivePort(baseUri) == effectivePort(uri)
                && uri.getPath().startsWith(baseUri.getPath());
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Throwable safeThrowable(Throwable exception) {
        RuntimeException sanitized = new RuntimeException(exception.getClass().getSimpleName()
                + ": " + safeDiagnostic(exception.getMessage()));
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
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

    private RemoteHttpSupport dynamicDelegate() {
        if (dynamicSettings == null) return null;
        OnlineSettings settings = Objects.requireNonNull(dynamicSettings.get(), "线上连接配置为空");
        RemoteHttpSupport current = cachedDelegate;
        if (current != null && settings.equals(cachedSettings)) return current;
        synchronized (this) {
            if (cachedDelegate != null && settings.equals(cachedSettings)) return cachedDelegate;
            HttpClient currentClient = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
            RemoteHttpSupport rebuilt = new RemoteHttpSupport(currentClient, mapper, settings.baseUri(),
                    settings.requestTimeout(), settings.fileTransferTimeout(), settings.sharedSecret());
            cachedSettings = settings;
            cachedDelegate = rebuilt;
            return rebuilt;
        }
    }

    /** 动态线上连接配置，不包含持久化行为。 */
    public record OnlineSettings(URI baseUri, Duration connectTimeout, Duration requestTimeout,
                                 Duration fileTransferTimeout,
                                 String sharedSecret) { }

}
