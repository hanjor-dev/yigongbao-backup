package com.yigongbao.backup.admin;
import cn.dev33.satoken.stp.StpUtil; import com.baomidou.mybatisplus.core.toolkit.Wrappers; import com.fasterxml.jackson.databind.ObjectMapper; import com.yigongbao.backup.cold.*; import com.yigongbao.backup.common.ApiResponse; import com.yigongbao.backup.job.*; import com.yigongbao.backup.order.ColdSupplementCoverageService; import com.yigongbao.backup.storage.volume.StorageVolumeEntity; import com.yigongbao.backup.storage.volume.StorageVolumeMapper; import com.yigongbao.backup.storage.volume.StorageVolumeStatus; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*; import java.time.LocalDateTime; import java.util.List; import java.util.UUID;
/** 管理员冷备磁盘、批次和状态流转接口。 */
@RestController @RequestMapping("/api/v1/admin/cold-batches") @RequiredArgsConstructor
public class AdminColdBatchController {private final ColdDiskMapper disks;private final ColdBatchMapper batches;private final ColdBatchWorkflowService workflow;private final ColdSupplementCoverageService supplementCoverage;private final StorageVolumeMapper volumes;private final PersistentJobCoordinator jobs;private final ObjectMapper mapper;private static String rid(){return UUID.randomUUID().toString();}private void admin(){StpUtil.checkRole("ADMIN");}
 @GetMapping("/disks") public ApiResponse<java.util.List<ColdDiskEntity>> disks(){admin();return ApiResponse.ok(disks.selectList(Wrappers.<ColdDiskEntity>lambdaQuery().eq(ColdDiskEntity::getStatus,"REGISTERED")),rid());}
 @PostMapping("/disks") public ApiResponse<ColdDiskEntity> registerDisk(@RequestBody RegisterDisk c){admin();throw legacyDisabled();}
 @GetMapping public ApiResponse<java.util.List<ColdBatchEntity>> list(){admin();return ApiResponse.ok(batches.selectList(Wrappers.<ColdBatchEntity>lambdaQuery().orderByDesc(ColdBatchEntity::getCreateTime)),rid());}
 @PostMapping public ApiResponse<ColdBatchEntity> create(@RequestBody Create c){admin();throw legacyDisabled();}
 @PostMapping("/{id}/start") public ApiResponse<ColdBatchEntity> start(@PathVariable("id") long id){admin();throw legacyDisabled();}
 /** 将指定订单包写入管理员选择的冷备盘；系统不会自动探测或操作其他盘。 */
 @PostMapping("/{id}/write") public ApiResponse<JobTask> write(@PathVariable("id") long id,@RequestBody Write c) {admin();throw new IllegalStateException("旧冷备复制写入流程已停用，请在存储卷管理中使用整盘封存");}
 @PostMapping("/{id}/complete") public ApiResponse<ColdBatchEntity> complete(@PathVariable("id") long id,@RequestBody Complete c){admin();throw legacyDisabled();}
 @PostMapping("/{id}/verify") public ApiResponse<ColdBatchEntity> verify(@PathVariable("id") long id){admin();throw legacyDisabled();}
 /** 冷备验证后由管理员显式提交整批热备包路径；删除成功后才落库为 CLEANED。 */
 @PostMapping({"/{id}/hot-cleanup", "/{id}/hot-cleanup-confirm"}) public ApiResponse<JobTask> cleanup(@PathVariable("id") long id,@RequestBody Cleanup c) {admin();throw new IllegalStateException("旧热备清理流程已停用；整盘封存不会复制或删除订单文件");}
 private ApiResponse<JobTask> job(String type,Object payload)throws Exception{return job(type,payload,null);}
 private static IllegalStateException legacyDisabled(){return new IllegalStateException("旧冷备复制流程已停用，请在存储卷管理中使用整盘封存");}
 private ApiResponse<JobTask> job(String type,Object payload,Long targetStorageVolumeId)throws Exception{long actor=StpUtil.getLoginIdAsLong();String json=mapper.writeValueAsString(payload);JobEntity j=jobs.create(type,JobIdempotency.key(type,actor,json),actor,json,targetStorageVolumeId);return ApiResponse.ok(new JobTask(j.getId(),j.getJobNo(),j.getStatus()),rid());}
 public record Create(Long diskId,LocalDateTime from,LocalDateTime to){}
 public record RegisterDisk(String diskCode,String volumeSerial,String markerUuid,String fileSystem,String label,String physicalLocation,Long capacityBytes,Long freeBytes){}
 public record Write(Long targetStorageVolumeId,List<String> orderPackages,String sha256){}
 public record Complete(int orders,int artifacts,long bytes,String sha256,List<String> orderCodes){}
 public record Cleanup(List<String> packageKeys){}
 public record JobTask(Long jobId,String jobNo,String status){}
 private static List<String> orderCodes(List<String> packages){return packages.stream().map(AdminColdBatchController::orderCode).filter(java.util.Objects::nonNull).distinct().toList();}
 private static String orderCode(String key){if(key==null)return null;String normalized=key.replace('\\','/');String[] parts=normalized.split("/");return parts.length>=3&&"orders".equals(parts[0])?parts[1]:null;}
}
