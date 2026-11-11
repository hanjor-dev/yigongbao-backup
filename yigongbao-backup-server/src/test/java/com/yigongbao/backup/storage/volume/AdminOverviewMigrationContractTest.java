package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewMigrationContractTest {
    private static final Path MODULE_ROOT = Path.of("").toAbsolutePath();

    @Test
    void migrationAddsCapacityObservationBackfillAndOverviewIndexes() throws Exception {
        String migration = Files.readString(MODULE_ROOT.resolve(
                "sql/migration/2026-11-11-13-admin-overview-dashboard.sql"));

        assertThat(migration)
                .contains("capacity_observed_at DATETIME(3)")
                .contains("total_bytes > 0")
                .contains("usable_bytes >= 0")
                .contains("usable_bytes <= total_bytes")
                .contains("capacity_observed_at = last_probed_at")
                .contains("idx_bk_job_overview")
                .contains("idx_bk_job_order_overview")
                .contains("idx_bk_snapshot_overview")
                .contains("idx_bk_package_overview")
                .contains("idx_bk_artifact_overview")
                .contains("idx_bk_volume_archive_overview");
    }

    @Test
    void freshInstallScriptsExposeTheSameCapacityAndIndexContract() throws Exception {
        String schema = Files.readString(MODULE_ROOT.resolve("sql/init/01-schema.sql"));
        String indexes = Files.readString(MODULE_ROOT.resolve("sql/init/02-indexes.sql"));
        String fullInit = Files.readString(MODULE_ROOT.resolve("sql/init/yigongbao-backup-full-init.sql"));

        assertThat(schema).contains("capacity_observed_at DATETIME(3)");
        assertThat(indexes)
                .contains("idx_bk_job_overview")
                .contains("idx_bk_job_order_overview")
                .contains("idx_bk_snapshot_overview")
                .contains("idx_bk_package_overview")
                .contains("idx_bk_artifact_overview")
                .contains("idx_bk_volume_archive_overview");
        assertThat(fullInit)
                .contains("capacity_observed_at DATETIME(3)")
                .contains("idx_bk_job_overview")
                .contains("idx_bk_volume_archive_overview");
    }
}
