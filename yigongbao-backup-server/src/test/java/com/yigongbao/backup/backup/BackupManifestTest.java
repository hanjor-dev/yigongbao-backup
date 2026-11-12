package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupManifestTest {
    @Test
    void v2JsonPreservesStructuredRelationGaps() throws Exception {
        String json = """
                {
                  "orderId":"42",
                  "orderCreatedAt":"2026-11-10T00:00:00",
                  "assets":[],
                  "manifestRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "manifestSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "relationSetHash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "manifestSchemaVersion":2,
                  "backupCompleteness":"INCOMPLETE",
                  "relationGaps":[{
                    "relationKind":"ORDER_FILE",
                    "sourceRecordId":"100",
                    "relationOwnerKey":"owner",
                    "relationValue":"10.1",
                    "originalFileId":"200",
                    "reasonCode":"FILE_RECORD_NOT_FOUND",
                    "reason":"订单文件不存在",
                    "relationHash":"relation"
                  }],
                  "relationGapCount":1,
                  "relationGapHash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "relationshipStateHash":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "orderRaw":{"optionalRemark":null},
                  "orderDisplay":{"orderCode":"ORDER-42"}
                }
                """;

        BackupManifest manifest = new ObjectMapper().readValue(json, BackupManifest.class);

        assertThat(manifest.manifestSchemaVersion()).isEqualTo(2);
        assertThat(manifest.backupCompleteness()).isEqualTo("INCOMPLETE");
        assertThat(manifest.relationGapCount()).isEqualTo(1);
        assertThat(manifest.relationGaps()).singleElement().satisfies(gap -> {
            assertThat(gap.relationKind()).isEqualTo("ORDER_FILE");
            assertThat(gap.originalFileId()).isEqualTo("200");
            assertThat(gap.reasonCode()).isEqualTo("FILE_RECORD_NOT_FOUND");
        });
        assertThatThrownBy(() -> manifest.relationGaps().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fullOrderSnapshotPreservesNullableBusinessFieldsButRemainsReadOnly() {
        LinkedHashMap<String, Object> order = new LinkedHashMap<>();
        order.put("orderCode", "ORDER-1");
        order.put("optionalRemark", null);

        BackupManifest manifest = new BackupManifest("1", "2026-11-02T00:00:00Z", List.of(),
                "a".repeat(64), "a".repeat(64), "b".repeat(64), order);

        assertThat(manifest.order()).containsEntry("optionalRemark", null);
        assertThat(manifest.manifestSchemaVersion()).isEqualTo(1);
        assertThat(manifest.backupCompleteness()).isEqualTo("COMPLETE");
        assertThat(manifest.relationGaps()).isEmpty();
        assertThat(manifest.relationGapCount()).isZero();
        assertThat(manifest.relationshipStateHash()).isNull();
        assertThatThrownBy(() -> manifest.order().put("newField", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void durableManifestDropsTransientDownloadCredentialButKeepsObjectIdentity() {
        BackupManifest.Asset asset = new BackupManifest.Asset("7", "a.pdf", "ORDER_FILE", 12,
                "https://cos.example/a?signature=secret", "a".repeat(64), "COS", "bucket",
                "orders/a.pdf", "version-1", 7L, List.of());
        BackupManifest manifest = new BackupManifest("1", "2026-11-12T00:00:00Z", List.of(asset));

        BackupManifest durable = manifest.withoutTransientSourceKeys();

        assertThat(durable.assets()).singleElement().satisfies(saved -> {
            assertThat(saved.sourceKey()).isNull();
            assertThat(saved.objectKey()).isEqualTo("orders/a.pdf");
            assertThat(saved.versionIdentity()).isEqualTo("version-1");
        });
        assertThat(manifest.assets().getFirst().sourceKey()).contains("signature=secret");
    }
}
