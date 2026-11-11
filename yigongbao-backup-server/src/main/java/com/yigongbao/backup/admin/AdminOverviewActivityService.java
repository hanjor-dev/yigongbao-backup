package com.yigongbao.backup.admin;

import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public final class AdminOverviewActivityService {
    public List<AdminOverviewView.Activity> merge(List<AdminOverviewQueryMapper.RecentJobRow> jobs,List<VolumeArchiveEntity> archives){
        List<AdminOverviewView.Activity> result=new ArrayList<>();
        if(jobs!=null) jobs.stream().filter(Objects::nonNull).map(this::job).forEach(result::add);
        if(archives!=null) archives.stream().filter(Objects::nonNull).map(this::archive).forEach(result::add);
        return result.stream().sorted(Comparator.comparing(this::sortTime,Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10).toList();
    }
    private AdminOverviewView.Activity job(AdminOverviewQueryMapper.RecentJobRow j){
        String name=switch(j.jobType()==null?"":j.jobType()){case "ORDER_BACKUP"->"自动订单备份";case "MANUAL_BACKUP"->"手工订单备份";case "DEGRADED_BACKUP"->"降级订单备份";case "RESTORE"->"线上恢复";case "SUPPLEMENT_UPLOAD"->"补传文件";case "CLEANUP_DRY_RUN"->"清理预览";case "CLEANUP_EXECUTE"->"清理执行";case "AUTO_CLEANUP"->"自动清理";default->"其他任务";};
        String route=switch(j.jobType()==null?"":j.jobType()){case "ORDER_BACKUP","MANUAL_BACKUP","DEGRADED_BACKUP"->"backup";case "RESTORE"->"restore";case "CLEANUP_DRY_RUN","CLEANUP_EXECUTE","AUTO_CLEANUP"->"cleanup";default->null;};
        Map<String,String> query=route==null||"cleanup".equals(route)?Map.of():Map.of("jobId",String.valueOf(j.jobId()));
        return new AdminOverviewView.Activity("JOB:"+j.jobId(),"JOB",j.jobId(),j.jobType(),name,j.status(),j.progressCurrent(),j.progressTotal(),n(j.completeSucceeded()),n(j.incompleteSucceeded()),n(j.failed()),n(j.skipped()),j.requestedAt(),j.startedAt(),j.finishedAt(),j.storageVolumeName(),safe(j.failureMessage()),route,query);
    }
    private AdminOverviewView.Activity archive(VolumeArchiveEntity a){return new AdminOverviewView.Activity("VOLUME_ARCHIVE:"+a.getId(),"VOLUME_ARCHIVE",a.getId(),"VOLUME_ARCHIVE","整盘冷备封存",a.getPhase(),0L,Math.max(a.getManagedFileCount()==null?0:a.getManagedFileCount(),0),0,0,a.getFailureCode()==null?0:1,0,a.getCreateTime(),a.getVerificationStartedAt(),a.getArchivedAt()==null?a.getVerificationCompletedAt():a.getArchivedAt(),a.getDisplayNameSnapshot(),safe(a.getFailureMessage()),"cold-storage",Map.of("archiveId",String.valueOf(a.getId())));}
    private LocalDateTime sortTime(AdminOverviewView.Activity a){return a.finishedAt()!=null?a.finishedAt():a.startedAt()!=null?a.startedAt():a.requestedAt();}
    private static long n(Long v){return v==null?0:v;}private static String safe(String v){return v==null?null:v.length()>300?v.substring(0,300):v;}
}
