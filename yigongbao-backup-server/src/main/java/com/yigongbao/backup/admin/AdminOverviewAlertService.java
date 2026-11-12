package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import org.springframework.stereotype.Service;
import java.util.*;

/** 根据当前事实生成可恢复、可消失的告警；不读取历史失败任务。 */
@Service
public final class AdminOverviewAlertService {
    private final BusinessClock clock;
    public AdminOverviewAlertService(BusinessClock clock){this.clock=clock;}
    public Evaluation evaluate(AlertContext c){
        return evaluate(c,Set.of());
    }
    public Evaluation evaluate(AlertContext c,Set<String> dismissedAlertKeys){
        if(c==null||!c.factsAvailable())return new Evaluation(new AdminOverviewView.Health("UNKNOWN",0,0,0),List.of());
        Map<String,AdminOverviewView.Alert> alerts=new LinkedHashMap<>();
        OverviewStoragePolicy.Result s=c.storage();
        String activeId=s!=null&&s.storage()!=null&&s.storage().activeVolume()!=null?String.valueOf(s.storage().activeVolume().id()):"active";
        if(s!=null&&s.managedVolumeCount()>0&&s.activeCount()!=1)add(alerts,"CRITICAL","STORAGE","ACTIVE_VOLUME_COUNT_INVALID","活动热备卷数量异常","系统必须且只能有一个活动热备卷","STORAGE_VOLUME",activeId,"storage-volumes");
        else if(s!=null&&s.managedVolumeCount()>0&&!s.activeWritable())add(alerts,"CRITICAL","STORAGE","ACTIVE_VOLUME_UNAVAILABLE","活动热备卷不可用","当前活动卷不满足统一写入策略","STORAGE_VOLUME",activeId,"storage-volumes");
        if(s!=null&&s.activeCapacityHigh())add(alerts,"WARNING","STORAGE","ACTIVE_VOLUME_CAPACITY_HIGH","活动卷容量接近阈值","请准备切换备用卷","STORAGE_VOLUME",activeId,"storage-volumes");
        if(s!=null&&s.activeCount()==1&&s.activeCapacityUnknown())add(alerts,"WARNING","STORAGE","ACTIVE_VOLUME_CAPACITY_UNKNOWN","活动卷容量未知","最近成功容量快照缺失或过期","STORAGE_VOLUME",activeId,"storage-volumes");
        if(s!=null&&s.managedVolumeCount()>0&&(s.activeCapacityHigh()||!s.activeWritable())&&s.standbyReadyCount()==0)add(alerts,"WARNING","STORAGE","STANDBY_VOLUME_NOT_READY","没有可用备用卷","活动卷异常时无法自动接替","STORAGE_VOLUME","standby","storage-volumes");
        if(s!=null&&s.expectedOnlineUnavailableCount()>0)add(alerts,"WARNING","STORAGE","EXPECTED_VOLUME_UNAVAILABLE","预期在线卷不可用","有启用卷当前不在线","STORAGE_VOLUME","unavailable","storage-volumes");
        if(c.summary()!=null&&c.summary().readableOrders().abnormalUnreadable()>0)add(alerts,"CRITICAL","ARTIFACT","UNREADABLE_BACKUP_ARTIFACTS","存在不可读正式备份","部分正式订单包因当前异常完全不可读","ORDER","unreadable","orders");
        if(c.summary()!=null&&c.summary().readableOrders().degradedRedundancy()>0)add(alerts,"WARNING","ARTIFACT","DEGRADED_ARTIFACT_REDUNDANCY","备份副本冗余降级","部分可读订单包缺少健康冗余副本","ORDER","degraded-redundancy","orders");
        if(c.summary()!=null&&(c.summary().readableFiles().unknownFileCountPackageCount()>0||c.summary().readableFiles().unknownSizeCopyCount()>0||c.summary().readableFiles().invalidCopyMetadataCount()>0))add(alerts,"WARNING","ARTIFACT","ARTIFACT_METADATA_INCOMPLETE","备份制品元数据不完整","部分活动副本缺少文件数、大小、卷、相对键或校验信息","ORDER","metadata-incomplete","orders");
        if(c.backupSchedule()!=null){String st=c.backupSchedule().state();if("FAILED".equals(st))add(alerts,"WARNING","AUTOMATION","BACKUP_SCHEDULE_FAILED","自动备份最近执行失败","请进入订单备份页查看","AUTOMATION","backup","backup");else if("CHECKPOINT_MISSING".equals(st))add(alerts,"WARNING","AUTOMATION","BACKUP_SCHEDULE_CHECKPOINT_MISSING","自动备份尚无检查点","请确认计划任务已启用","AUTOMATION","backup","backup");else if("MISSED".equals(st))add(alerts,"WARNING","AUTOMATION","BACKUP_SCHEDULE_MISSED","自动备份已错过计划槽位","系统不会自动补跑，请按需立即扫描","AUTOMATION","backup","backup");}
        if(c.account()!=null&&c.account().enabled()){if("FAILED".equals(c.account().state()))add(alerts,"WARNING","AUTOMATION","ACCOUNT_SYNC_FAILED","账号同步最近失败","请检查账号同步状态","AUTOMATION","account","accounts");else if("STALE".equals(c.account().state()))add(alerts,"WARNING","AUTOMATION","ACCOUNT_SYNC_STALE","账号同步数据陈旧","最近成功时间已超过阈值","AUTOMATION","account","accounts");else if("MISSING".equals(c.account().state()))add(alerts,"WARNING","AUTOMATION","ACCOUNT_SYNC_CHECKPOINT_MISSING","账号同步尚无检查点","请确认同步功能配置","AUTOMATION","account","accounts");}
        if(c.archiveIssues()!=null)for(CurrentArchiveIssue issue:c.archiveIssues())add(alerts,"CRITICAL","ARCHIVE",issue.code(),issue.title(),issue.message(),issue.targetType(),issue.targetId(),issue.actionRoute());
        Set<String> dismissedCodes=new HashSet<>();
        if(dismissedAlertKeys!=null&&!dismissedAlertKeys.isEmpty()){
            alerts.keySet().removeAll(dismissedAlertKeys);
            dismissedAlertKeys.stream().filter(Objects::nonNull).map(key->{int separator=key.indexOf(':');return separator<0?key:key.substring(0,separator);}).forEach(dismissedCodes::add);
        }
        List<AdminOverviewView.DataQualityMessage> visibleQuality=c.quality()==null?List.of():c.quality().stream()
                .filter(q->q!=null&&!dismissedCodes.contains(q.code())).toList();
        long blocking=visibleQuality.stream().filter(q->"BLOCKING".equals(q.severity())).count();
        long qualityWarnings=visibleQuality.stream().filter(q->"WARNING".equals(q.severity())).count();
        long critical=alerts.values().stream().filter(a->"CRITICAL".equals(a.severity())).count(),warning=alerts.size()-critical;
        String level=critical>0?"CRITICAL":blocking>0?"UNKNOWN":warning>0||qualityWarnings>0?"WARNING":s!=null&&s.managedVolumeCount()==0?"SETUP_REQUIRED":"NORMAL";
        return new Evaluation(new AdminOverviewView.Health(level,alerts.size(),critical,warning),alerts.values().stream().sorted(Comparator.comparing((AdminOverviewView.Alert a)->!"CRITICAL".equals(a.severity())).thenComparing(AdminOverviewView.Alert::code)).toList());
    }
    private void add(Map<String,AdminOverviewView.Alert> out,String severity,String category,String code,String title,String message,String type,String id,String route){String key=code+":"+type+":"+id;out.putIfAbsent(key,new AdminOverviewView.Alert(key,severity,category,code,title,message,clock.now(),type,id,route,actionQuery(route,id,code)));}
    private static Map<String,String> actionQuery(String route,String id,String code){
        if("orders".equals(route)&&"UNREADABLE_BACKUP_ARTIFACTS".equals(code))return Map.of("backupMode","REAL","storageIssue","UNREADABLE");
        if("orders".equals(route))return Map.of("backupMode","REAL");
        if("cold-storage".equals(route))return Map.of("archiveId",id);
        if("storage-volumes".equals(route)&&id.matches("\\d+"))return Map.of("volumeId",id);
        if("storage-volumes".equals(route))return Map.of("focus",id);
        if("accounts".equals(route))return Map.of("focus","sync");
        return Map.of();
    }
    public record AlertContext(OverviewStoragePolicy.Result storage,AdminOverviewView.Summary summary,OverviewTimePolicy.BackupScheduleState backupSchedule,AutomationFact account,boolean factsAvailable,List<AdminOverviewView.DataQualityMessage> quality,List<CurrentArchiveIssue> archiveIssues){}
    public record AutomationFact(boolean enabled,String state){}
    public record CurrentArchiveIssue(String code,String title,String message,String targetType,String targetId,String actionRoute){}
    public record Evaluation(AdminOverviewView.Health health,List<AdminOverviewView.Alert> alerts){}
}
