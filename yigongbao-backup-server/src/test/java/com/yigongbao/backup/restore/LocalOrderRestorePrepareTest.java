package com.yigongbao.backup.restore;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOrderRestorePrepareTest {
    @Test
    void derivesPrepareDigestsFromPackageManifest() throws Exception {
        Path zip = Files.createTempFile("restore-manifest", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write("{\"manifestRevision\":\"rev-1\",\"assets\":[{\"assetIdentity\":\"a1\",\"relations\":[]}] }".getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("a1-file.txt"));
            out.write(1);
            out.closeEntry();
        }
        AtomicReference<OnlineRestoreClient.RestoreRequest> captured = new AtomicReference<>();
        OnlineRestoreClient client = new OnlineRestoreClient() {
            @Override public Session prepare(String orderId) { throw new AssertionError("must use manifest prepare"); }
            @Override public Session prepare(String orderId, RestoreRequest request) {
                captured.set(request);
                return new Session("s", List.of("a1"));
            }
            @Override public void upload(Session session, String assetName, InputStream data) { }
            @Override public void commit(Session session) { }
            @Override public void abort(Session session) { }
        };

        try {
            new LocalOrderRestoreService(client).restore(zip, "order-1");
            OnlineRestoreClient.RestoreRequest request = captured.get();
            assertThat(request).isNotNull();
            assertThat(request.manifestRevision()).isEqualTo("rev-1");
            assertThat(request.manifestSha256()).matches("[0-9a-f]{64}");
            assertThat(request.requestDigest()).matches("[0-9a-f]{64}");
            assertThat(request.assetSetDigest()).matches("[0-9a-f]{64}");
            assertThat(request.relationSetHash()).matches("[0-9a-f]{64}");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void v2PrepareKeepsGapsAsEvidenceWithoutAddingUploadTargets() throws Exception {
        Path zip = Files.createTempFile("restore-v2-manifest", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(("{\"manifestSchemaVersion\":2,\"backupCompleteness\":\"INCOMPLETE\"," +
                    "\"manifestRevision\":\"rev-2\",\"manifestSha256\":\"" + "a".repeat(64) + "\"," +
                    "\"relationSetHash\":\"" + "b".repeat(64) + "\",\"relationshipStateHash\":\"" + "c".repeat(64) + "\"," +
                    "\"assets\":[{\"assetIdentity\":\"10\",\"fileName\":\"a.txt\",\"size\":1,\"platform\":\"COS\",\"relations\":[]}]," +
                    "\"relationGaps\":[{\"relationKind\":\"ORDER_FILE\",\"sourceRecordId\":\"1\",\"relationOwnerKey\":\"" + "d".repeat(64) + "\",\"relationValue\":\"1\",\"originalFileId\":\"20\",\"reasonCode\":\"FILE_RECORD_NOT_FOUND\",\"reason\":\"missing\",\"relationHash\":\"" + "e".repeat(64) + "\"}]}" ).getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("files/10-a.txt")); out.write(1); out.closeEntry();
        }
        AtomicReference<OnlineRestoreClient.RestoreRequest> captured = new AtomicReference<>();
        OnlineRestoreClient client = new OnlineRestoreClient() {
            public Session prepare(String orderId) { throw new AssertionError(); }
            public Session prepare(String orderId, RestoreRequest request) { captured.set(request); return new Session("s", List.of("10")); }
            public void upload(Session session, String assetName, InputStream data) { }
            public void commit(Session session) { }
            public void abort(Session session) { }
        };
        try {
            new LocalOrderRestoreService(client).restore(zip, "42");
            assertThat(captured.get().manifestSchemaVersion()).isEqualTo(2);
            assertThat(captured.get().backupCompleteness()).isEqualTo("INCOMPLETE");
            assertThat(captured.get().relationshipStateHash()).isEqualTo("c".repeat(64));
            assertThat(captured.get().relationGaps()).hasSize(1);
            assertThat(captured.get().assets()).extracting(OnlineRestoreClient.RestoreAsset::assetIdentity).containsExactly("10");
        } finally { Files.deleteIfExists(zip); }
    }
}
