package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import com.yigongbao.backup.account.BkSyncCursorMapper;
import com.yigongbao.backup.backup.BackupExecutionMode;
import com.yigongbao.backup.backup.BackupExecutionModeProvider;
import com.yigongbao.backup.config.ConfigService;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.archive.VolumeArchiveMapper;
import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminOverviewServiceTest {
    @Test void cachesBusinessFactsRebindsErrorsAndRefreshBypassesCache() {
        AdminOverviewQueryMapper queries=mock(AdminOverviewQueryMapper.class);
        when(queries.selectArtifactReplicaCandidates()).thenThrow(new IllegalStateException("database path secret"));
        when(queries.selectBackupResultBuckets(any(),any())).thenReturn(List.of());
        when(queries.selectBackupBytesBuckets(any(),any())).thenReturn(List.of());
        when(queries.selectRecentJobs(10)).thenReturn(List.of());
        BusinessClock clock=new BusinessClock(Clock.fixed(Instant.parse("2026-11-11T09:30:00Z"),BusinessClock.ZONE));
        StorageVolumeAccessPolicy access=new StorageVolumeAccessPolicy();OverviewTimePolicy time=new OverviewTimePolicy(clock);
        BackupExecutionModeProvider executionMode=mock(BackupExecutionModeProvider.class);when(executionMode.mode()).thenReturn(BackupExecutionMode.REAL);
        AdminOverviewService service=new AdminOverviewService(queries,mock(StorageVolumeMapper.class),mock(VolumeArchiveMapper.class),access,time,
                new OverviewStoragePolicy(access,time),new AdminOverviewActivityService(),new AdminOverviewAlertService(clock),mock(RuntimeConfigService.class),clock,mock(BkSyncCursorMapper.class),executionMode);

        AdminOverviewView first=service.overview(false,"req-1");
        AdminOverviewView cached=service.overview(false,"req-2");
        AdminOverviewView refreshed=service.overview(true,"req-3");

        assertThat(first.summary()).isNull();
        assertThat(first.sections().summary()).isEqualTo("UNAVAILABLE");
        assertThat(first.sectionErrors().getFirst().message()).doesNotContain("secret");
        assertThat(cached.observedAt()).isEqualTo(first.observedAt());
        assertThat(cached.sectionErrors()).extracting(AdminOverviewView.SectionError::requestId).containsOnly("req-2");
        assertThat(first.backupTrend()).hasSize(7);
        assertThat(first.sections().automation()).isEqualTo("AVAILABLE");
        assertThat(first.automation().backup().state()).isEqualTo("UNKNOWN");
        assertThat(first.dataQuality().messages()).extracting(AdminOverviewView.DataQualityMessage::code)
                .contains("AUTOMATION_CONFIG_INVALID");
        verify(queries,times(2)).selectArtifactReplicaCandidates();
        assertThat(refreshed.sectionErrors()).extracting(AdminOverviewView.SectionError::requestId).containsOnly("req-3");
    }

    @Test void validatesActiveArchiveAndOrphanGuardInBothDirections() {
        BusinessClock clock=new BusinessClock(Clock.fixed(Instant.parse("2026-11-11T09:30:00Z"),BusinessClock.ZONE));
        StorageVolumeAccessPolicy access=new StorageVolumeAccessPolicy();OverviewTimePolicy time=new OverviewTimePolicy(clock);
        BackupExecutionModeProvider executionMode=mock(BackupExecutionModeProvider.class);when(executionMode.mode()).thenReturn(BackupExecutionMode.REAL);
        AdminOverviewService service=new AdminOverviewService(mock(AdminOverviewQueryMapper.class),mock(StorageVolumeMapper.class),mock(VolumeArchiveMapper.class),access,time,
                new OverviewStoragePolicy(access,time),new AdminOverviewActivityService(),new AdminOverviewAlertService(clock),mock(RuntimeConfigService.class),clock,mock(BkSyncCursorMapper.class),executionMode);
        VolumeArchiveEntity archive=new VolumeArchiveEntity();archive.setId(9L);archive.setIsDeleted(0);archive.setPhase("VERIFYING");archive.setSourceVolumeId(1L);archive.setSuccessorVolumeId(2L);
        StorageVolumeEntity source=new StorageVolumeEntity();source.setId(1L);source.setIsDeleted(0);source.setTier("HOT");source.setMountStatus("ONLINE");source.setWriteStatus("READ_ONLY");source.setAdminStatus("ENABLED");source.setOperationStatus("SEALING");source.setOperationRefId(9L);
        StorageVolumeEntity idleSuccessor=new StorageVolumeEntity();idleSuccessor.setId(2L);idleSuccessor.setIsDeleted(0);idleSuccessor.setTier("HOT");idleSuccessor.setMountStatus("ONLINE");idleSuccessor.setWriteStatus("ACTIVE");idleSuccessor.setAdminStatus("ENABLED");idleSuccessor.setOperationStatus("IDLE");
        StorageVolumeEntity orphan=new StorageVolumeEntity();orphan.setId(3L);orphan.setIsDeleted(0);orphan.setTier("HOT");orphan.setMountStatus("ONLINE");orphan.setWriteStatus("ACTIVE");orphan.setAdminStatus("ENABLED");orphan.setOperationStatus("ARCHIVE_GUARD");orphan.setOperationRefId(99L);

        var issues=service.archiveIssues(List.of(source,idleSuccessor,orphan),List.of(archive));

        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(AdminOverviewAlertService.CurrentArchiveIssue::targetType)
                .containsOnly("STORAGE_VOLUME");
        assertThat(issues).extracting(AdminOverviewAlertService.CurrentArchiveIssue::targetId)
                .containsExactlyInAnyOrder("2","3");
        assertThat(issues).extracting(AdminOverviewAlertService.CurrentArchiveIssue::actionRoute)
                .containsOnly("storage-volumes");

        source.setOperationStatus("IDLE");
        assertThat(service.archiveIssues(List.of(source,idleSuccessor),List.of(archive)))
                .extracting(AdminOverviewAlertService.CurrentArchiveIssue::targetId).contains("1");
    }

    @Test void usesAllEnablementTimestampsAndSeparatesDirtyConfigFromConfigStoreFailure() {
        AdminOverviewQueryMapper queries=mock(AdminOverviewQueryMapper.class);when(queries.selectArtifactReplicaCandidates()).thenReturn(List.of());when(queries.selectBackupResultBuckets(any(),any())).thenReturn(List.of());when(queries.selectBackupBytesBuckets(any(),any())).thenReturn(List.of());when(queries.selectRecentJobs(10)).thenReturn(List.of());
        StorageVolumeMapper volumeMapper=mock(StorageVolumeMapper.class);doReturn(List.of()).when(volumeMapper).selectList(any());
        VolumeArchiveMapper archiveMapper=mock(VolumeArchiveMapper.class);doReturn(List.of()).when(archiveMapper).selectList(any());
        RuntimeConfigService configs=mock(RuntimeConfigService.class);
        Instant changed=Instant.parse("2026-11-10T01:00:00Z");
        Map<String,ConfigService.Entry> entries=new HashMap<>();
        entries.put("online.enabled",new ConfigService.Entry("online.enabled","true",1,1,null));
        entries.put("backup.schedule.enabled",new ConfigService.Entry("backup.schedule.enabled","true",1,1,changed));
        entries.put("account.sync-enabled",new ConfigService.Entry("account.sync-enabled","true",1,1,changed));
        entries.put("cleanup.schedule.enabled",new ConfigService.Entry("cleanup.schedule.enabled","false",1,1,changed));
        entries.put("backup.schedule.start-time",new ConfigService.Entry("backup.schedule.start-time","05:00",1,1,changed));
        entries.put("backup.schedule.interval-days",new ConfigService.Entry("backup.schedule.interval-days","1",1,1,changed));
        entries.put("account.sync-delay-minutes",new ConfigService.Entry("account.sync-delay-minutes","30",1,1,changed));
        entries.put("cleanup.schedule.interval-hours",new ConfigService.Entry("cleanup.schedule.interval-hours","24",1,1,changed));
        entries.put("storage.volume-switch.threshold-percent",new ConfigService.Entry("storage.volume-switch.threshold-percent","invalid",1,1,changed));
        entries.put("storage.volume-health.interval-minutes",new ConfigService.Entry("storage.volume-health.interval-minutes","5",1,1,changed));
        when(configs.entry(anyString())).thenAnswer(invocation->entries.get(invocation.getArgument(0,String.class)));
        BusinessClock clock=new BusinessClock(Clock.fixed(Instant.parse("2026-11-11T09:30:00Z"),BusinessClock.ZONE));StorageVolumeAccessPolicy access=new StorageVolumeAccessPolicy();OverviewTimePolicy time=new OverviewTimePolicy(clock);BackupExecutionModeProvider executionMode=mock(BackupExecutionModeProvider.class);when(executionMode.mode()).thenReturn(BackupExecutionMode.REAL);
        AdminOverviewService service=new AdminOverviewService(queries,volumeMapper,archiveMapper,access,time,new OverviewStoragePolicy(access,time),new AdminOverviewActivityService(),new AdminOverviewAlertService(clock),configs,clock,mock(BkSyncCursorMapper.class),executionMode);

        AdminOverviewView view=service.overview(true,"dirty-config");

        assertThat(view.sections().storage()).isEqualTo("AVAILABLE");
        assertThat(view.automation().backup().state()).isEqualTo("UNKNOWN");
        assertThat(view.automation().accountSync().state()).isEqualTo("UNKNOWN");
        assertThat(view.automation().accountSync().nextExpectedAt()).isNull();
        assertThat(view.dataQuality().messages()).extracting(AdminOverviewView.DataQualityMessage::code)
                .contains("STORAGE_CONFIG_INVALID","AUTOMATION_CONFIG_EFFECTIVE_TIME_MISSING");

        entries.put("online.enabled",new ConfigService.Entry("online.enabled","true",1,1,Instant.parse("2026-11-11T08:00:00Z")));
        AdminOverviewView enabledLater=service.overview(true,"enabled-later");
        assertThat(enabledLater.automation().backup().state()).isEqualTo("READY");
        assertThat(enabledLater.automation().backup().nextExpectedAt())
                .isEqualTo(LocalDateTime.of(2026,11,12,5,0));

        RuntimeConfigService failedConfigs=mock(RuntimeConfigService.class);when(failedConfigs.entry(anyString())).thenThrow(new IllegalStateException("config database unavailable"));
        AdminOverviewService failed=new AdminOverviewService(queries,volumeMapper,archiveMapper,access,time,new OverviewStoragePolicy(access,time),new AdminOverviewActivityService(),new AdminOverviewAlertService(clock),failedConfigs,clock,mock(BkSyncCursorMapper.class),executionMode);
        AdminOverviewView unavailable=failed.overview(true,"config-store-failed");
        assertThat(unavailable.sections().automation()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.sections().storage()).isEqualTo("UNAVAILABLE");
    }
}
