package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RecordingHttpClient;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOnlineManifestClientTest {
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
}
