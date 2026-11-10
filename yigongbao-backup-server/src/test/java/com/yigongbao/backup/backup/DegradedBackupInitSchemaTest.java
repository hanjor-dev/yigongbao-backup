package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedBackupInitSchemaTest {

    @Test
    void modularFreshInitCreatesDegradedEvidenceAndStageSchema() throws Exception {
        String schema = Files.readString(Path.of("sql/init/01-schema.sql"));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:degraded_backup_init;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            executeTable(connection, schema, "bk_order_snapshot");
            executeTable(connection, schema, "bk_relation_gap");
            executeTable(connection, schema, "bk_backup_stage");

            assertThat(columns(connection, "bk_order_snapshot")).contains(
                    "backup_completeness", "manifest_schema_version", "relation_gap_count",
                    "relation_gap_hash", "relationship_state_hash");
            assertIndexes(indexes(connection, "bk_relation_gap"),
                    "uk_bk_relation_gap_owner", "idx_bk_relation_gap_reason", "idx_bk_relation_gap_hash");
            assertIndexes(indexes(connection, "bk_backup_stage"),
                    "uk_bk_backup_stage_idempotency", "uk_bk_backup_stage_job_order",
                    "idx_bk_backup_stage_order_status");
        }
    }

    private static void executeTable(Connection connection, String schema, String table) throws Exception {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = schema.indexOf(marker);
        assertThat(start).as("%s DDL exists in modular init", table).isGreaterThanOrEqualTo(0);
        int end = schema.indexOf(';', start);
        assertThat(end).as("%s DDL is terminated", table).isGreaterThan(start);
        String ddl = schema.substring(start, end + 1)
                .replaceAll("(?is)\\)\\s*ENGINE=.*?;", ");")
                .replaceAll("(?i)\\s+COMMENT\\s+'(?:''|[^'])*'", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME").toLowerCase());
        }
        return names;
    }

    private static Set<String> indexes(Connection connection, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) names.add(name.toLowerCase());
            }
        }
        return names;
    }

    /** H2 为 MySQL UNIQUE KEY 自动追加 _INDEX_n，普通索引名称保持不变。 */
    private static void assertIndexes(Set<String> actual, String... expected) {
        for (String name : expected) {
            assertThat(actual).as("index %s", name)
                    .anyMatch(candidate -> candidate.equals(name) || candidate.startsWith(name + "_index_"));
        }
    }
}
