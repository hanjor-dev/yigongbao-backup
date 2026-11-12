package com.yigongbao.backup.admin;

import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewActivityServiceTest {
    @Test void mergesJobsAndArchivesSortsNewestAndFallsBackForUnknownTypes() {
        LocalDateTime now=LocalDateTime.of(2026,11,11,17,30);
        var unknown=new AdminOverviewQueryMapper.RecentJobRow(1L,"J1","FUTURE_JOB","SUCCEEDED",1L,1L,0L,0L,0L,0L,now.minusHours(2),now.minusHours(2),now.minusHours(1),null,null,null,null,null);
        VolumeArchiveEntity archive=new VolumeArchiveEntity();archive.setId(9L);archive.setPhase("VERIFYING");archive.setCreateTime(now);archive.setIsDeleted(0);
        List<AdminOverviewView.Activity> activities=new AdminOverviewActivityService().merge(List.of(unknown),List.of(archive));
        assertThat(activities).hasSize(2);
        assertThat(activities.getFirst().sourceType()).isEqualTo("VOLUME_ARCHIVE");
        assertThat(activities.getFirst().actionRoute()).isEqualTo("cold-storage");
        assertThat(activities.get(1).name()).isEqualTo("其他任务");
        assertThat(activities.get(1).actionRoute()).isNull();
    }

    @Test void routesKnownJobTypesToTheirExistingDetailPages() {
        LocalDateTime now=LocalDateTime.of(2026,11,11,17,30);
        var backup=new AdminOverviewQueryMapper.RecentJobRow(2L,"J2","ORDER_BACKUP","SUCCEEDED",1L,1L,1L,0L,0L,0L,now,now,now,null,null,null,null,null);
        var restore=new AdminOverviewQueryMapper.RecentJobRow(3L,"J3","RESTORE","SUCCEEDED",1L,1L,1L,0L,0L,0L,now,now,now,null,null,null,null,null);
        List<AdminOverviewView.Activity> activities=new AdminOverviewActivityService().merge(List.of(backup,restore),List.of());
        assertThat(activities).extracting(AdminOverviewView.Activity::actionRoute).containsExactlyInAnyOrder("backup","restore");
        assertThat(activities).allSatisfy(item->assertThat(item.actionQuery()).containsKey("jobId"));
    }

    @Test void namesAutomaticCleanupAsCloudCleanup() {
        LocalDateTime now=LocalDateTime.of(2026,11,12,14,0);
        var cleanup=new AdminOverviewQueryMapper.RecentJobRow(6L,"J6","AUTO_CLEANUP","SUCCEEDED",1L,1L,1L,0L,0L,0L,now,now,now,null,null,null,null,null);

        List<AdminOverviewView.Activity> activities=new AdminOverviewActivityService().merge(List.of(cleanup),List.of());

        assertThat(activities).singleElement().extracting(AdminOverviewView.Activity::name).isEqualTo("云端清理");
    }

    @Test void excludesSupplementUploadsFromRecentActivities() {
        LocalDateTime now=LocalDateTime.of(2026,11,12,10,30);
        var supplement=new AdminOverviewQueryMapper.RecentJobRow(4L,"J4","SUPPLEMENT_UPLOAD","SUCCEEDED",1L,1L,0L,0L,0L,0L,now,now,now,null,null,null,null,null);
        var backup=new AdminOverviewQueryMapper.RecentJobRow(5L,"J5","ORDER_BACKUP","SUCCEEDED",1L,1L,1L,0L,0L,0L,now.minusMinutes(1),now.minusMinutes(1),now.minusMinutes(1),null,null,null,null,null);

        List<AdminOverviewView.Activity> activities=new AdminOverviewActivityService().merge(List.of(supplement,backup),List.of());

        assertThat(activities).extracting(AdminOverviewView.Activity::activityType)
                .containsExactly("ORDER_BACKUP");
    }
}
