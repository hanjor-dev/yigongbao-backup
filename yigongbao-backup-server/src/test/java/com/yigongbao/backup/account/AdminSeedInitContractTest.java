package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSeedInitContractTest {

    @Test
    void freshDatabaseInitSeedsLocalAdminWithoutBootstrapEnvironmentCredentials() throws Exception {
        String modularInit = Files.readString(Path.of("sql/init/01-schema.sql"));
        String fullInit = Files.readString(Path.of("sql/init/yigongbao-backup-full-init.sql"));
        String envExample = Files.readString(Path.of(".env.example"));

        for (String init : new String[]{modularInit, fullInit}) {
            assertThat(init)
                    .contains("INSERT INTO bk_user", "'admin1'", "'ADMIN'", "'ENABLED'")
                    .contains("WHERE NOT EXISTS")
                    .doesNotContain("BACKUP_BOOTSTRAP_ADMIN_PASSWORD", "BACKUP_BOOTSTRAP_ADMIN_USERNAME");
        }
        assertThat(envExample)
                .doesNotContain("BACKUP_BOOTSTRAP_ADMIN_PASSWORD", "BACKUP_BOOTSTRAP_ADMIN_USERNAME");
    }
}
