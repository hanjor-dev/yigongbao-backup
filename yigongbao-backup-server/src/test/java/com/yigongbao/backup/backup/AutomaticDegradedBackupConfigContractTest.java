package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutomaticDegradedBackupConfigContractTest {
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    @Test
    void initializationAndMigrationDeclareDefaultEnabledConfiguration() throws Exception {
        String key = "backup.auto-degrade-on-relation-broken";
        String defaults = Files.readString(ROOT.resolve("sql/init/04-functional-config-defaults.sql"));
        String full = Files.readString(ROOT.resolve("sql/init/yigongbao-backup-full-init.sql"));
        String migration = Files.readString(ROOT.resolve("sql/migration/2026-11-12-10-auto-degraded-backup.sql"));

        assertThat(defaults).contains(key, "文件关系不完整时自动降级备份", "'BOOLEAN','true'");
        assertThat(full).contains(key, "文件关系不完整时自动降级备份", "'BOOLEAN','true'");
        assertThat(migration).contains(key, "WHERE NOT EXISTS");
    }
}
