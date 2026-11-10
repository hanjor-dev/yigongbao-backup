package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储卷迁移脚本的静态契约测试。
 *
 * <p>该测试避免在单元测试中修改真实数据库；它锁定新环境初始化和存量环境迁移
 * 必须同时具备的卷定位字段、逻辑唯一约束及 legacy-hot 兼容规则。</p>
 */
class StorageVolumeMigrationContractTest {

    @Test
    void shouldDeclareVolumeLedgerAndCrossVolumeArtifactLocationContract() throws Exception {
        Path moduleRoot = Path.of("").toAbsolutePath();
        String schema = Files.readString(moduleRoot.resolve("sql/init/01-schema.sql"));
        String indexes = Files.readString(moduleRoot.resolve("sql/init/02-indexes.sql"));
        String migration = Files.readString(moduleRoot.resolve("sql/migration/2026-11-06-04-storage-volume-management.sql"));

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS bk_storage_volume")
                .contains("storage_volume_id BIGINT NOT NULL")
                .contains("target_storage_volume_id BIGINT NULL")
                .contains("source_storage_volume_id BIGINT NULL")
                .contains("bk_artifact_copy");
        assertThat(indexes)
                .contains("uk_bk_package_volume_key")
                .contains("uk_bk_supplement_volume_key")
                .contains("uk_bk_artifact_copy_volume_key")
                .contains("uk_bk_storage_hot_active");
        assertThat(migration)
                .contains("legacy-hot")
                .contains("bk_storage_volume_migration_issue")
                .contains("storage_volume_id")
                .contains("不可靠")
                .contains("不猜测");
    }

    @Test
    void jobOrderLocationMigrationBackfillsExistingRows() throws Exception {
        String migration = Files.readString(Path.of("").toAbsolutePath()
                .resolve("sql/migration/2026-11-09-06-job-order-storage-location.sql"));

        assertThat(migration).contains("UPDATE bk_job_order")
                .contains("source_storage_volume_id")
                .contains("bk_order_package")
                .contains("target_storage_volume_id");
    }
}
