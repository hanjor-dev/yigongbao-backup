package com.yigongbao.backup.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewProjectionTest {
    @Test
    void fixesTheTopLevelResponseContract() throws Exception {
        AdminOverviewView view = new AdminOverviewView(LocalDateTime.of(2026, 11, 11, 17, 30),
                new AdminOverviewView.Health("NORMAL", 0, 0, 0),
                new AdminOverviewView.Sections("AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE"),
                null, null, List.of(), List.of(new AdminOverviewView.BackupTrendPoint(
                        LocalDate.of(2026, 11, 11), 1, 0, 0, 0, 1024, 0)), null, List.of(),
                new AdminOverviewView.DataQuality(true, List.of()), List.of());

        Map<String, Object> json = new ObjectMapper().registerModule(new JavaTimeModule())
                .convertValue(view, new TypeReference<>() { });

        assertThat(json).containsOnlyKeys("observedAt", "health", "sections", "summary", "storage",
                "activeAlerts", "backupTrend", "automation", "recentActivities", "dataQuality", "sectionErrors");
        assertThat(((Map<?, ?>) ((List<?>) json.get("backupTrend")).getFirst()).get("date"))
                .isEqualTo("2026-11-11");
    }

    @Test
    void selectsTheLatestReadablePackageAndFallsBackFromANewerUnreadablePackage() {
        LocalDateTime oldTime = LocalDateTime.of(2026, 11, 1, 8, 0);
        LocalDateTime newTime = oldTime.plusDays(1);
        List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> rows = List.of(
                row(1, 10, 100, oldTime, "COMPLETE", 5L, 1000, "VERIFIED", "HOT", "ONLINE", "ACTIVE", "IDLE"),
                row(1, 11, 101, newTime, "INCOMPLETE", 3L, 1001, "DAMAGED", "HOT", "ONLINE", "ACTIVE", "IDLE"),
                row(2, 20, 200, newTime, "INCOMPLETE", 7L, 2000, "VERIFIED", "HOT", "ONLINE", "ACTIVE", "IDLE")
        );

        AdminOverviewView.Summary summary = AdminOverviewProjection.summary(rows,
                new AdminOverviewQueryMapper.ManagedArtifactSummaryRow(3000L, 0L), new StorageVolumeAccessPolicy());

        assertThat(summary.readableOrders().total()).isEqualTo(2);
        assertThat(summary.readableOrders().complete()).isEqualTo(1);
        assertThat(summary.readableOrders().incomplete()).isEqualTo(1);
        assertThat(summary.readableFiles().logicalFileCount()).isEqualTo(12);
        assertThat(summary.readableOrders().complete() + summary.readableOrders().incomplete())
                .isEqualTo(summary.readableOrders().total());
    }

    @Test
    void keepsExpectedOfflineSeparateButLetsAnyDamagedReplicaWinAsAbnormal() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 1, 8, 0);
        AdminOverviewQueryMapper.ArtifactReplicaCandidateRow offline =
                row(1, 10, 100, time, "COMPLETE", 5L, 1000, "VERIFIED", "COLD", "OFFLINE", "READ_ONLY", "IDLE");
        AdminOverviewQueryMapper.ArtifactReplicaCandidateRow damaged =
                row(1, 10, 100, time, "COMPLETE", 5L, 1001, "DAMAGED", "HOT", "ONLINE", "ACTIVE", "IDLE");
        AdminOverviewQueryMapper.ArtifactReplicaCandidateRow onlyOffline =
                row(2, 20, 200, time, "COMPLETE", 5L, 2000, "VERIFIED", "COLD", "OFFLINE", "READ_ONLY", "IDLE");
        AdminOverviewQueryMapper.ArtifactReplicaCandidateRow noCopy =
                row(3, 30, 300, time, "COMPLETE", 5L, null, null, null, null, null, null);

        AdminOverviewView.Summary summary = AdminOverviewProjection.summary(
                List.of(offline, damaged, onlyOffline, noCopy),
                new AdminOverviewQueryMapper.ManagedArtifactSummaryRow(0L, 0L), new StorageVolumeAccessPolicy());

        assertThat(summary.readableOrders().total()).isZero();
        assertThat(summary.readableOrders().temporarilyOffline()).isEqualTo(1);
        assertThat(summary.readableOrders().abnormalUnreadable()).isEqualTo(2);
    }

    @Test
    void degradesOnlyWhenTheSelectedReadablePackageHasAnotherAbnormalActiveCopy() {
        LocalDateTime oldTime = LocalDateTime.of(2026, 11, 1, 8, 0);
        var singleHealthy = aggregate(1, 10, 100, oldTime, 1, 0);
        var oldAbnormal = aggregate(2, 20, 200, oldTime, 1, 1);
        var newerHealthy = aggregate(2, 21, 201, oldTime.plusDays(1), 1, 0);
        var mixed = aggregate(3, 30, 300, oldTime, 2, 1);

        AdminOverviewView.Summary summary = AdminOverviewProjection.summary(
                List.of(singleHealthy, oldAbnormal, newerHealthy, mixed),
                new AdminOverviewQueryMapper.ManagedArtifactSummaryRow(0L, 0L), new StorageVolumeAccessPolicy());

        assertThat(summary.readableOrders().total()).isEqualTo(3);
        assertThat(summary.readableOrders().degradedRedundancy()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizesCurrentBackedUpOrdersByTierAndCountsAssetMetadata() {
        LocalDateTime completed = LocalDateTime.of(2026, 11, 11, 8, 0);
        List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> rows = List.of(
                aggregateWithTiers(1, 10, 100, completed, 1, 0),
                aggregateWithTiers(2, 20, 200, completed, 0, 1),
                aggregateWithTiers(3, 30, 300, completed, 1, 1),
                aggregateWithTiers(4, 40, 400, completed, 0, 0)
        );

        AdminOverviewView.Summary summary = AdminOverviewProjection.summary(rows,
                new AdminOverviewQueryMapper.ManagedArtifactSummaryRow(4096L, 0L, 6L),
                new StorageVolumeAccessPolicy());
        Map<String, Object> json = new ObjectMapper().convertValue(summary, new TypeReference<>() { });

        assertThat(json).containsKey("orderStatistics");
        assertThat((Map<String, Object>) json.get("orderStatistics"))
                .containsEntry("total", 3L)
                .containsEntry("hot", 2L)
                .containsEntry("cold", 2L);
        assertThat((Map<String, Object>) json.get("readableFiles"))
                .containsEntry("orderPackageCount", 3L)
                .containsEntry("logicalFileCount", 3L)
                .containsEntry("metadataRecordCount", 6L);
    }

    @Test
    void countsStoredOrderPackagesSeparatelyFromDistinctBackedUpOrders() {
        LocalDateTime completed = LocalDateTime.of(2026, 11, 11, 8, 0);
        List<AdminOverviewQueryMapper.ArtifactReplicaCandidateRow> rows = List.of(
                aggregateWithTiers(1, 10, 100, completed, 1, 0),
                aggregateWithTiers(1, 11, 101, completed.plusHours(1), 1, 0)
        );

        AdminOverviewView.Summary summary = AdminOverviewProjection.summary(rows,
                new AdminOverviewQueryMapper.ManagedArtifactSummaryRow(2048L, 0L, 2L),
                new StorageVolumeAccessPolicy());

        assertThat(summary.orderStatistics().total()).isEqualTo(1);
        assertThat(summary.readableFiles().orderPackageCount()).isEqualTo(2);
    }

    private static AdminOverviewQueryMapper.ArtifactReplicaCandidateRow aggregate(
            long order, long snapshot, long pkg, LocalDateTime completed, long readable, long abnormal) {
        return new AdminOverviewQueryMapper.ArtifactReplicaCandidateRow(order, snapshot, pkg, completed,
                "COMPLETE", 1L, 100L, readable, 0L, readable + abnormal, 0L, abnormal);
    }

    private static AdminOverviewQueryMapper.ArtifactReplicaCandidateRow aggregateWithTiers(
            long order, long snapshot, long pkg, LocalDateTime completed, long hot, long cold) {
        long copies = hot + cold;
        return new AdminOverviewQueryMapper.ArtifactReplicaCandidateRow(order, snapshot, pkg, completed,
                "COMPLETE", 1L, 100L, hot, cold, copies, 0L, 0L, hot, cold);
    }

    private static AdminOverviewQueryMapper.ArtifactReplicaCandidateRow row(
            long order, long snapshot, long pkg, LocalDateTime completed, String completeness, Long files,
            Integer copyId, String copyState, String tier, String mount, String write, String operation) {
        boolean present=copyId!=null&&List.of("PRESENT","VERIFIED").contains(copyState);
        long active=copyId==null?0:1;
        long readable=present&&"ONLINE".equals(mount)&&("HOT".equals(tier)||("COLD".equals(tier)&&"READ_ONLY".equals(write)))&&"IDLE".equals(operation)?1:0;
        long offline=present&&"COLD".equals(tier)&&"OFFLINE".equals(mount)&&"READ_ONLY".equals(write)&&"IDLE".equals(operation)?1:0;
        long abnormal=copyId!=null&&!present?1:0;
        long hot=present&&"HOT".equals(tier)?1:0;
        long cold=present&&"COLD".equals(tier)?1:0;
        return new AdminOverviewQueryMapper.ArtifactReplicaCandidateRow(order, snapshot, pkg, completed,
                completeness, files, 100L,readable,offline,active,0L,abnormal,hot,cold);
    }
}
