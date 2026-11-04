package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BackupWorkingSpaceEstimatorTest {
    @Test void reservesRawZipAndAtomicPublishCopies() {
        var manifest = new BackupManifest("O1", "v1", List.of(
                new BackupManifest.Asset("a", "a.bin", "DESIGN", 100, "key")));
        assertThat(BackupWorkingSpaceEstimator.estimate(manifest, 300)).isEqualTo(300);
    }

    @Test void rejectsOverflow() {
        var manifest = new BackupManifest("O1", "v1", List.of(
                new BackupManifest.Asset("a", "a.bin", "DESIGN", Long.MAX_VALUE, "key")));
        assertThatThrownBy(() -> BackupWorkingSpaceEstimator.estimate(manifest, 300))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("溢出");
    }
}
