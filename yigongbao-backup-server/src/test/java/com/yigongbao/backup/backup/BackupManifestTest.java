package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupManifestTest {
    @Test
    void fullOrderSnapshotPreservesNullableBusinessFieldsButRemainsReadOnly() {
        LinkedHashMap<String, Object> order = new LinkedHashMap<>();
        order.put("orderCode", "ORDER-1");
        order.put("optionalRemark", null);

        BackupManifest manifest = new BackupManifest("1", "2026-11-02T00:00:00Z", List.of(),
                "a".repeat(64), "a".repeat(64), "b".repeat(64), order);

        assertThat(manifest.order()).containsEntry("optionalRemark", null);
        assertThatThrownBy(() -> manifest.order().put("newField", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
