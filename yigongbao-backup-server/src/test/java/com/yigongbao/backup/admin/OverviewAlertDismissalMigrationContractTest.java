package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OverviewAlertDismissalMigrationContractTest {
    @Test void migrationAndFreshInstallCreatePersistentAlertDismissals() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        String migration=Files.readString(root.resolve("sql/migration/2026-11-12-09-overview-alert-dismissal.sql"));
        String schema=Files.readString(root.resolve("sql/init/01-schema.sql"));
        String full=Files.readString(root.resolve("sql/init/yigongbao-backup-full-init.sql"));

        for(String sql: new String[]{migration,schema,full}) {
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS bk_overview_alert_dismissal")
                    .contains("alert_key VARCHAR(191) NOT NULL")
                    .contains("UNIQUE KEY uk_bk_overview_alert_dismissal_key (alert_key)")
                    .contains("dismissed_by BIGINT NOT NULL")
                    .contains("dismissed_at DATETIME(3) NOT NULL");
        }
    }
}
