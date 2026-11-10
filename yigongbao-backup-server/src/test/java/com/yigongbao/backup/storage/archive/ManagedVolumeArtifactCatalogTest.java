package com.yigongbao.backup.storage.archive;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ManagedVolumeArtifactCatalogTest {
    @Test void normalizesRelativeKeysAndRejectsEscapes() {
        assertThat(ManagedVolumeArtifactCatalog.normalizeStorageKey("orders\\1\\a.zip")).isEqualTo("orders/1/a.zip");
        assertThatThrownBy(() -> ManagedVolumeArtifactCatalog.normalizeStorageKey("../secret"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非法");
        assertThatThrownBy(() -> ManagedVolumeArtifactCatalog.normalizeStorageKey("D:/secret"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非法");
    }
}
