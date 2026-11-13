package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OverviewStoragePolicyTest {
    @Test void projectsActiveStandbyColdAndFreshOnlineCapacityWithoutProbing() {
        LocalDateTime now = LocalDateTime.of(2026,11,11,17,30);
        OverviewTimePolicy time = new OverviewTimePolicy(new BusinessClock(Clock.fixed(
                now.atZone(BusinessClock.ZONE).toInstant(), BusinessClock.ZONE)));
        OverviewStoragePolicy policy = new OverviewStoragePolicy(new StorageVolumeAccessPolicy(), time);
        StorageVolumeEntity active = volume(1,"HOT","ONLINE","ACTIVE","IDLE",100,20,now.minusMinutes(2));
        StorageVolumeEntity standby = volume(2,"HOT","ONLINE","STANDBY","IDLE",100,80,now.minusMinutes(2));
        StorageVolumeEntity cold = volume(3,"COLD","OFFLINE","READ_ONLY","IDLE",200,100,now.minusDays(1));
        VolumeArchiveEntity archive = new VolumeArchiveEntity(); archive.setPhase("SUCCEEDED"); archive.setArchivedAt(now.minusDays(2)); archive.setIsDeleted(0);

        OverviewStoragePolicy.Result result = policy.project(List.of(active,standby,cold),List.of(archive),90,5);

        assertThat(result.storage().activeVolume().id()).isEqualTo(1L);
        assertThat(result.storage().standby().readyCount()).isEqualTo(1);
        assertThat(result.storage().coldMedia().expectedOfflineCount()).isEqualTo(1);
        assertThat(result.storage().onlineCapacity().totalBytes()).isEqualTo(200L);
        assertThat(result.storage().onlineCapacity().usedBytes()).isEqualTo(100L);
    }

    @Test void staleCapacityIsUnknownAndNeverRaisesAHighWatermarkAlert() {
        LocalDateTime now=LocalDateTime.of(2026,11,11,17,30);
        OverviewTimePolicy time=new OverviewTimePolicy(new BusinessClock(Clock.fixed(now.atZone(BusinessClock.ZONE).toInstant(),BusinessClock.ZONE)));
        OverviewStoragePolicy.Result result=new OverviewStoragePolicy(new StorageVolumeAccessPolicy(),time)
                .project(List.of(volume(1,"HOT","ONLINE","ACTIVE","IDLE",100,1,now.minusHours(1))),List.of(),90,5);
        assertThat(result.activeCapacityUnknown()).isTrue();
        assertThat(result.activeCapacityHigh()).isFalse();
    }

    @Test void currentColdMediaCountUsesCurrentColdVolumesInsteadOfSuccessfulArchiveHistory() {
        LocalDateTime now=LocalDateTime.of(2026,11,11,17,30);
        OverviewTimePolicy time=new OverviewTimePolicy(new BusinessClock(Clock.fixed(
                now.atZone(BusinessClock.ZONE).toInstant(),BusinessClock.ZONE)));
        StorageVolumeEntity currentCold=volume(1,"COLD","OFFLINE","READ_ONLY","IDLE",200,100,now.minusDays(1));
        StorageVolumeEntity promotedBackToHot=volume(2,"HOT","ONLINE","STANDBY","IDLE",200,100,now.minusMinutes(2));
        VolumeArchiveEntity firstArchive=successfulArchive(now.minusDays(3));
        VolumeArchiveEntity secondArchive=successfulArchive(now.minusDays(2));

        OverviewStoragePolicy.Result result=new OverviewStoragePolicy(new StorageVolumeAccessPolicy(),time)
                .project(List.of(currentCold,promotedBackToHot),List.of(firstArchive,secondArchive),90,5);

        assertThat(result.storage().coldMedia().totalCount()).isEqualTo(1);
        assertThat(result.storage().coldMedia().successfulArchiveCount()).isEqualTo(2);
    }

    private static VolumeArchiveEntity successfulArchive(LocalDateTime archivedAt) {
        VolumeArchiveEntity archive=new VolumeArchiveEntity();
        archive.setPhase("SUCCEEDED");
        archive.setArchivedAt(archivedAt);
        archive.setIsDeleted(0);
        return archive;
    }

    private static StorageVolumeEntity volume(long id,String tier,String mount,String write,String op,long total,long usable,LocalDateTime observed){
        StorageVolumeEntity v=new StorageVolumeEntity();v.setId(id);v.setDisplayName("卷"+id);v.setVolumeCode("V"+id);v.setTier(tier);v.setMountStatus(mount);v.setWriteStatus(write);v.setAdminStatus("ENABLED");v.setOperationStatus(op);v.setTotalBytes(total);v.setUsableBytes(usable);v.setCapacityObservedAt(observed);v.setPriority((int)id);v.setIsDeleted(0);return v;
    }
}
