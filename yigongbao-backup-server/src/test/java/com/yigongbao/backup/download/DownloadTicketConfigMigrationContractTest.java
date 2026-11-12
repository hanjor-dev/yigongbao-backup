package com.yigongbao.backup.download;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadTicketConfigMigrationContractTest {
    private static final Path MODULE_ROOT = Path.of("").toAbsolutePath();

    @Test
    void upgradeAndFreshInstallScriptsSeedTheDownloadTicketTtl() throws Exception {
        Path repair = MODULE_ROOT.resolve("sql/migration/2026-11-12-08-download-ticket-config.sql");

        assertThat(repair).exists();
        assertThat(Files.readString(repair)).contains("download.ticket.ttl-seconds", "'120'");
        assertThat(Files.readString(MODULE_ROOT.resolve("sql/init/04-functional-config-defaults.sql")))
                .contains("download.ticket.ttl-seconds", "'120'");
        assertThat(Files.readString(MODULE_ROOT.resolve("sql/init/yigongbao-backup-full-init.sql")))
                .contains("download.ticket.ttl-seconds", "'120'");
    }
}
