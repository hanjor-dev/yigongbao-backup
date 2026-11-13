package com.yigongbao.backup.admin;

import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.volume.*;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;

/** 仅基于持久化卷与封存事实生成首页存储投影。 */
@Component
public final class OverviewStoragePolicy {
    private final StorageVolumeAccessPolicy access;
    private final OverviewTimePolicy time;
    public OverviewStoragePolicy(StorageVolumeAccessPolicy access, OverviewTimePolicy time) { this.access=access;this.time=time; }

    public Result project(List<StorageVolumeEntity> source, List<VolumeArchiveEntity> archives,
                          int threshold, int intervalMinutes) {
        List<StorageVolumeEntity> volumes=source==null?List.of():source.stream().filter(Objects::nonNull)
                .filter(v->!Integer.valueOf(1).equals(v.getIsDeleted())).toList();
        Map<Long,VolumeArchiveEntity> archiveById=new HashMap<>();
        if(archives!=null) archives.stream().filter(Objects::nonNull).filter(a->a.getId()!=null).forEach(a->archiveById.put(a.getId(),a));
        List<StorageVolumeEntity> active=volumes.stream().filter(v->"HOT".equals(v.getTier())&&"ACTIVE".equals(v.getWriteStatus())).toList();
        StorageVolumeEntity current=active.size()==1?active.getFirst():null;
        boolean activeWritable=current!=null&&access.writable(current,guard(current,archiveById)).allowed();
        boolean activeFresh=current!=null&&time.capacityFresh(current.getCapacityObservedAt(),intervalMinutes);
        BigDecimal activeUsed=current==null?null:StorageVolumeCapacitySnapshotPolicy.usedPercent(current.getTotalBytes(),current.getUsableBytes());
        boolean high=activeFresh&&activeUsed!=null&&activeUsed.compareTo(BigDecimal.valueOf(threshold))>=0;
        List<StorageVolumeEntity> standby=volumes.stream().filter(v->access.standbyReady(v,time.capacityFresh(v.getCapacityObservedAt(),intervalMinutes),threshold))
                .sorted(Comparator.comparing(StorageVolumeEntity::getPriority,Comparator.nullsLast(Integer::compareTo))).toList();
        List<StorageVolumeEntity> cold=volumes.stream().filter(v->"COLD".equals(v.getTier())).toList();
        long coldOnline=cold.stream().filter(v->"ONLINE".equals(v.getMountStatus())).count();
        long expectedOffline=volumes.stream().filter(access::expectedOffline).count();
        long repair=cold.stream().filter(v->"REPAIR_REQUIRED".equals(v.getOperationStatus())).count();
        long successArchives=archives==null?0:archives.stream().filter(a->a!=null&&!Integer.valueOf(1).equals(a.getIsDeleted())&&"SUCCEEDED".equals(a.getPhase())).count();
        LocalDateTime latestArchive=archives==null?null:archives.stream().filter(a->a!=null&&"SUCCEEDED".equals(a.getPhase()))
                .map(VolumeArchiveEntity::getArchivedAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        List<StorageVolumeEntity> capacity=volumes.stream().filter(v->"ONLINE".equals(v.getMountStatus()))
                .filter(v->v.getAdminStatus()==null||"ENABLED".equals(v.getAdminStatus())).filter(v->!"DISABLED".equals(v.getWriteStatus()))
                .filter(v->time.capacityFresh(v.getCapacityObservedAt(),intervalMinutes))
                .filter(v->StorageVolumeCapacitySnapshotPolicy.usedPercent(v.getTotalBytes(),v.getUsableBytes())!=null).toList();
        long total=capacity.stream().map(StorageVolumeEntity::getTotalBytes).reduce(0L,Math::addExact);
        long usable=capacity.stream().map(StorageVolumeEntity::getUsableBytes).reduce(0L,Math::addExact);
        AdminOverviewView.Storage view=new AdminOverviewView.Storage(volume(current,threshold,activeFresh),
                new AdminOverviewView.Standby(standby.size(),standby.isEmpty()?null:volume(standby.getFirst(),threshold,true)),
                new AdminOverviewView.ColdMedia(cold.size(),coldOnline,expectedOffline,repair,successArchives,latestArchive),
                new AdminOverviewView.OnlineCapacity(total-usable,usable,total,
                        StorageVolumeCapacitySnapshotPolicy.usedPercent(total,usable),capacity.size(),volumes.size()-capacity.size()));
        long unexpected=volumes.stream().filter(v->!access.expectedOffline(v)).filter(v->v.getAdminStatus()==null||"ENABLED".equals(v.getAdminStatus()))
                .filter(v->!"ONLINE".equals(v.getMountStatus())).count();
        return new Result(view,volumes.size(),active.size(),activeWritable,high,!activeFresh||activeUsed==null,standby.size(),unexpected);
    }
    private static AdminOverviewView.Volume volume(StorageVolumeEntity v,int threshold,boolean fresh){
        if(v==null)return null;BigDecimal used=StorageVolumeCapacitySnapshotPolicy.usedPercent(v.getTotalBytes(),v.getUsableBytes());
        Long usedBytes=used==null?null:v.getTotalBytes()-v.getUsableBytes();
        return new AdminOverviewView.Volume(v.getId(),v.getDisplayName(),v.getVolumeCode(),v.getMountStatus(),v.getWriteStatus(),v.getOperationStatus(),usedBytes,v.getUsableBytes(),v.getTotalBytes(),used,threshold,v.getLastProbedAt(),v.getCapacityObservedAt(),fresh,fresh?"当前快照":"历史快照");
    }
    private static StorageVolumeAccessPolicy.ArchiveGuardContext guard(StorageVolumeEntity v,Map<Long,VolumeArchiveEntity> map){
        VolumeArchiveEntity a=v.getOperationRefId()==null?null:map.get(v.getOperationRefId());
        return a==null?null:new StorageVolumeAccessPolicy.ArchiveGuardContext(a.getId(),a.getSuccessorVolumeId(),a.getPhase(),!Integer.valueOf(1).equals(a.getIsDeleted()));
    }
    public record Result(AdminOverviewView.Storage storage,long managedVolumeCount,long activeCount,
                         boolean activeWritable,boolean activeCapacityHigh,boolean activeCapacityUnknown,
                         long standbyReadyCount,long expectedOnlineUnavailableCount){
        public static Result emptyManagedWithoutActive(long managed,long expectedOffline){
            return new Result(new AdminOverviewView.Storage(null,new AdminOverviewView.Standby(0,null),new AdminOverviewView.ColdMedia(0,0,expectedOffline,0,0,null),new AdminOverviewView.OnlineCapacity(0L,0L,0L,null,0,managed)),managed,0,false,false,true,0,0);
        }
    }
}
