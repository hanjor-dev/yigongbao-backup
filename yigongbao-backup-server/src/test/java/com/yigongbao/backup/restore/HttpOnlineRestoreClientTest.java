package com.yigongbao.backup.restore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOnlineRestoreClientTest {
    @Test
    void restoreProtocolStreamsAssetAndCommits() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            String path = request.uri().getPath();
            String body = path.endsWith("/prepare")
                    ? "{\"data\":{\"sessionId\":\"session-1\",\"signedPlanDigest\":\"plan\",\"uploadTargets\":[{\"assetIdentity\":\"a.txt\",\"url\":\"https://cos.example/upload/a.txt\"}]}}"
                    : "{\"code\":\"OK\",\"data\":{}}";
            return RecordingHttpClient.response(request, 200, body);
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5),
                "shared-production-secret-32-bytes!");
        HttpOnlineRestoreClient client = new HttpOnlineRestoreClient(support, new ObjectMapper());

        OnlineRestoreClient.Session session = client.prepare("order-1");
        client.upload(session, "a.txt", new java.io.ByteArrayInputStream(new byte[]{1, 2}));
        client.commit(session);

        assertThat(session.id()).isEqualTo("session-1");
        assertThat(session.expectedAssets()).containsExactly("a.txt");
        assertThat(transport.requests).extracting(request -> request.uri().getPath()).containsExactly(
                "/internal/restore/v1/orders/order-1/prepare",
                "/upload/a.txt",
                "/internal/restore/v1/sessions/session-1/commit");
        assertThat(transport.requests.get(0).headers().firstValue("X-Signature")).isPresent();
        assertThat(transport.requests.get(0).headers().firstValue("X-Signature").orElseThrow())
                .doesNotContain("do-not-log");
        assertThat(transport.requests.get(0).headers().firstValue("Idempotency-Key")).isPresent();
    }

    @Test
    void commitTimeoutQueriesAuthoritativeStatusAndDoesNotAbort() {
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/prepare")) {
                return RecordingHttpClient.response(request, 200,
                        "{\"data\":{\"sessionId\":\"session-unknown\",\"expectedAssets\":[\"a\"],\"uploadTargets\":[]}}");
            }
            if (path.endsWith("/commit")) return RecordingHttpClient.response(request, 504, "{}");
            if (path.endsWith("/sessions/session-unknown")) {
                return RecordingHttpClient.response(request, 200, "{\"data\":{\"status\":\"COMMITTING\"}}");
            }
            return RecordingHttpClient.response(request, 200, "{\"data\":{}}");
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        HttpOnlineRestoreClient client = new HttpOnlineRestoreClient(support, new ObjectMapper());
        OnlineRestoreClient.Session session = client.prepare("order-unknown");

        assertThatThrownBy(() -> client.commit(session)).isInstanceOf(CommitOutcomeUnknownException.class);
        assertThat(transport.requests).extracting(request -> request.uri().getPath()).containsExactly(
                "/internal/restore/v1/orders/order-unknown/prepare",
                "/internal/restore/v1/sessions/session-unknown/commit",
                "/internal/restore/v1/sessions/session-unknown");
    }

    @Test
    void abortMarksPartiallyUploadedSessionForOrphanReconciliation() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean marked = new java.util.concurrent.atomic.AtomicBoolean();
        RestoreEvidenceStore store = new RestoreEvidenceStore() {
            public void prepared(PreparedSession session) { }
            public void uploaded(String sessionId, UploadEvidence evidence) { }
            public java.util.List<UploadEvidence> uploads(String sessionId) { return java.util.List.of(); }
            public void status(String sessionId, String status) { }
            public void markOrphans(String sessionId) { marked.set(true); }
        };
        RecordingHttpClient transport = new RecordingHttpClient(request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/prepare")) return RecordingHttpClient.response(request, 200,
                    "{\"data\":{\"sessionId\":\"session-abort\",\"uploadTargets\":[{\"assetIdentity\":\"a\",\"url\":\"https://cos.example/a\"}]}}");
            return RecordingHttpClient.response(request, 200, "{\"data\":{}}");
        });
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        HttpOnlineRestoreClient client = new HttpOnlineRestoreClient(support, new ObjectMapper(), store);
        OnlineRestoreClient.Session session = client.prepare("order-abort");
        client.upload(session, "a", new java.io.ByteArrayInputStream(new byte[]{1}));
        client.abort(session);
        assertThat(marked).isTrue();
    }
}
