package com.yigongbao.backup.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 管理概览的稳定只读响应契约。未知分区使用 null，不用零或空集合伪装。 */
public record AdminOverviewView(LocalDateTime observedAt,
                                Health health,
                                Sections sections,
                                Summary summary,
                                Storage storage,
                                List<Alert> activeAlerts,
                                List<BackupTrendPoint> backupTrend,
                                Automation automation,
                                List<Activity> recentActivities,
                                DataQuality dataQuality,
                                List<SectionError> sectionErrors) {
    public record Health(String level, long activeAlertCount, long criticalAlertCount, long warningAlertCount) { }
    public record Sections(String summary, String storage, String activeAlerts, String backupTrend,
                           String automation, String recentActivities) { }
    public record Summary(ReadableOrders readableOrders, OrderStatistics orderStatistics,
                          ReadableFiles readableFiles, TodayBackup todayBackup) {
        public Summary(ReadableOrders readableOrders, ReadableFiles readableFiles, TodayBackup todayBackup) {
            this(readableOrders, new OrderStatistics(0, 0, 0), readableFiles, todayBackup);
        }
    }
    public record ReadableOrders(long total, long complete, long incomplete,
                                 long temporarilyOffline, long abnormalUnreadable,
                                 long degradedRedundancy) { }
    public record OrderStatistics(long total, long hot, long cold) { }
    public record ReadableFiles(long orderPackageCount, long logicalFileCount, long managedArtifactBytes,
                                long unknownFileCountPackageCount, long unknownSizeCopyCount,
                                long invalidCopyMetadataCount, long metadataRecordCount) {
        public ReadableFiles(long logicalFileCount, long managedArtifactBytes,
                             long unknownFileCountPackageCount, long unknownSizeCopyCount,
                             long invalidCopyMetadataCount) {
            this(0, logicalFileCount, managedArtifactBytes, unknownFileCountPackageCount,
                    unknownSizeCopyCount, invalidCopyMetadataCount, 0);
        }
    }
    public record TodayBackup(long completeSucceeded, long incompleteSucceeded, long failed, long skipped, long total) { }
    public record Storage(Volume activeVolume, Standby standby, ColdMedia coldMedia, OnlineCapacity onlineCapacity) { }
    public record Volume(Long id, String name, String code, String mountStatus, String writeStatus,
                         String operationStatus, Long usedBytes, Long usableBytes, Long totalBytes,
                         BigDecimal usedPercent, Integer thresholdPercent, LocalDateTime lastProbedAt,
                         LocalDateTime capacityObservedAt, boolean capacityFresh, String capacityLabel) { }
    public record Standby(long readyCount, Volume preferred) { }
    public record ColdMedia(long totalCount, long onlineCount, long expectedOfflineCount, long repairRequiredCount,
                            long successfulArchiveCount, LocalDateTime latestArchivedAt) { }
    public record OnlineCapacity(Long usedBytes, Long usableBytes, Long totalBytes,
                                 BigDecimal usedPercent, long includedVolumeCount, long excludedVolumeCount) { }
    public record Alert(String alertKey, String severity, String category, String code, String title,
                        String message, LocalDateTime evidenceAt, String targetType, String targetId,
                        String actionRoute, Map<String, String> actionQuery) { }
    public record BackupTrendPoint(@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
                                   long completeSucceeded, long incompleteSucceeded,
                                   long failed, long skipped, long addedPackageBytes,
                                   long unknownPackageCount) { }
    public record Automation(AutomationItem backup, AutomationItem accountSync, AutomationItem cleanup) { }
    public record AutomationItem(String state, String scheduleState, String lastRunStatus,
                                 boolean enabled, String executionMode,
                                 String schedule, Integer intervalValue, String intervalUnit,
                                 Integer lastProcessedCount, String lastError,
                                 LocalDateTime lastAttemptAt,
                                 LocalDateTime lastSuccessAt, LocalDateTime nextExpectedAt,
                                 String message, String actionRoute, Map<String, String> actionQuery) { }
    public record Activity(String activityId, String sourceType, Long sourceId, String activityType,
                           String name, String status, Long progressCurrent, Long progressTotal,
                           long completeSucceeded, long incompleteSucceeded, long failed, long skipped,
                           LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime finishedAt,
                           String storageSummary, String failureSummary,
                           String actionRoute, Map<String, String> actionQuery) { }
    public record DataQuality(boolean complete, List<DataQualityMessage> messages) { }
    public record DataQualityMessage(String code, String severity, String section,
                                     long affectedCount, String message) { }
    public record SectionError(String section, String code, String requestId, String message) { }
}

/** 把候选事实归并为互斥订单分类；不访问数据库、文件系统或外部服务。 */
final class AdminOverviewProjection {
    private AdminOverviewProjection() { }

    static AdminOverviewView.Summary summary(
            List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> candidates,
            AdminOverviewQueryMapper.ManagedArtifactSummaryRow managed,
            StorageVolumeAccessPolicy accessPolicy) {
        Map<Long, Map<Long, List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow>>> orders = new LinkedHashMap<>();
        if (candidates != null) for (var row : candidates) {
            if (row == null || row.sourceOrderId() == null || row.packageId() == null) continue;
            orders.computeIfAbsent(row.sourceOrderId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.packageId(), ignored -> new ArrayList<>()).add(row);
        }
        long total = 0, complete = 0, incomplete = 0, offline = 0, abnormal = 0, degraded = 0;
        long backedUpOrders = 0, hotOrders = 0, coldOrders = 0;
        long orderPackages = 0, logicalFiles = 0, unknownFiles = 0;
        long invalidCopyMetadata = candidates == null ? 0 : candidates.stream().filter(Objects::nonNull)
                .map(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::invalidMetadataCount)
                .filter(Objects::nonNull).reduce(0L,Math::addExact);
        for (Map<Long, List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow>> packages : orders.values()) {
            orderPackages = Math.addExact(orderPackages, packages.values().stream()
                    .filter(rows -> rows.stream().anyMatch(AdminOverviewProjection::hasStoredCopy)).count());
            boolean hasHot = packages.values().stream().flatMap(List::stream)
                    .anyMatch(row -> row.hotCopyCount() != null && row.hotCopyCount() > 0);
            boolean hasCold = packages.values().stream().flatMap(List::stream)
                    .anyMatch(row -> row.coldCopyCount() != null && row.coldCopyCount() > 0);
            if (hasHot || hasCold) backedUpOrders++;
            if (hasHot) hotOrders++;
            if (hasCold) coldOrders++;
            AdminOverviewQueryMapper.ArtifactReplicaCandidateRow assetRepresentative = packages.values().stream()
                    .flatMap(List::stream).filter(AdminOverviewProjection::hasStoredCopy)
                    .max(CANDIDATE_ORDER).orElse(null);
            if (assetRepresentative != null) {
                if (assetRepresentative.fileCount() == null) unknownFiles++;
                else logicalFiles = Math.addExact(logicalFiles, assetRepresentative.fileCount());
            }
            List<PackageDecision> decisions = packages.values().stream()
                    .map(rows -> decidePackage(rows, accessPolicy)).toList();
            PackageDecision representative = decisions.stream().filter(PackageDecision::readable)
                    .max(PACKAGE_ORDER).orElse(null);
            if (representative != null) {
                total++;
                if (representative.hasAbnormalActiveCopy()) degraded++;
                if ("INCOMPLETE".equals(representative.row().completeness())) incomplete++; else complete++;
            } else if (!decisions.isEmpty() && decisions.stream().allMatch(PackageDecision::expectedOffline)) {
                offline++;
            } else {
                abnormal++;
            }
        }
        long managedBytes = managed == null || managed.managedArtifactBytes() == null ? 0 : managed.managedArtifactBytes();
        long unknownSize = managed == null || managed.unknownSizeCopyCount() == null ? 0 : managed.unknownSizeCopyCount();
        long metadataRecords = managed == null || managed.metadataRecordCount() == null ? 0 : managed.metadataRecordCount();
        return new AdminOverviewView.Summary(
                new AdminOverviewView.ReadableOrders(total, complete, incomplete, offline, abnormal, degraded),
                new AdminOverviewView.OrderStatistics(backedUpOrders, hotOrders, coldOrders),
                new AdminOverviewView.ReadableFiles(orderPackages, logicalFiles, managedBytes, unknownFiles, unknownSize,
                        invalidCopyMetadata, metadataRecords),
                new AdminOverviewView.TodayBackup(0, 0, 0, 0, 0));
    }

    private static boolean hasStoredCopy(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow row) {
        return row != null && ((row.hotCopyCount() != null && row.hotCopyCount() > 0)
                || (row.coldCopyCount() != null && row.coldCopyCount() > 0));
    }

    private static PackageDecision decidePackage(
            List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> rows,
            StorageVolumeAccessPolicy accessPolicy) {
        AdminOverviewQueryMapper.ArtifactReplicaCandidateRow representative = rows.getFirst();
        long readableCopies = rows.stream().map(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::readableCopyCount)
                .filter(Objects::nonNull).reduce(0L,Math::addExact);
        boolean readable = readableCopies > 0;
        long activeCopies=rows.stream().map(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::activeCopyCount)
                .filter(Objects::nonNull).reduce(0L,Math::addExact);
        long offlineCopies=rows.stream().map(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::expectedOfflineCopyCount)
                .filter(Objects::nonNull).reduce(0L,Math::addExact);
        long abnormalCopies=rows.stream().map(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::abnormalActiveCopyCount)
                .filter(Objects::nonNull).reduce(0L,Math::addExact);
        boolean expectedOffline = !readable && activeCopies>0 && offlineCopies==activeCopies && abnormalCopies==0;
        boolean abnormalActive = abnormalCopies>0;
        return new PackageDecision(representative, readable, expectedOffline, readableCopies, abnormalActive);
    }

    private static final Comparator<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> CANDIDATE_ORDER = Comparator
            .comparing((AdminOverviewQueryMapper.ArtifactReplicaCandidateRow row) -> row.backupCompletedAt() != null)
            .thenComparing(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::backupCompletedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::snapshotId,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow::packageId,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
    private static final Comparator<PackageDecision> PACKAGE_ORDER =
            Comparator.comparing(PackageDecision::row, CANDIDATE_ORDER);

    private record PackageDecision(AdminOverviewQueryMapper.ArtifactReplicaCandidateRow row,
                                   boolean readable, boolean expectedOffline, long readableCopyCount,
                                   boolean hasAbnormalActiveCopy) { }
}
