package com.yigongbao.backup.admin;

import com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy;
import com.yigongbao.backup.storage.volume.StorageVolumeAccessSqlPredicate;

/** MySQL 8 概览 SQL。只提取持久化事实；卷可读性由共享 Java 策略分类。 */
public final class AdminOverviewQuerySqlProvider {
    private AdminOverviewQuerySqlProvider() { }

    public static String selectArtifactReplicaCandidates() {
        StorageVolumeAccessPolicy access=new StorageVolumeAccessPolicy();
        var aliases=new StorageVolumeAccessSqlPredicate.SqlAliases("v","a");
        String readable=access.readableSql(aliases).sql();
        String expectedOffline=access.expectedOfflineSql(aliases).sql();
        String invalid="c.storage_volume_id IS NULL OR v.id IS NULL OR c.storage_key IS NULL OR c.storage_key='' OR c.size IS NULL OR c.sha256 IS NULL OR c.sha256=''";
        String healthy="COALESCE(c.state,'') IN ('PRESENT','VERIFIED') AND NOT ("+invalid+")";
        String abnormal="NOT ("+healthy+" AND (("+readable+") OR ("+expectedOffline+")))";
        return """
                SELECT s.source_order_id AS sourceOrderId, s.id AS snapshotId, p.id AS packageId,
                       s.backup_completed_at AS backupCompletedAt,
                       s.backup_completeness AS completeness, p.file_count AS fileCount,
                       p.package_size AS packageSize,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND %s AND %s THEN c.id END) AS readableCopyCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND %s AND %s THEN c.id END) AS expectedOfflineCopyCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL THEN c.id END) AS activeCopyCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND (%s) THEN c.id END) AS invalidMetadataCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND (%s) THEN c.id END) AS abnormalActiveCopyCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND %s AND v.is_deleted=0 AND v.tier='HOT' THEN c.id END) AS hotCopyCount,
                       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND %s AND v.is_deleted=0 AND v.tier='COLD' THEN c.id END) AS coldCopyCount
                FROM bk_order_snapshot s
                JOIN bk_order_package p ON p.snapshot_id=s.id AND p.is_deleted=0
                     AND p.backup_mode='REAL' AND p.status='AVAILABLE'
                LEFT JOIN bk_artifact_copy c ON c.artifact_type='ORDER_PACKAGE' AND c.artifact_id=p.id
                     AND c.lifecycle='ACTIVE' AND c.is_deleted=0
                LEFT JOIN bk_storage_volume v ON v.id=c.storage_volume_id
                LEFT JOIN bk_volume_archive a ON a.id=v.operation_ref_id
                WHERE s.is_deleted=0 AND s.backup_mode='REAL' AND s.snapshot_status='AVAILABLE'
                GROUP BY s.source_order_id,s.id,p.id,s.backup_completed_at,s.backup_completeness,p.file_count,p.package_size
                """.formatted(healthy,readable,healthy,expectedOffline,invalid,abnormal,healthy,healthy);
    }

    public static String selectDataQualitySummary() {
        return """
                SELECT
                  (SELECT COUNT(*) FROM bk_order_snapshot s
                   JOIN bk_order_package p ON p.snapshot_id=s.id AND p.is_deleted=0
                   WHERE s.is_deleted=0 AND s.backup_mode='REAL' AND s.snapshot_status='AVAILABLE'
                     AND p.backup_mode<>'REAL') AS realSnapshotSimulationPackageCount,
                  (SELECT COUNT(*) FROM bk_order_snapshot s
                   JOIN bk_order_package p ON p.snapshot_id=s.id AND p.is_deleted=0
                     AND p.backup_mode='REAL' AND p.status='AVAILABLE'
                   WHERE s.is_deleted=0 AND s.backup_mode='REAL' AND s.snapshot_status='AVAILABLE'
                     AND s.backup_completed_at IS NULL) AS missingCompletionTimePackageCount,
                  (SELECT COUNT(*) FROM bk_artifact_copy c
                   LEFT JOIN bk_storage_volume v ON v.id=c.storage_volume_id AND v.is_deleted=0
                   WHERE c.is_deleted=0 AND c.lifecycle='ACTIVE'
                     AND c.artifact_type IN ('ORDER_PACKAGE','SUPPLEMENT_FILE')
                     AND (c.storage_volume_id IS NULL OR v.id IS NULL)) AS missingVolumeCopyCount
                """;
    }

    public static String selectManagedArtifactSummary() {
        return """
                SELECT COALESCE(SUM(CASE WHEN c.size IS NOT NULL THEN c.size ELSE 0 END),0) AS managedArtifactBytes,
                       SUM(CASE WHEN c.size IS NULL THEN 1 ELSE 0 END) AS unknownSizeCopyCount,
                       COUNT(*) AS metadataRecordCount
                FROM bk_artifact_copy c
                WHERE c.is_deleted=0 AND c.lifecycle='ACTIVE'
                  AND c.state IN ('PRESENT','VERIFIED')
                  AND c.artifact_type IN ('ORDER_PACKAGE','SUPPLEMENT_FILE')
                """;
    }

    public static String selectBackupResultBuckets() {
        return """
                WITH ranked AS (
                  SELECT DATE(j.finished_at) AS day, jo.source_order_id, jo.status AS order_status,
                         s.backup_completeness,
                         ROW_NUMBER() OVER (PARTITION BY DATE(j.finished_at), jo.source_order_id
                                            ORDER BY j.finished_at DESC, j.id DESC) AS rn
                  FROM bk_job j
                  JOIN bk_job_order jo ON jo.job_id=j.id AND jo.is_deleted=0
                  LEFT JOIN bk_order_snapshot s ON s.id=jo.snapshot_id AND s.is_deleted=0
                  WHERE j.is_deleted=0 AND j.finished_at>=#{from} AND j.finished_at<#{to}
                    AND j.job_type IN ('ORDER_BACKUP','MANUAL_BACKUP','DEGRADED_BACKUP')
                    AND j.finished_at IS NOT NULL
                    AND jo.status NOT IN ('SIMULATED','SIMULATION_FAILED')
                )
                SELECT day,
                       SUM(order_status='SUCCEEDED' AND backup_completeness='COMPLETE') AS completeSucceeded,
                       SUM(order_status='SUCCEEDED' AND backup_completeness='INCOMPLETE') AS incompleteSucceeded,
                       SUM(order_status='FAILED') AS failed,
                       SUM(order_status='SKIPPED') AS skipped,
                       COUNT(*) AS total
                FROM ranked WHERE rn=1 GROUP BY day ORDER BY day
                """;
    }

    public static String selectBackupBytesBuckets() {
        return """
                SELECT DATE(s.backup_completed_at) AS day,
                       COALESCE(SUM(CASE WHEN p.package_size IS NOT NULL THEN p.package_size ELSE 0 END),0) AS addedPackageBytes,
                       SUM(CASE WHEN p.package_size IS NULL THEN 1 ELSE 0 END) AS unknownPackageCount
                FROM bk_order_snapshot s
                JOIN bk_order_package p ON p.snapshot_id=s.id AND p.is_deleted=0
                     AND p.backup_mode='REAL' AND p.status='AVAILABLE'
                WHERE s.is_deleted=0 AND s.backup_mode='REAL' AND s.snapshot_status='AVAILABLE'
                  AND s.backup_completed_at>=#{from} AND s.backup_completed_at<#{to}
                GROUP BY DATE(s.backup_completed_at) ORDER BY day
                """;
    }

    public static String selectRecentJobs() {
        return recentJobsSql("AND job_type<>'SUPPLEMENT_UPLOAD'", "#{limit}");
    }

    public static String selectLatestAutoCleanup() {
        return recentJobsSql("AND job_type='AUTO_CLEANUP'", "1");
    }

    private static String recentJobsSql(String jobTypePredicate, String limit) {
        return """
                WITH recent_jobs AS (
                  SELECT * FROM bk_job
                  WHERE is_deleted=0 %s
                  ORDER BY COALESCE(finished_at,started_at,requested_at) DESC, id DESC
                  LIMIT %s
                ), order_rollup AS (
                  SELECT jo.job_id,
                         SUM(jo.status='SUCCEEDED' AND s.backup_completeness='COMPLETE') AS completeSucceeded,
                         SUM(jo.status='SUCCEEDED' AND s.backup_completeness='INCOMPLETE') AS incompleteSucceeded,
                         SUM(jo.status='FAILED') AS failed,
                         SUM(jo.status='SKIPPED') AS skipped
                  FROM bk_job_order jo
                  JOIN recent_jobs rj ON rj.id=jo.job_id
                  LEFT JOIN bk_order_snapshot s ON s.id=jo.snapshot_id AND s.is_deleted=0
                  WHERE jo.is_deleted=0 GROUP BY jo.job_id
                )
                SELECT j.id AS jobId, j.job_no AS jobNo, j.job_type AS jobType, j.status,
                       j.progress_current AS progressCurrent, j.progress_total AS progressTotal,
                       COALESCE(jo.completeSucceeded,0) AS completeSucceeded,
                       COALESCE(jo.incompleteSucceeded,0) AS incompleteSucceeded,
                       COALESCE(jo.failed,0) AS failed, COALESCE(jo.skipped,0) AS skipped,
                       j.requested_at AS requestedAt, j.started_at AS startedAt, j.finished_at AS finishedAt,
                       v.id AS storageVolumeId, v.display_name AS storageVolumeName, v.tier AS storageTier,
                       j.failure_code AS failureCode, j.failure_message AS failureMessage
                FROM recent_jobs j
                LEFT JOIN order_rollup jo ON jo.job_id=j.id
                LEFT JOIN bk_storage_volume v ON v.id=j.target_storage_volume_id AND v.is_deleted=0
                ORDER BY COALESCE(j.finished_at,j.started_at,j.requested_at) DESC, j.id DESC
                """.formatted(jobTypePredicate, limit);
    }
}
