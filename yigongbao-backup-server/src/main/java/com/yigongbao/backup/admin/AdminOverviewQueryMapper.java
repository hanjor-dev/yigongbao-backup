package com.yigongbao.backup.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 管理概览固定数量的批量/聚合事实查询。 */
@Mapper
public interface AdminOverviewQueryMapper {
    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectArtifactReplicaCandidates")
    List<ArtifactReplicaCandidateRow> selectArtifactReplicaCandidates();

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectManagedArtifactSummary")
    ManagedArtifactSummaryRow selectManagedArtifactSummary();

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectDataQualitySummary")
    DataQualitySummaryRow selectDataQualitySummary();

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectBackupResultBuckets")
    List<BackupResultBucketRow> selectBackupResultBuckets(@Param("from") LocalDateTime from,
                                                           @Param("to") LocalDateTime to);

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectBackupBytesBuckets")
    List<BackupBytesBucketRow> selectBackupBytesBuckets(@Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectRecentJobs")
    List<RecentJobRow> selectRecentJobs(@Param("limit") int limit);

    @SelectProvider(type = AdminOverviewQuerySqlProvider.class, method = "selectLatestAutoCleanup")
    RecentJobRow selectLatestAutoCleanup();

    record ArtifactReplicaCandidateRow(Long sourceOrderId, Long snapshotId, Long packageId,
                                       LocalDateTime backupCompletedAt, String completeness,
                                       Long fileCount, Long packageSize, Long readableCopyCount,
                                       Long expectedOfflineCopyCount, Long activeCopyCount,
                                       Long invalidMetadataCount, Long abnormalActiveCopyCount,
                                       Long hotCopyCount, Long coldCopyCount) {
        ArtifactReplicaCandidateRow(Long sourceOrderId, Long snapshotId, Long packageId,
                                    LocalDateTime backupCompletedAt, String completeness,
                                    Long fileCount, Long packageSize, Long readableCopyCount,
                                    Long expectedOfflineCopyCount, Long activeCopyCount,
                                    Long invalidMetadataCount, Long abnormalActiveCopyCount) {
            this(sourceOrderId, snapshotId, packageId, backupCompletedAt, completeness, fileCount, packageSize,
                    readableCopyCount, expectedOfflineCopyCount, activeCopyCount, invalidMetadataCount,
                    abnormalActiveCopyCount, 0L, 0L);
        }
    }

    record ManagedArtifactSummaryRow(Long managedArtifactBytes, Long unknownSizeCopyCount,
                                     Long metadataRecordCount) {
        ManagedArtifactSummaryRow(Long managedArtifactBytes, Long unknownSizeCopyCount) {
            this(managedArtifactBytes, unknownSizeCopyCount, 0L);
        }
    }
    record DataQualitySummaryRow(Long realSnapshotSimulationPackageCount,
                                 Long missingCompletionTimePackageCount,
                                 Long missingVolumeCopyCount) { }
    record BackupResultBucketRow(LocalDate day, Long completeSucceeded, Long incompleteSucceeded,
                                 Long failed, Long skipped, Long total) { }
    record BackupBytesBucketRow(LocalDate day, Long addedPackageBytes, Long unknownPackageCount) { }
    record RecentJobRow(Long jobId, String jobNo, String jobType, String status,
                        Long progressCurrent, Long progressTotal, Long completeSucceeded,
                        Long incompleteSucceeded, Long failed, Long skipped,
                        LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime finishedAt,
                        Long storageVolumeId, String storageVolumeName, String storageTier,
                        String failureCode, String failureMessage) { }
}
