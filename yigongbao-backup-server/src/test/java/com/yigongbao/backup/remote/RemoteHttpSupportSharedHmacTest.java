package com.yigongbao.backup.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地客户端的简化共享 HMAC 请求头契约。 */
class RemoteHttpSupportSharedHmacTest {
    @Test
    void reportsOnlyStructuredRemoteErrorFieldsForDiagnosis() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 500,
                        "{\"code\":\"INTERNAL_ERROR\",\"message\":\"回执写入失败\",\"requestId\":\"req-42\",\"data\":\"secret=do-not-log\"}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        assertThatThrownBy(() -> support.data("PUT", "/internal/backup/v1/receipts/42", "{}"))
                .hasMessageContaining("HTTP 500", "code=INTERNAL_ERROR", "message=回执写入失败", "requestId=req-42")
                .hasMessageNotContaining("do-not-log");
    }

    @Test
    void preservesContextPathFromConfiguredBaseUrl() {
        String secret = "shared-production-secret-32-bytes!";
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200, "{\"data\":{}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example/api"), Duration.ofSeconds(5),
                secret);

        support.json("GET", "/internal/backup/v1/accounts", "");

        var request = transport.requests.getFirst();
        assertThat(request.uri().getPath())
                .isEqualTo("/api/internal/backup/v1/accounts");
        String canonical = String.join("\n", "GET", "/internal/backup/v1/accounts", "",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                request.headers().firstValue("X-Timestamp").orElseThrow(),
                request.headers().firstValue("X-Nonce").orElseThrow(), "");
        assertThat(request.headers().firstValue("X-Signature")).contains(hmac(secret, canonical));
    }

    @Test
    void signsWithSharedSecretWithoutSendingKeyIdOrScope() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200, "{\"data\":{}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        support.json("POST", "/internal/backup/v1/candidates/search", "{}");

        var request = transport.requests.getFirst();
        assertThat(request.headers().firstValue("X-Signature")).isPresent();
        assertThat(request.headers().firstValue("X-Timestamp")).isPresent();
        assertThat(request.headers().firstValue("X-Nonce")).isPresent();
        assertThat(request.headers().firstValue("X-Key-Id")).isEmpty();
        assertThat(request.headers().firstValue("X-Scope")).isEmpty();
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
    }

    private static String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
