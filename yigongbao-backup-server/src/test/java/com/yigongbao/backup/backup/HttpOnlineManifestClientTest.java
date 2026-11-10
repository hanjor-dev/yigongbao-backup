package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteCallException;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOnlineManifestClientTest {
    @Test
    void readsConfirmsAndReceiptsDegradedV2ManifestIncludingZeroAssets() throws Exception {
        String gapHash = "c".repeat(64);
        String stateHash = "d".repeat(64);
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200,
                        request.method().equals("GET") ? """
                        {"code":"OK","data":{"orderId":"42","manifestSchemaVersion":2,
                        "backupCompleteness":"INCOMPLETE","manifestRevision":"%s","manifestJcsSha256":"%s",
                        "objectIdentityDigest":"%s","assets":[],"relationGapCount":1,
                        "relationGapHash":"%s","relationshipStateHash":"%s",
                        "relationGaps":[{"relationKind":"ORDER_FILE","sourceRecordId":"100",
                        "relationOwnerKey":"%s","relationValue":"10.1","originalFileId":"200",
                        "reasonCode":"FILE_RECORD_NOT_FOUND","reason":"订单文件不存在: 200",
                        "relationHash":"%s"}],"orderRaw":{"id":42},"orderDisplay":{"id":42}}}
                        """.formatted("a".repeat(64), "a".repeat(64), stateHash, gapHash, stateHash,
                                "e".repeat(64), "f".repeat(64))
                                : "{\"code\":\"OK\",\"data\":{}}"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HttpOnlineManifestClient client = new HttpOnlineManifestClient(new RemoteHttpSupport(
                transport, mapper, URI.create("http://online.example"), Duration.ofSeconds(5),
                "shared-production-secret-32-bytes!"), mapper);

        BackupManifest manifest = client.fetchDegraded("42");
        client.confirm("42", manifest);
        client.submitReceipt("42", manifest, new OrderBackupPipeline.Result(
                "orders/42.zip", "b".repeat(64), 100L, java.util.Map.of()), "idem", 1);

        assertThat(manifest.manifestSchemaVersion()).isEqualTo(2);
        assertThat(manifest.backupCompleteness()).isEqualTo("INCOMPLETE");
        assertThat(manifest.assets()).isEmpty();
        assertThat(manifest.relationGaps()).singleElement()
                .extracting(BackupManifest.RelationGap::reasonCode).isEqualTo("FILE_RECORD_NOT_FOUND");
        assertThat(transport.requests).extracting(request -> request.method() + " " + request.uri().getPath())
                .containsExactly(
                        "GET /internal/backup/v1/orders/42/manifest/degraded",
                        "POST /internal/backup/v1/orders/42/manifest/degraded/" + "a".repeat(64) + "/confirm",
                        "PUT /internal/backup/v1/receipts/42");
        assertThat(body(transport.requests.get(1))).contains("manifestJcsSha256", "relationshipStateHash", stateHash);
        assertThat(body(transport.requests.get(2))).contains(
                "\"backupCompleteness\":\"INCOMPLETE\"", "\"relationGapCount\":1",
                "\"relationGapHash\":\"" + gapHash, "\"relationshipStateHash\":\"" + stateHash);
    }

    @Test
    void degradedEndpointRejectsLegacyOrCompleteManifestShape() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200,
                        "{\"code\":\"OK\",\"data\":{\"orderId\":\"42\",\"assets\":[],"
                                + "\"manifestRevision\":\"rev-1\"}}"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HttpOnlineManifestClient client = new HttpOnlineManifestClient(new RemoteHttpSupport(
                transport, mapper, URI.create("http://online.example"), Duration.ofSeconds(5),
                "shared-production-secret-32-bytes!"), mapper);

        assertThatThrownBy(() -> client.fetchDegraded("42"))
                .isInstanceOf(RemoteCallException.class)
                .extracting(error -> ((RemoteCallException) error).code())
                .isEqualTo("VERSION_INCOMPATIBLE");
    }

    @Test
    void readsYamlManifestPathAndNestedAssetSourceObject() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200,
                        "{\"code\":\"OK\",\"data\":{"
                                + "\"manifestRevision\":\"rev-1\","
                                + "\"order\":{\"id\":\"42\",\"createdAt\":\"2026-01-01T00:00:00Z\"},"
                                + "\"assets\":[{\"assetIdentity\":\"file-1\",\"assetType\":\"DESIGN\","
                                + "\"originalFilename\":\"a.dcm\",\"size\":\"2\",\"sha256\":\"abc\","
                                + "\"sourceObject\":{\"objectKey\":\"orders/42/a.dcm\","
                                + "\"url\":\"https://cos.example/orders/42/a.dcm?signature=x\"}}]}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        BackupManifest manifest = new HttpOnlineManifestClient(support, new ObjectMapper()).fetch("42");

        assertThat(transport.requests).extracting(request -> request.uri().getPath())
                .containsExactly("/internal/backup/v1/orders/42/manifest");
        assertThat(manifest.orderId()).isEqualTo("42");
        assertThat(manifest.orderCreatedAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(manifest.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.assetId()).isEqualTo("file-1");
            assertThat(asset.fileName()).isEqualTo("a.dcm");
            assertThat(asset.sourceKey()).startsWith("https://cos.example/");
        });
    }

    @Test
    void readsSeparateRawAndDisplayOrderSnapshots() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200,
                        "{\"code\":\"OK\",\"data\":{" +
                                "\"orderId\":\"42\"," +
                                "\"orderRaw\":{\"id\":42,\"status\":2030,\"isClassicCase\":1}," +
                                "\"orderDisplay\":{\"id\":42,\"orderCode\":\"O-42\"," +
                                "\"statusName\":\"设计中\",\"regionalAudit\":null," +
                                "\"rebuildProjectList\":[]},\"assets\":[]}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        BackupManifest manifest = new HttpOnlineManifestClient(support, new ObjectMapper()).fetch("42");

        assertThat(manifest.orderRaw()).containsEntry("status", 2030).containsEntry("isClassicCase", 1);
        assertThat(manifest.orderDisplay()).containsEntry("orderCode", "O-42")
                .containsEntry("statusName", "设计中").containsKey("rebuildProjectList");
    }

    @Test
    void fallsBackToLegacyOrderForBothSnapshots() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200,
                        "{\"code\":\"OK\",\"data\":{\"orderId\":\"42\"," +
                                "\"order\":{\"id\":42,\"orderCode\":\"O-42\"},\"assets\":[]}}"));
        RemoteHttpSupport support = new RemoteHttpSupport(transport, new ObjectMapper(),
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");

        BackupManifest manifest = new HttpOnlineManifestClient(support, new ObjectMapper()).fetch("42");

        assertThat(manifest.orderRaw()).containsEntry("orderCode", "O-42");
        assertThat(manifest.orderDisplay()).containsEntry("orderCode", "O-42");
    }

    @Test
    void confirmsManifestAndSubmitsReceiptUsingInternalContract() {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200, "{\"code\":\"OK\",\"data\":{}}"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RemoteHttpSupport support = new RemoteHttpSupport(transport, mapper,
                URI.create("http://online.example"), Duration.ofSeconds(5), "shared-production-secret-32-bytes!");
        HttpOnlineManifestClient client = new HttpOnlineManifestClient(support, mapper);
        String digest = "a".repeat(64);
        BackupManifest manifest = new BackupManifest("42", "2026-01-01T00:00:00Z", java.util.List.of(),
                digest, digest, "b".repeat(64), java.util.Map.of());
        OrderBackupPipeline.Result result = new OrderBackupPipeline.Result(
                "orders/42/42.zip", "c".repeat(64), 128L, java.util.Map.of());

        client.confirm("42", manifest);
        client.submitReceipt("42", manifest, result, "backup:42:" + digest, 1);

        assertThat(transport.requests).extracting(request -> request.method() + " " + request.uri().getPath())
                .containsExactly(
                        "POST /internal/backup/v1/orders/42/manifest/" + digest + "/confirm",
                        "PUT /internal/backup/v1/receipts/42");
        assertThat(transport.requests.get(1).headers().firstValue("Idempotency-Key"))
                .contains("backup:42:" + digest);
    }

    @Test
    void receiptReplayReusesTheCompletedAtPersistedByTheStage() throws Exception {
        RecordingHttpClient transport = new RecordingHttpClient(request ->
                RecordingHttpClient.response(request, 200, "{\"code\":\"OK\",\"data\":{}}"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HttpOnlineManifestClient client = new HttpOnlineManifestClient(new RemoteHttpSupport(
                transport, mapper, URI.create("http://online.example"), Duration.ofSeconds(5),
                "shared-production-secret-32-bytes!"), mapper);
        String digest = "a".repeat(64);
        BackupManifest manifest = new BackupManifest("42", "2026-01-01T00:00:00Z", java.util.List.of(),
                digest, digest, "b".repeat(64), java.util.Map.of());
        OrderBackupPipeline.Result result = new OrderBackupPipeline.Result(
                "orders/42/42.zip", "c".repeat(64), 128L, java.util.Map.of());
        java.time.Instant completedAt = java.time.Instant.parse("2026-11-10T10:15:30.123Z");

        client.submitReceipt("42", manifest, result, "backup:42:" + digest, 1, completedAt);
        client.submitReceipt("42", manifest, result, "backup:42:" + digest, 1, completedAt);

        assertThat(mapper.readTree(body(transport.requests.get(0))).path("completedAt").asText())
                .isEqualTo(completedAt.toString());
        assertThat(mapper.readTree(body(transport.requests.get(1))).path("completedAt").asText())
                .isEqualTo(completedAt.toString());
    }

    private static String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { byte[] bytes = new byte[item.remaining()]; item.get(bytes); output.writeBytes(bytes); }
            public void onError(Throwable throwable) { completed.countDown(); }
            public void onComplete() { completed.countDown(); }
        });
        completed.await();
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
