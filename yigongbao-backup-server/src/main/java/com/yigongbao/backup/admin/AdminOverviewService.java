package com.yigongbao.backup.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.common.time.BusinessClock;
import com.yigongbao.backup.common.time.BusinessTimeCodec;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.config.ConfigService;
import com.yigongbao.backup.account.BkSyncCursorEntity;
import com.yigongbao.backup.account.BkSyncCursorMapper;
import com.yigongbao.backup.backup.BackupExecutionModeProvider;
import com.yigongbao.backup.storage.archive.*;
import com.yigongbao.backup.storage.volume.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 分区查询、失败隔离和 30 秒业务结果缓存。 */
@Service @RequiredArgsConstructor @Slf4j
public class AdminOverviewService {
    private final AdminOverviewQueryMapper queries; private final StorageVolumeMapper volumes;
    private final VolumeArchiveMapper archives; private final StorageVolumeAccessPolicy access;
    private final OverviewTimePolicy time; private final OverviewStoragePolicy storagePolicy;
    private final AdminOverviewActivityService activities; private final AdminOverviewAlertService alerts;
    private final RuntimeConfigService configs; private final BusinessClock clock; private final BkSyncCursorMapper cursors;
    private final BackupExecutionModeProvider executionMode;
    private volatile Cached cached;
    private volatile CompletableFuture<AdminOverviewView> inFlight;

    public AdminOverviewView overview(boolean refresh,String requestId){
        String safeRequest=requestId==null||requestId.isBlank()?UUID.randomUUID().toString():requestId;
        LocalDateTime now=clock.now(); Cached current=cached;
        if(!refresh&&current!=null&&Duration.between(current.cachedAt(),now).compareTo(Duration.ofSeconds(30))<0)
            return rebind(current.view(),safeRequest);
        CompletableFuture<AdminOverviewView> future; boolean owner=false;
        synchronized(this){
            current=cached;
            if(!refresh&&current!=null&&Duration.between(current.cachedAt(),now).compareTo(Duration.ofSeconds(30))<0)
                return rebind(current.view(),safeRequest);
            if(inFlight!=null)future=inFlight;
            else{future=new CompletableFuture<>();inFlight=future;owner=true;}
        }
        if(!owner)return rebind(future.join(),safeRequest);
        try{
            AdminOverviewView view=load(now,safeRequest);cached=new Cached(view,now);future.complete(view);return view;
        }catch(RuntimeException failure){future.completeExceptionally(failure);throw failure;}
        finally{synchronized(this){if(inFlight==future)inFlight=null;}}
    }

    private AdminOverviewView load(LocalDateTime now,String safeRequest){
        List<AdminOverviewView.SectionError> errors=new ArrayList<>();
        SectionResult<SummaryFacts> summary=section("summary",this::summary,safeRequest,errors);
        SectionResult<StorageFacts> storage=section("storage",this::storage,safeRequest,errors);
        SectionResult<List<AdminOverviewView.BackupTrendPoint>> trend=section("backupTrend",this::trend,safeRequest,errors);
        SectionResult<List<AdminOverviewView.Activity>> recent=section("recentActivities",this::recent,safeRequest,errors);
        SectionResult<AutomationFacts> automation=section("automation",this::automation,safeRequest,errors);
        List<AdminOverviewView.DataQualityMessage> quality=quality(storage.value(),trend.value(),automation.value(),summary.value());
        List<AdminOverviewAlertService.CurrentArchiveIssue> archiveIssues=storage.value()==null?List.of():archiveIssues(storage.value().volumes(),storage.value().archives());
        SectionResult<AdminOverviewAlertService.Evaluation> alert=section("activeAlerts",()->alerts.evaluate(new AdminOverviewAlertService.AlertContext(
                storage.value()==null?null:storage.value().result(),summary.value()==null?null:summary.value().view(),
                automation.value()==null?null:automation.value().backupSchedule(),
                automation.value()==null?null:automation.value().account(),
                summary.available()&&storage.available()&&automation.available(),quality,archiveIssues)),safeRequest,errors);
        AdminOverviewView.Health health=alert.value()==null?new AdminOverviewView.Health("UNKNOWN",0,0,0):alert.value().health();
        AdminOverviewView view=new AdminOverviewView(now,health,new AdminOverviewView.Sections(summary.status(),storage.status(),alert.status(),trend.status(),automation.status(),recent.status()),
                summary.value()==null?null:summary.value().view(),storage.value()==null?null:storage.value().result().storage(),alert.value()==null?null:alert.value().alerts(),trend.value(),automation.value()==null?null:automation.value().view(),recent.value(),new AdminOverviewView.DataQuality(quality.isEmpty(),quality),List.copyOf(errors));
        return view;
    }
    private SummaryFacts summary(){var base=AdminOverviewProjection.summary(queries.selectArtifactReplicaCandidates(),queries.selectManagedArtifactSummary(),access);var w=time.today();var row=queries.selectBackupResultBuckets(w.from(),w.to()).stream().findFirst().orElse(null);var today=row==null?new AdminOverviewView.TodayBackup(0,0,0,0,0):new AdminOverviewView.TodayBackup(n(row.completeSucceeded()),n(row.incompleteSucceeded()),n(row.failed()),n(row.skipped()),n(row.total()));return new SummaryFacts(new AdminOverviewView.Summary(base.readableOrders(),base.readableFiles(),today),queries.selectDataQualitySummary());}
    private StorageFacts storage(){List<StorageVolumeEntity> v=volumes.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery().eq(StorageVolumeEntity::getIsDeleted,0));List<VolumeArchiveEntity> a=archives.selectList(Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getIsDeleted,0));ConfigValue<Integer> threshold=intConfig("storage.volume-switch.threshold-percent",90,1);ConfigValue<Integer> interval=intConfig("storage.volume-health.interval-minutes",5,1);List<AdminOverviewView.DataQualityMessage> configQuality=java.util.stream.Stream.of(threshold,interval).filter(x->!x.valid()).map(x->new AdminOverviewView.DataQualityMessage("STORAGE_CONFIG_INVALID","WARNING","storage",1,x.message())).toList();return new StorageFacts(storagePolicy.project(v,a,threshold.value(),interval.value()),a,v,configQuality);}
    private List<AdminOverviewView.BackupTrendPoint> trend(){var w=time.sevenDays();Map<LocalDate,AdminOverviewQueryMapper.BackupResultBucketRow> r=new HashMap<>();queries.selectBackupResultBuckets(w.from(),w.to()).forEach(x->r.put(x.day(),x));Map<LocalDate,AdminOverviewQueryMapper.BackupBytesBucketRow>b=new HashMap<>();queries.selectBackupBytesBuckets(w.from(),w.to()).forEach(x->b.put(x.day(),x));List<AdminOverviewView.BackupTrendPoint> out=new ArrayList<>();for(int i=0;i<7;i++){LocalDate d=w.from().toLocalDate().plusDays(i);var x=r.get(d);var y=b.get(d);out.add(new AdminOverviewView.BackupTrendPoint(d,x==null?0:n(x.completeSucceeded()),x==null?0:n(x.incompleteSucceeded()),x==null?0:n(x.failed()),x==null?0:n(x.skipped()),y==null?0:n(y.addedPackageBytes()),y==null?0:n(y.unknownPackageCount())));}return out;}
    private List<AdminOverviewView.Activity> recent(){List<VolumeArchiveEntity>a=archives.selectList(Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getIsDeleted,0).orderByDesc(VolumeArchiveEntity::getUpdateTime).orderByDesc(VolumeArchiveEntity::getId).last("LIMIT 10"));return activities.merge(queries.selectRecentJobs(10),a);}
    private AutomationFacts automation(){
        ConfigValue<Boolean> onlineConfig=booleanConfig("online.enabled",false);
        ConfigValue<Boolean> backupSwitch=booleanConfig("backup.schedule.enabled",true);
        ConfigValue<Boolean> accountSwitch=booleanConfig("account.sync-enabled",true);
        ConfigValue<Boolean> cleanupSwitch=booleanConfig("cleanup.schedule.enabled",false);
        ConfigValue<String> startConfig=stringConfig("backup.schedule.start-time","05:00");
        ConfigValue<Integer> intervalConfig=intConfig("backup.schedule.interval-days",1,1);
        ConfigValue<Integer> syncConfig=intConfig("account.sync-delay-minutes",30,1);
        ConfigValue<Integer> cleanupInterval=intConfig("cleanup.schedule.interval-hours",24,1);
        boolean online=onlineConfig.value();
        boolean backupEnabled=online&&backupSwitch.value();
        boolean accountEnabled=online&&accountSwitch.value();
        boolean cleanupEnabled=online&&cleanupSwitch.value();
        BkSyncCursorEntity backup=cursors.find("BACKUP_CANDIDATE"),account=cursors.find("USER");
        boolean backupConfigValid=onlineConfig.valid()&&backupSwitch.valid()&&startConfig.valid()&&intervalConfig.valid()
                &&(!backupEnabled||(changedAt(onlineConfig.entry())!=null&&changedAt(backupSwitch.entry())!=null));
        LocalDateTime backupEnabledAt=max(changedAt(onlineConfig.entry()),changedAt(backupSwitch.entry()));
        OverviewTimePolicy.BackupScheduleState schedule=backupConfigValid
                ?time.backupSchedule(new OverviewTimePolicy.BackupScheduleInput(backupEnabled,backupEnabledAt,startConfig.value(),
                    intervalConfig.value(),backup==null?null:backup.getLastSuccessAt(),backup==null?null:backup.getSyncStatus()))
                :new OverviewTimePolicy.BackupScheduleState("UNKNOWN",null,null,false,"自动备份配置缺失、非法或没有生效时间");
        AdminOverviewView.AutomationItem backupItem=new AdminOverviewView.AutomationItem(schedule.state(),backupEnabled,executionMode.mode().name(),
                startConfig.value(),intervalConfig.value(),"DAYS",backup==null?null:backup.getLastItemCount(),backup==null?null:backup.getLastErrorMessage(),
                backup==null?null:backup.getLastAttemptAt(),backup==null?null:backup.getLastSuccessAt(),schedule.nextExpectedAt(),
                backupMessage(backupEnabled,schedule,backup),"backup",Map.of());
        int syncDelay=syncConfig.value();
        LocalDateTime onlineEnabledAt=changedAt(onlineConfig.entry()),accountSwitchAt=changedAt(accountSwitch.entry());
        LocalDateTime accountEnabledAt=onlineEnabledAt==null||accountSwitchAt==null?null:max(onlineEnabledAt,accountSwitchAt);
        LocalDateTime firstAccount=accountEnabledAt==null?null:accountEnabledAt.plusMinutes(syncDelay);
        boolean accountConfigValid=onlineConfig.valid()&&accountSwitch.valid()&&syncConfig.valid()
                &&(!accountEnabled||(onlineEnabledAt!=null&&accountSwitchAt!=null));
        String accountState=accountConfigValid?accountState(accountEnabled,account,firstAccount,syncDelay):"UNKNOWN";
        LocalDateTime nextAccount=accountEnabled?(account!=null&&account.getLastSuccessAt()!=null?account.getLastSuccessAt().plusMinutes(syncDelay):firstAccount):null;
        AdminOverviewView.AutomationItem accountItem=new AdminOverviewView.AutomationItem(accountState,accountEnabled,null,
                null,syncDelay,"MINUTES",account==null?null:account.getLastItemCount(),account==null?null:account.getLastErrorMessage(),
                account==null?null:account.getLastAttemptAt(),account==null?null:account.getLastSuccessAt(),nextAccount,
                accountMessage(accountEnabled,accountState,account),"accounts",Map.of("focus","sync"));
        AdminOverviewQueryMapper.RecentJobRow cleanup=queries.selectLatestAutoCleanup();
        int cleanupHours=cleanupInterval.value();
        boolean cleanupConfigValid=onlineConfig.valid()&&cleanupSwitch.valid()&&cleanupInterval.valid();
        String cleanupState=cleanupConfigValid?(cleanupEnabled?(cleanup==null?"IDLE":cleanup.status()):"DISABLED"):"UNKNOWN";
        AdminOverviewView.AutomationItem cleanupItem=new AdminOverviewView.AutomationItem(cleanupState,cleanupEnabled,null,
                null,cleanupHours,"HOURS",null,cleanup==null?null:cleanup.failureMessage(),
                cleanup==null?null:cleanup.requestedAt(),cleanup!=null&&"SUCCEEDED".equals(cleanup.status())?cleanup.finishedAt():null,null,
                cleanupConfigValid?(cleanupEnabled?(cleanup==null?"尚无自动清理任务":"最近自动清理任务 #"+cleanup.jobId()):"管理员已停用"):"自动清理配置缺失或非法","cleanup",Map.of());
        List<AdminOverviewView.DataQualityMessage> configQuality=new ArrayList<>();
        List.of(onlineConfig,backupSwitch,accountSwitch,cleanupSwitch,startConfig,intervalConfig,syncConfig,cleanupInterval).stream()
                .filter(v->!v.valid()).forEach(v->configQuality.add(new AdminOverviewView.DataQualityMessage(
                        "AUTOMATION_CONFIG_INVALID","BLOCKING","automation",1,v.message())));
        if(backupEnabled&&(changedAt(onlineConfig.entry())==null||changedAt(backupSwitch.entry())==null))configQuality.add(new AdminOverviewView.DataQualityMessage(
                "AUTOMATION_CONFIG_EFFECTIVE_TIME_MISSING","BLOCKING","automation",1,"线上总开关或自动备份开关缺少生效时间"));
        if(accountEnabled&&(onlineEnabledAt==null||accountSwitchAt==null))configQuality.add(new AdminOverviewView.DataQualityMessage(
                "AUTOMATION_CONFIG_EFFECTIVE_TIME_MISSING","BLOCKING","automation",1,"线上总开关或账号同步开关缺少生效时间"));
        return new AutomationFacts(new AdminOverviewView.Automation(backupItem,accountItem,cleanupItem),schedule,
                new AdminOverviewAlertService.AutomationFact(accountEnabled,accountState),List.copyOf(configQuality));
    }
    private String accountState(boolean enabled,BkSyncCursorEntity row,LocalDateTime first,int delay){
        if(!enabled)return "DISABLED";
        if(row==null)return first!=null&&!first.isAfter(clock.now())?"MISSING":"READY";
        if("FAILED".equals(row.getSyncStatus()))return "FAILED";
        if(time.accountSyncStale(new OverviewTimePolicy.AccountSyncInput(true,first,row.getLastSuccessAt(),delay)))return "STALE";
        return row.getSyncStatus()==null||"IDLE".equals(row.getSyncStatus())?"READY":row.getSyncStatus();
    }
    private static String backupMessage(boolean enabled,OverviewTimePolicy.BackupScheduleState state,BkSyncCursorEntity row){
        if("UNKNOWN".equals(state.state()))return safe(state.qualityMessage(),"自动备份配置状态未知");
        if(!enabled)return "管理员已停用";
        if("MISSED".equals(state.state()))return "已错过最近计划槽位，系统不会自动补跑";
        if("FAILED".equals(state.state()))return safe(row==null?null:row.getLastErrorMessage(),"最近候选扫描失败");
        if("CHECKPOINT_MISSING".equals(state.state()))return "尚无自动备份扫描检查点";
        return row==null?"等待首次计划扫描":"最近扫描处理 "+number(row.getLastItemCount())+" 个订单";
    }
    private static String accountMessage(boolean enabled,String state,BkSyncCursorEntity row){if("UNKNOWN".equals(state))return "账号同步配置缺失或非法";if(!enabled)return "管理员已停用";if("FAILED".equals(state))return safe(row==null?null:row.getLastErrorMessage(),"最近账号同步失败");if("MISSING".equals(state))return "尚无账号同步检查点";if("STALE".equals(state))return "账号同步数据已超过新鲜度阈值";return row==null?"等待首次账号同步":"最近同步处理 "+number(row.getLastItemCount())+" 个账号";}
    private ConfigValue<Boolean> booleanConfig(String key,boolean fallback){ConfigService.Entry entry=configEntry(key);if(entry==null||entry.value()==null)return new ConfigValue<>(fallback,false,entry,"配置 "+key+" 缺失");String value=entry.value().trim().toLowerCase(Locale.ROOT);return switch(value){case "true","1","yes","y","on"->new ConfigValue<>(true,true,entry,null);case "false","0","no","n","off"->new ConfigValue<>(false,true,entry,null);default->new ConfigValue<>(fallback,false,entry,"配置 "+key+" 不是有效布尔值");};}
    private ConfigValue<Integer> intConfig(String key,int fallback,int min){ConfigService.Entry entry=configEntry(key);if(entry==null||entry.value()==null)return new ConfigValue<>(fallback,false,entry,"配置 "+key+" 缺失");try{int value=Integer.parseInt(entry.value().trim());return value>=min?new ConfigValue<>(value,true,entry,null):new ConfigValue<>(fallback,false,entry,"配置 "+key+" 低于允许下限");}catch(NumberFormatException invalid){return new ConfigValue<>(fallback,false,entry,"配置 "+key+" 不是有效整数");}}
    private ConfigValue<String> stringConfig(String key,String fallback){ConfigService.Entry entry=configEntry(key);String value=entry==null||entry.value()==null?null:entry.value().trim();return value==null||value.isBlank()?new ConfigValue<>(fallback,false,entry,"配置 "+key+" 缺失"):new ConfigValue<>(value,true,entry,null);}
    private ConfigService.Entry configEntry(String key){return configs.entry(key);}
    private static LocalDateTime changedAt(ConfigService.Entry e){try{return e==null?null:BusinessTimeCodec.toLocalDateTime(e.changedAt());}catch(RuntimeException invalid){return null;}}
    private static LocalDateTime max(LocalDateTime a,LocalDateTime b){if(a==null)return b;if(b==null)return a;return a.isAfter(b)?a:b;}
    private static int number(Integer value){return value==null?0:value;}private static String safe(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    List<AdminOverviewAlertService.CurrentArchiveIssue> archiveIssues(List<StorageVolumeEntity> volumes,List<VolumeArchiveEntity> archives){
        Map<Long,StorageVolumeEntity> volumeById=new HashMap<>();volumes.stream().filter(Objects::nonNull).forEach(v->volumeById.put(v.getId(),v));
        Map<Long,VolumeArchiveEntity> archiveById=new HashMap<>();archives.stream().filter(Objects::nonNull).forEach(a->archiveById.put(a.getId(),a));
        List<AdminOverviewAlertService.CurrentArchiveIssue> out=new ArrayList<>();
        archives.stream().filter(Objects::nonNull).filter(a->List.of("PREPARE_FAILED","VERIFY_FAILED","COMMIT_FAILED").contains(a.getPhase()))
                .collect(java.util.stream.Collectors.toMap(VolumeArchiveEntity::getSourceVolumeId,a->a,(a,b)->n(a.getId())>=n(b.getId())?a:b))
                .values().stream().filter(a->{StorageVolumeEntity v=volumeById.get(a.getSourceVolumeId());return v!=null&&"REPAIR_REQUIRED".equals(v.getOperationStatus());})
                .forEach(a->out.add(new AdminOverviewAlertService.CurrentArchiveIssue("VOLUME_ARCHIVE_REPAIR_REQUIRED","封存失败且存储卷需要修复",safe(a.getFailureMessage(),"请处理最新封存失败并修复存储卷"),"VOLUME_ARCHIVE",String.valueOf(a.getId()),"cold-storage")));
        archives.stream().filter(this::activeArchive).forEach(a->{StorageVolumeEntity source=volumeById.get(a.getSourceVolumeId());if(!validArchiveSource(source,a)){String type=source==null?"VOLUME_ARCHIVE":"STORAGE_VOLUME";String id=String.valueOf(source==null?a.getId():source.getId());String route=source==null?"cold-storage":"storage-volumes";out.add(new AdminOverviewAlertService.CurrentArchiveIssue("VOLUME_ARCHIVE_STATE_INCONSISTENT","封存操作状态不一致","活动封存尝试的源卷未保持只读封存保护状态",type,id,route));}StorageVolumeEntity guard=volumeById.get(a.getSuccessorVolumeId());if(!validArchiveGuard(guard,a)){String type=guard==null?"VOLUME_ARCHIVE":"STORAGE_VOLUME";String id=String.valueOf(guard==null?a.getId():guard.getId());String route=guard==null?"cold-storage":"storage-volumes";out.add(new AdminOverviewAlertService.CurrentArchiveIssue("VOLUME_ARCHIVE_STATE_INCONSISTENT","封存操作状态不一致","活动封存尝试缺少合法的热备在线保障卷",type,id,route));}});
        volumes.stream().filter(v->"ARCHIVE_GUARD".equals(v.getOperationStatus())).forEach(v->{VolumeArchiveEntity a=archiveById.get(v.getOperationRefId());if(!validArchiveGuard(v,a))out.add(new AdminOverviewAlertService.CurrentArchiveIssue("VOLUME_ARCHIVE_STATE_INCONSISTENT","封存操作状态不一致","存储卷的封存保护引用与当前活动封存尝试不匹配","STORAGE_VOLUME",String.valueOf(v.getId()),"storage-volumes"));});
        return List.copyOf(out);
    }
    private boolean validArchiveGuard(StorageVolumeEntity volume,VolumeArchiveEntity archive){if(volume==null||!activeArchive(archive)||!Objects.equals(volume.getId(),archive.getSuccessorVolumeId()))return false;var guard=access.archiveGuard(volume,archive.getId(),archive.getSuccessorVolumeId(),archive.getPhase(),true);return guard!=null&&access.readable(volume,guard).allowed();}
    private boolean validArchiveSource(StorageVolumeEntity volume,VolumeArchiveEntity archive){return volume!=null&&!Integer.valueOf(1).equals(volume.getIsDeleted())&&Objects.equals(volume.getId(),archive.getSourceVolumeId())&&Objects.equals(volume.getOperationRefId(),archive.getId())&&"HOT".equals(volume.getTier())&&"ONLINE".equals(volume.getMountStatus())&&"READ_ONLY".equals(volume.getWriteStatus())&&(volume.getAdminStatus()==null||"ENABLED".equals(volume.getAdminStatus()))&&"SEALING".equals(volume.getOperationStatus());}
    private boolean activeArchive(VolumeArchiveEntity archive){return archive!=null&&!Integer.valueOf(1).equals(archive.getIsDeleted())&&List.of("REQUESTED","SWITCHED","MANIFEST_FROZEN","VERIFYING","COMMITTING").contains(archive.getPhase());}
    private static List<AdminOverviewView.DataQualityMessage> quality(StorageFacts storage,List<AdminOverviewView.BackupTrendPoint> trend,AutomationFacts automation,SummaryFacts summary){
        List<AdminOverviewView.DataQualityMessage> out=new ArrayList<>();
        if(storage!=null){long invalid=storage.volumes().stream().filter(v->v.getTotalBytes()==null||v.getUsableBytes()==null||v.getTotalBytes()<=0||v.getUsableBytes()<0||v.getUsableBytes()>v.getTotalBytes()).count();if(invalid>0)out.add(new AdminOverviewView.DataQualityMessage("STORAGE_CAPACITY_INVALID","WARNING","storage",invalid,"部分存储卷容量快照非法，已从容量汇总排除"));}
        if(storage!=null)out.addAll(storage.configQuality());
        if(trend!=null){long unknown=trend.stream().mapToLong(AdminOverviewView.BackupTrendPoint::unknownPackageCount).sum();if(unknown>0)out.add(new AdminOverviewView.DataQualityMessage("BACKUP_PACKAGE_SIZE_MISSING","WARNING","backupTrend",unknown,"部分订单包缺少容量，趋势容量未包含这些记录"));}
        if(summary!=null&&summary.view().readableFiles().invalidCopyMetadataCount()>0)out.add(new AdminOverviewView.DataQualityMessage("ARTIFACT_METADATA_INCOMPLETE","WARNING","summary",summary.view().readableFiles().invalidCopyMetadataCount(),"部分活动副本缺少卷、相对键、大小或校验信息"));
        if(summary!=null&&summary.quality()!=null){var q=summary.quality();if(n(q.realSnapshotSimulationPackageCount())>0)out.add(new AdminOverviewView.DataQualityMessage("BACKUP_MODE_CONTRADICTION","BLOCKING","summary",n(q.realSnapshotSimulationPackageCount()),"正式快照关联了模拟订单包"));if(n(q.missingCompletionTimePackageCount())>0)out.add(new AdminOverviewView.DataQualityMessage("BACKUP_COMPLETION_TIME_MISSING","WARNING","backupTrend",n(q.missingCompletionTimePackageCount()),"部分正式订单包缺少完成时间，未计入容量趋势"));if(n(q.missingVolumeCopyCount())>0)out.add(new AdminOverviewView.DataQualityMessage("ARTIFACT_VOLUME_REFERENCE_MISSING","WARNING","summary",n(q.missingVolumeCopyCount()),"部分活动副本引用的存储卷不存在"));}
        if(storage!=null){long inconsistent=archiveReferenceIssueCount(storage.volumes(),storage.archives());if(inconsistent>0)out.add(new AdminOverviewView.DataQualityMessage("VOLUME_ARCHIVE_REFERENCE_INCONSISTENT","BLOCKING","storage",inconsistent,"卷操作引用与封存尝试不一致"));}
        if(automation!=null&&automation.backupSchedule()!=null&&automation.backupSchedule().qualityMessage()!=null)out.add(new AdminOverviewView.DataQualityMessage("BACKUP_SCHEDULE_CONFIG_INVALID","BLOCKING","automation",1,automation.backupSchedule().qualityMessage()));
        if(automation!=null)out.addAll(automation.configQuality());
        return List.copyOf(out);
    }
    private static long archiveReferenceIssueCount(List<StorageVolumeEntity> volumes,List<VolumeArchiveEntity> archives){Map<Long,StorageVolumeEntity> volumeById=new HashMap<>();volumes.stream().filter(Objects::nonNull).forEach(v->volumeById.put(v.getId(),v));Map<Long,VolumeArchiveEntity> archiveById=new HashMap<>();archives.stream().filter(Objects::nonNull).forEach(a->archiveById.put(a.getId(),a));long invalidActive=archives.stream().filter(Objects::nonNull).filter(a->!Integer.valueOf(1).equals(a.getIsDeleted())&&List.of("REQUESTED","SWITCHED","MANIFEST_FROZEN","VERIFYING","COMMITTING").contains(a.getPhase())).filter(a->{StorageVolumeEntity source=volumeById.get(a.getSourceVolumeId()),guard=volumeById.get(a.getSuccessorVolumeId());boolean sourceValid=source!=null&&"SEALING".equals(source.getOperationStatus())&&Objects.equals(source.getOperationRefId(),a.getId());boolean guardValid=guard!=null&&"ARCHIVE_GUARD".equals(guard.getOperationStatus())&&Objects.equals(guard.getOperationRefId(),a.getId());return !sourceValid||!guardValid;}).count();long orphanGuards=volumes.stream().filter(Objects::nonNull).filter(v->"ARCHIVE_GUARD".equals(v.getOperationStatus())).filter(v->{VolumeArchiveEntity a=archiveById.get(v.getOperationRefId());return a==null||!Objects.equals(a.getSuccessorVolumeId(),v.getId())||!List.of("REQUESTED","SWITCHED","MANIFEST_FROZEN","VERIFYING","COMMITTING").contains(a.getPhase());}).count();return Math.addExact(invalidActive,orphanGuards);}
    private <T> SectionResult<T> section(String name,Supplier<T> query,String request,List<AdminOverviewView.SectionError> errors){long start=System.nanoTime();try{return new SectionResult<>("AVAILABLE",query.get());}catch(RuntimeException e){log.warn("管理概览分区查询失败｜requestId={}｜section={}｜durationMs={}｜exception={}",request,name,(System.nanoTime()-start)/1_000_000,e.getClass().getSimpleName());errors.add(new AdminOverviewView.SectionError(name,"OVERVIEW_SECTION_UNAVAILABLE",request,"该分区暂时无法读取"));return new SectionResult<>("UNAVAILABLE",null);}}
    private static AdminOverviewView rebind(AdminOverviewView v,String request){List<AdminOverviewView.SectionError> e=v.sectionErrors().stream().map(x->new AdminOverviewView.SectionError(x.section(),x.code(),request,x.message())).toList();return new AdminOverviewView(v.observedAt(),v.health(),v.sections(),v.summary(),v.storage(),v.activeAlerts(),v.backupTrend(),v.automation(),v.recentActivities(),v.dataQuality(),e);}
    private static long n(Long v){return v==null?0:v;} private record SectionResult<T>(String status,T value){boolean available(){return "AVAILABLE".equals(status);}}private record SummaryFacts(AdminOverviewView.Summary view,AdminOverviewQueryMapper.DataQualitySummaryRow quality){}private record StorageFacts(OverviewStoragePolicy.Result result,List<VolumeArchiveEntity> archives,List<StorageVolumeEntity> volumes,List<AdminOverviewView.DataQualityMessage> configQuality){}private record AutomationFacts(AdminOverviewView.Automation view,OverviewTimePolicy.BackupScheduleState backupSchedule,AdminOverviewAlertService.AutomationFact account,List<AdminOverviewView.DataQualityMessage> configQuality){}private record ConfigValue<T>(T value,boolean valid,ConfigService.Entry entry,String message){}private record Cached(AdminOverviewView view,LocalDateTime cachedAt){}
}
