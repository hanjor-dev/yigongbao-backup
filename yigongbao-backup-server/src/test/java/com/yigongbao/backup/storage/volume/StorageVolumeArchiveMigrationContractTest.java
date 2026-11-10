package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定整盘封存、读取租约和卷操作状态在新装与升级脚本中的一致契约。 */
class StorageVolumeArchiveMigrationContractTest {

    @Test
    void declaresArchiveAttemptManifestAndReadLeaseSchema() throws Exception {
        Path module = Path.of("").toAbsolutePath();
        Path migrationPath = module.resolve("sql/migration/2026-11-09-09-cold-volume-archive.sql");
        assertThat(migrationPath).exists();
        String migration = Files.readString(migrationPath);
        String schema = Files.readString(module.resolve("sql/init/01-schema.sql"));
        String indexes = Files.readString(module.resolve("sql/init/02-indexes.sql"));
        String full = Files.readString(module.resolve("sql/init/yigongbao-backup-full-init.sql"));

        for (String sql : new String[]{migration, schema, full}) {
            assertThat(sql)
                    .contains("admin_status")
                    .contains("operation_status")
                    .contains("operation_ref_id")
                    .contains("archive_note")
                    .contains("order_created_from")
                    .contains("CREATE TABLE IF NOT EXISTS bk_volume_archive")
                    .contains("CREATE TABLE IF NOT EXISTS bk_volume_archive_item")
                    .contains("CREATE TABLE IF NOT EXISTS bk_storage_volume_read_lease");
        }
        assertThat(indexes)
                .contains("uk_bk_volume_archive_attempt")
                .contains("uk_bk_volume_archive_item_path")
                .contains("idx_bk_volume_read_lease_active");
        assertThat(migration).doesNotContain("tier='COLD' AND write_status='DISABLED'");
    }
}
