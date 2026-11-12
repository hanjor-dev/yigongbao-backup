package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil; import com.fasterxml.jackson.databind.ObjectMapper; import com.yigongbao.backup.audit.AuditLogService; import com.yigongbao.backup.backup.BackupJobCreationService; import com.yigongbao.backup.backup.ManualBackupJobHandler; import com.yigongbao.backup.common.ApiResponse; import com.yigongbao.backup.job.*; import com.yigongbao.backup.storage.archive.VolumeArchiveService; import jakarta.servlet.http.HttpServletRequest; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.web.bind.annotation.*; import java.time.Duration; import java.util.UUID;

/** 管理员长任务创建、查询和领取接口。 */
@RestController @RequestMapping("/api/v1/admin/jobs")
public class AdminJobController {
 private final PersistentJobService jobs; private final PersistentJobCoordinator coordinator; private final ObjectMapper mapper; private final BackupJobCreationService jobCreation; private final VolumeArchiveService volumeArchives; private final AuditLogService audit;
 public AdminJobController(PersistentJobService jobs,PersistentJobCoordinator coordinator,ObjectMapper mapper,BackupJobCreationService jobCreation,VolumeArchiveService volumeArchives){this(jobs,coordinator,mapper,jobCreation,volumeArchives,null);}
 @Autowired public AdminJobController(PersistentJobService jobs,PersistentJobCoordinator coordinator,ObjectMapper mapper,BackupJobCreationService jobCreation,VolumeArchiveService volumeArchives,AuditLogService audit){this.jobs=jobs;this.coordinator=coordinator;this.mapper=mapper;this.jobCreation=jobCreation;this.volumeArchives=volumeArchives;this.audit=audit;}
 private static String rid(){return UUID.randomUUID().toString();} private void admin(){StpUtil.checkRole("ADMIN");}
 /** 查询全部任务，按创建顺序倒序；不再要求前端猜测任务 ID。 */
 @GetMapping public ApiResponse<java.util.List<Job>> list(){admin();return ApiResponse.ok(jobs.list(),rid());}
 /** 创建备份长任务并记录订单、文件总量。 */
 @PostMapping public ApiResponse<JobEntity> create(@RequestBody CreateCommand c)throws Exception{admin();if(c==null||c.orders()==null||c.orders().isEmpty())throw new IllegalArgumentException("手工备份必须选择订单");java.util.List<Long> ids=c.orders().stream().map(OrderCommand::sourceOrderId).filter(java.util.Objects::nonNull).distinct().sorted().toList();if(ids.isEmpty())throw new IllegalArgumentException("手工备份订单为空");long actor=StpUtil.getLoginIdAsLong();String json=mapper.writeValueAsString(ManualBackupJobHandler.Payload.strictOrders(ids,jobCreation.autoDegradeOnRelationBroken()));return ApiResponse.ok(jobCreation.createForOrders("MANUAL_BACKUP",JobIdempotency.key("MANUAL_BACKUP",actor,json),actor,json,ids.size()),rid());}
 /** 查询任务状态和进度。 */
 @GetMapping("/{id}") public ApiResponse<Job> get(@PathVariable("id") long id){admin(); return ApiResponse.ok(jobs.get(id),rid());}
 @GetMapping("/{id}/orders") public ApiResponse<PersistentJobService.JobOrderPage> orders(@PathVariable("id") long id, @RequestParam(name="current", defaultValue="1") long current, @RequestParam(name="size", defaultValue="20") long size){admin();return ApiResponse.ok(jobs.listOrders(id,current,size),rid());}
 @GetMapping("/{id}/files") public ApiResponse<PersistentJobService.JobFilePage> files(
         @PathVariable("id") long id,
         @RequestParam(name="current", defaultValue="1") long current,
         @RequestParam(name="size", defaultValue="20") long size,
         @RequestParam(name="sourceOrderId", required=false) Long sourceOrderId){
  admin();return ApiResponse.ok(jobs.listFiles(id,sourceOrderId,current,size),rid());
 }
 /** 以租约方式领取任务，避免多个执行器重复处理。 */
 @PostMapping("/{id}/claim") public ApiResponse<Job> claim(@PathVariable("id") long id,@RequestBody ClaimCommand c){admin();return ApiResponse.ok(jobs.claim(id,c.owner(),Duration.ofSeconds(c.leaseSeconds())),rid());}
 @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable("id") long id,HttpServletRequest request){admin();long actor=StpUtil.getLoginIdAsLong();try{if(!volumeArchives.cancelQueuedJob(id,actor))coordinator.requestCancel(id);if(audit!=null)audit.appendActor(request,actor,"ADMIN","JOB_CANCEL","JOB",String.valueOf(id),id,"OK",null);return ApiResponse.ok(null,rid());}catch(RuntimeException failure){if(audit!=null)audit.appendActor(request,actor,"ADMIN","JOB_CANCEL","JOB",String.valueOf(id),id,"FAILED",failure.getMessage());throw failure;}}
 @PostMapping("/{id}/retry") public ApiResponse<JobEntity> retry(@PathVariable("id") long id,HttpServletRequest request){admin();long actor=StpUtil.getLoginIdAsLong();try{JobEntity result=coordinator.retry(id,actor);if(audit!=null)audit.appendActor(request,actor,"ADMIN","JOB_RETRY","JOB",String.valueOf(id),id,"OK",null);return ApiResponse.ok(result,rid());}catch(RuntimeException failure){if(audit!=null)audit.appendActor(request,actor,"ADMIN","JOB_RETRY","JOB",String.valueOf(id),id,"FAILED",failure.getMessage());throw failure;}}
 public record CreateCommand(int orderTotal,int fileTotal,java.util.List<OrderCommand> orders,java.util.List<FileCommand> files){} public record OrderCommand(Long sourceOrderId,Long snapshotId){} public record FileCommand(Long sourceOrderId,String assetIdentity,String step,Long bytesTotal,String platform,String bucket,String objectKey,String versionIdentity,String tempPath){} public record ClaimCommand(String owner,long leaseSeconds){}
}
