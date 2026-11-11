package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;
import com.yigongbao.backup.common.time.BusinessClock;
import java.time.Clock;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewAlertServiceTest {
    private final AdminOverviewAlertService service=new AdminOverviewAlertService(new BusinessClock(Clock.system(BusinessClock.ZONE)));
    @Test void reportsSetupRequiredWithoutFalseActiveVolumeAlertsBeforeFirstVolumeIsManaged() {
        OverviewStoragePolicy.Result storage=OverviewStoragePolicy.Result.emptyManagedWithoutActive(0,0);
        var result=service.evaluate(new AdminOverviewAlertService.AlertContext(
                storage,null,null,null,true,List.of(),List.of()));
        assertThat(result.health().level()).isEqualTo("SETUP_REQUIRED");
        assertThat(result.alerts()).isEmpty();
    }

    @Test void createsStableCurrentAlertsAndDoesNotAlertForExpectedOfflineCounts() {
        OverviewStoragePolicy.Result storage=OverviewStoragePolicy.Result.emptyManagedWithoutActive(1,1);
        AdminOverviewView.Summary summary=new AdminOverviewView.Summary(
                new AdminOverviewView.ReadableOrders(0,0,0,3,2,0),new AdminOverviewView.ReadableFiles(0,0,0,0,0),
                new AdminOverviewView.TodayBackup(0,0,0,0,0));
        var result=service.evaluate(new AdminOverviewAlertService.AlertContext(
                storage,summary,null,null,true,List.of(),List.of()));
        assertThat(result.health().level()).isEqualTo("CRITICAL");
        assertThat(result.alerts()).extracting(AdminOverviewView.Alert::code)
                .contains("ACTIVE_VOLUME_COUNT_INVALID","UNREADABLE_BACKUP_ARTIFACTS")
                .doesNotContain("EXPECTED_VOLUME_UNAVAILABLE");
        assertThat(result.alerts().stream().map(AdminOverviewView.Alert::alertKey).distinct().count())
                .isEqualTo(result.alerts().size());
        assertThat(result.alerts().stream().filter(a->"UNREADABLE_BACKUP_ARTIFACTS".equals(a.code())).findFirst().orElseThrow().actionQuery())
                .containsEntry("backupMode","REAL");
    }

    @Test void exposesRedundancyMetadataAndWarningQualityWithoutClaimingNormal() {
        AdminOverviewView.Summary summary=new AdminOverviewView.Summary(
                new AdminOverviewView.ReadableOrders(2,2,0,0,0,1),
                new AdminOverviewView.ReadableFiles(4,100,0,0,1),new AdminOverviewView.TodayBackup(0,0,0,0,0));
        var quality=List.of(new AdminOverviewView.DataQualityMessage("DIRTY","WARNING","summary",1,"存在脏数据"));
        var result=service.evaluate(new AdminOverviewAlertService.AlertContext(
                OverviewStoragePolicy.Result.emptyManagedWithoutActive(0,0),summary,null,null,true,quality,List.of()));
        assertThat(result.health().level()).isEqualTo("WARNING");
        assertThat(result.alerts()).extracting(AdminOverviewView.Alert::code)
                .contains("DEGRADED_ARTIFACT_REDUNDANCY","ARTIFACT_METADATA_INCOMPLETE");
    }

    @Test void routesArchiveAndVolumeIssuesWithTheCorrectIdentifier() {
        var issues=List.of(
                new AdminOverviewAlertService.CurrentArchiveIssue("ARCHIVE","封存异常","处理封存","VOLUME_ARCHIVE","9","cold-storage"),
                new AdminOverviewAlertService.CurrentArchiveIssue("GUARD","保障卷异常","处理保障卷","STORAGE_VOLUME","2","storage-volumes"));
        var result=service.evaluate(new AdminOverviewAlertService.AlertContext(
                OverviewStoragePolicy.Result.emptyManagedWithoutActive(0,0),null,null,null,true,List.of(),issues));
        assertThat(result.alerts().stream().filter(a->"ARCHIVE".equals(a.code())).findFirst().orElseThrow().actionQuery())
                .containsEntry("archiveId","9");
        assertThat(result.alerts().stream().filter(a->"GUARD".equals(a.code())).findFirst().orElseThrow().actionQuery())
                .containsEntry("volumeId","2");
    }
}
