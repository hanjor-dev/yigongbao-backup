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
}
