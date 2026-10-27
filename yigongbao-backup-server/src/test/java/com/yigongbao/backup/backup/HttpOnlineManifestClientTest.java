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
                URI.create("http://online.example"), Duration.ofSeconds(5), "NONE", "", "", "");

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
}
