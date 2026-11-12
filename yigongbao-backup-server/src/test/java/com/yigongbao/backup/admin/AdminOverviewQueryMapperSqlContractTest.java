package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewQueryMapperSqlContractTest {
    @Test
    void candidateSqlKeepsTheRealPackageReplicaAndArchiveDecisionDimensions() {
        String sql = normalized(AdminOverviewQuerySqlProvider.selectArtifactReplicaCandidates());

        assertThat(sql).contains("bk_order_snapshot", "bk_order_package", "bk_artifact_copy", "bk_storage_volume",
                "bk_volume_archive", "backup_completeness", "backup_mode='real'", "snapshot_status='available'",
                "p.status='available'", "left join bk_artifact_copy", "operation_ref_id", "successor_volume_id",
                "readablecopycount", "expectedofflinecopycount", "invalidmetadatacount", "v.id is null",
                "abnormalactivecopycount", "hotcopycount", "coldcopycount", "group by s.source_order_id");
        assertThat(sql).doesNotContain("path(", "files.");
    }

    @Test
    void managedAssetSummaryCountsPersistedMetadataRecords() {
        String sql = normalized(AdminOverviewQuerySqlProvider.selectManagedArtifactSummary());

        assertThat(sql).contains("managedartifactbytes", "unknownsizecopycount", "metadatarecordcount",
                "order_package", "supplement_file");
    }

    @Test
    void aggregatesMandatoryDataQualityContradictions() {
        String sql = normalized(AdminOverviewQuerySqlProvider.selectDataQualitySummary());
        assertThat(sql).contains("p.backup_mode<>'real'", "s.backup_completed_at is null",
                "c.storage_volume_id is null or v.id is null");
    }

    @Test
    void aggregateQueriesUseFinalDailyOrderAttemptsAndFixedBatchJoins() {
        String results = normalized(AdminOverviewQuerySqlProvider.selectBackupResultBuckets());
        String recent = normalized(AdminOverviewQuerySqlProvider.selectRecentJobs());
        String bytes = normalized(AdminOverviewQuerySqlProvider.selectBackupBytesBuckets());

        assertThat(results).contains("row_number() over", "partition by date(j.finished_at), jo.source_order_id", "j.finished_at",
                "order_backup", "manual_backup", "degraded_backup");
        assertThat(bytes).contains("backup_mode='real'", "package_size", "backup_completed_at");
        assertThat(recent).contains("with recent_jobs as", "join recent_jobs rj", "left join", "bk_job_order",
                "bk_order_snapshot", "backup_completeness", "group by jo.job_id", "bk_storage_volume");
        assertThat(recent).contains("job_type<>'supplement_upload'");
        assertThat(recent).doesNotContain("select count(*) from bk_job_order");
    }

    @Test
    void projectStillHasNoMybatisMapperXml() throws Exception {
        Path resources = Path.of("src/main/resources");
        try (var paths = Files.walk(resources)) {
            assertThat(paths.filter(path -> path.toString().endsWith("Mapper.xml")).toList()).isEmpty();
        }
    }

    private static String normalized(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ");
    }
}
