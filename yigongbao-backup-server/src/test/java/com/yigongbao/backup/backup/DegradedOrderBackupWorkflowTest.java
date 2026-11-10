package com.yigongbao.backup.backup;

import com.yigongbao.backup.restore.LocalOrderRestoreService;
import com.yigongbao.backup.restore.OnlineRestoreClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 从 v2 降级包到既有恢复客户端的跨模块工作流验收。 */
class DegradedOrderBackupWorkflowTest {

    @Test
    void normalRestoreUploadsOnlyRealAssetsAndCarriesGapEvidence() throws Exception {
        Path archive = degradedPackage(true);
        AtomicReference<OnlineRestoreClient.RestoreRequest> prepared = new AtomicReference<>();
        AtomicInteger uploads = new AtomicInteger();
        OnlineRestoreClient client = new OnlineRestoreClient() {
            @Override public Session prepare(String orderId) { throw new AssertionError("必须使用清单恢复协议"); }
            @Override public Session prepare(String orderId, RestoreRequest request) {
                prepared.set(request);
                return new Session("session-42", request.assets().stream().map(RestoreAsset::assetIdentity).toList());
            }
            @Override public void upload(Session session, String assetName, InputStream data) { uploads.incrementAndGet(); }
            @Override public void commit(Session session) { }
            @Override public void abort(Session session) { }
        };

        try {
            new LocalOrderRestoreService(client).restore(archive, "42");

            assertThat(prepared.get().manifestSchemaVersion()).isEqualTo(2);
            assertThat(prepared.get().backupCompleteness()).isEqualTo("INCOMPLETE");
            assertThat(prepared.get().relationGaps()).singleElement()
                    .satisfies(gap -> assertThat(gap.originalFileId()).isEqualTo("20"));
            assertThat(prepared.get().assets()).extracting(OnlineRestoreClient.RestoreAsset::assetIdentity)
                    .containsExactly("10");
            assertThat(uploads).hasValue(1);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void allMissingPackageProducesNoPlaceholderRestoreAssets() throws Exception {
        Path archive = degradedPackage(false);
        try {
            LocalOrderRestoreService.RestorePackageInventory inventory =
                    new LocalOrderRestoreService(failingClient()).inspect(archive, "42");

            assertThat(inventory.assets()).isEmpty();
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static OnlineRestoreClient failingClient() {
        return new OnlineRestoreClient() {
            @Override public Session prepare(String orderId) { throw new AssertionError("零资产应由任务层跳过"); }
            @Override public void upload(Session session, String assetName, InputStream data) { throw new AssertionError(); }
            @Override public void commit(Session session) { throw new AssertionError(); }
            @Override public void abort(Session session) { throw new AssertionError(); }
        };
    }

    private static Path degradedPackage(boolean includeAsset) throws Exception {
        Path archive = Files.createTempFile("degraded-order-workflow", ".zip");
        String assetJson = includeAsset
                ? "{\"assetIdentity\":\"10\",\"fileName\":\"a.txt\",\"size\":1,\"platform\":\"COS\",\"relations\":[]}" : "";
        String manifest = "{\"manifestSchemaVersion\":2,\"backupCompleteness\":\"INCOMPLETE\"," +
                "\"manifestRevision\":\"rev-2\",\"manifestSha256\":\"" + "a".repeat(64) + "\"," +
                "\"relationSetHash\":\"" + "b".repeat(64) + "\",\"relationshipStateHash\":\"" + "c".repeat(64) + "\"," +
                "\"assets\":[" + assetJson + "],\"relationGaps\":[{\"relationKind\":\"ORDER_FILE\"," +
                "\"sourceRecordId\":\"1\",\"relationOwnerKey\":\"" + "d".repeat(64) + "\",\"relationValue\":\"1\"," +
                "\"originalFileId\":\"20\",\"reasonCode\":\"FILE_RECORD_NOT_FOUND\",\"reason\":\"missing\"," +
                "\"relationHash\":\"" + "e".repeat(64) + "\"}]}";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            if (includeAsset) {
                out.putNextEntry(new ZipEntry("files/10-a.txt"));
                out.write(1);
                out.closeEntry();
            }
        }
        return archive;
    }
}
