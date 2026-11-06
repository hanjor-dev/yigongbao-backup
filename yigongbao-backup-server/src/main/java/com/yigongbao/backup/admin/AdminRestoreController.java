package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.job.JobExecutionLogQueryService;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 线上恢复入口：只持久化创建任务，文件上传和提交由后台执行器处理。 */
@RestController @RequestMapping("/api/v1/admin/restores") @RequiredArgsConstructor
public class AdminRestoreController {
 private final PersistentJobCoordinator jobs; private final PersistentJobService jobService; private final JobExecutionLogQueryService executionLogs; private final ObjectMapper mapper; private final RuntimeConfigService configs; private final OrderSnapshotMapper snapshots;
 /** 分页查询线上恢复任务；仅管理员可读取恢复执行证据。 */
 @GetMapping("/jobs") public ApiResponse<PersistentJobService.JobPage> listJobs(
         @RequestParam(value="current", defaultValue="1") long current,
         @RequestParam(value="size", defaultValue="20") long size) {
     StpUtil.checkRole("ADMIN");
     return ApiResponse.ok(jobService.listRestoreJobs(current, size), rid());
 }
 /** 查询恢复任务详情及其订单、文件执行证据。 */
 @GetMapping("/jobs/{id}") public ApiResponse<com.yigongbao.backup.job.Job> getJob(@PathVariable("id") long id) {
     StpUtil.checkRole("ADMIN");
     return ApiResponse.ok(jobService.getRestoreJob(id), rid());
 }
 @GetMapping("/jobs/{id}/orders") public ApiResponse<PersistentJobService.JobOrderPage> orders(
         @PathVariable("id") long id, @RequestParam(value="current", defaultValue="1") long current,
         @RequestParam(value="size", defaultValue="20") long size) {
     StpUtil.checkRole("ADMIN");
     return ApiResponse.ok(jobService.listRestoreOrders(id, current, size), rid());
 }
 @GetMapping("/jobs/{id}/files") public ApiResponse<List<com.yigongbao.backup.job.JobFileEntity>> files(@PathVariable("id") long id) {
     StpUtil.checkRole("ADMIN");
     return ApiResponse.ok(jobService.listRestoreFiles(id), rid());
 }
 /** 读取恢复任务自身的生命周期日志，不混入订单及文件明细。 */
 @GetMapping("/jobs/{id}/logs") public ApiResponse<JobExecutionLogQueryService.LogPage> taskLogs(
         @PathVariable("id") long id, @RequestParam(value="current", defaultValue="1") long current,
         @RequestParam(value="size", defaultValue="50") long size) {
     StpUtil.checkRole("ADMIN"); jobService.getRestoreJob(id);
     return ApiResponse.ok(executionLogs.taskPage(id, current, size), rid());
 }
 /** 读取一个恢复订单的完整过程日志，含 ZIP 清单、文件上传及重试信息。 */
 @GetMapping("/jobs/{id}/orders/{sourceOrderId}/logs") public ApiResponse<JobExecutionLogQueryService.LogPage> orderLogs(
         @PathVariable("id") long id, @PathVariable("sourceOrderId") long sourceOrderId,
         @RequestParam(value="current", defaultValue="1") long current,
         @RequestParam(value="size", defaultValue="50") long size) {
     StpUtil.checkRole("ADMIN"); jobService.getRestoreJob(id);
     return ApiResponse.ok(executionLogs.page(id, sourceOrderId, current, size), rid());
 }
 /** 分页读取某订单 ZIP 内的逐文件上传结果；历史任务没有该类证据时返回空页。 */
 @GetMapping("/jobs/{id}/orders/{sourceOrderId}/files") public ApiResponse<PersistentJobService.JobFilePage> orderFiles(
         @PathVariable("id") long id, @PathVariable("sourceOrderId") long sourceOrderId,
         @RequestParam(value="current", defaultValue="1") long current,
         @RequestParam(value="size", defaultValue="20") long size) {
     StpUtil.checkRole("ADMIN");
     return ApiResponse.ok(jobService.listRestoreOrderFiles(id, sourceOrderId, current, size), rid());
 }
 /** 仅以失败订单快照创建补偿任务，成功订单不会被重复上传或再次回写。 */
 @PostMapping("/jobs/{id}/retry-failed") public ApiResponse<RestoreTask> retryFailed(@PathVariable("id") long id) throws Exception {
     StpUtil.checkRole("ADMIN");
     List<Long> snapshotIds = jobService.listAllRestoreOrders(id).stream()
             .filter(row -> "FAILED".equals(row.getStatus()))
             .map(com.yigongbao.backup.job.JobOrderEntity::getSnapshotId)
             .filter(java.util.Objects::nonNull).distinct().sorted().toList();
     if (snapshotIds.isEmpty()) throw new IllegalStateException("该恢复任务没有可重试的失败订单");
     RestorePayload original = mapper.readValue(jobService.configJson(id), RestorePayload.class);
     return created(new RestorePayload(snapshotIds, null, null, original.conflictPolicy()));
 }
 @PostMapping public ApiResponse<RestoreTask> restore(@RequestBody RestoreCommand command, @RequestHeader(value="Idempotency-Key",required=false) String requestKey) throws Exception {StpUtil.checkRole("ADMIN");enabled();if(command==null||command.orderSnapshotId()==null||command.orderSnapshotId()<1)throw new IllegalArgumentException("恢复订单不能为空");rejectSimulation(List.of(command.orderSnapshotId()),null,null);return created(new RestorePayload(List.of(command.orderSnapshotId()),null,null,configs.get("restore.conflict-policy","REJECT")),requestKey);}
 @PostMapping("/batch") public ApiResponse<RestoreTask> restoreBatch(@RequestBody BatchRestoreCommand command, @RequestHeader(value="Idempotency-Key",required=false) String requestKey) throws Exception {StpUtil.checkRole("ADMIN");enabled();if(command==null||(command.orderSnapshotIds()==null||command.orderSnapshotIds().isEmpty())&&command.createdFrom()==null&&command.createdTo()==null)throw new IllegalArgumentException("必须指定订单或创建时间范围");List<Long> ids=command.orderSnapshotIds()==null?List.of():command.orderSnapshotIds();rejectSimulation(ids,command.createdFrom(),command.createdTo());return created(new RestorePayload(ids,command.createdFrom(),command.createdTo(),configs.get("restore.conflict-policy","REJECT")),requestKey);}
 private ApiResponse<RestoreTask> created(RestorePayload payload) throws Exception { return created(payload, null); }
 /** 同一次 HTTP 请求重发使用相同键；每次用户主动操作默认生成新键，不能复用历史失败任务。 */
 private ApiResponse<RestoreTask> created(RestorePayload payload, String requestKey) throws Exception {List<Long> ids=payload.orderSnapshotIds()==null?List.of():payload.orderSnapshotIds().stream().distinct().sorted().toList();RestorePayload normalized=new RestorePayload(ids,payload.createdFrom(),payload.createdTo(),payload.conflictPolicy());long actor=StpUtil.getLoginIdAsLong();String json=mapper.writeValueAsString(normalized);JobEntity job=jobs.create("RESTORE",JobIdempotency.key("RESTORE",actor,restoreRequestKey(requestKey)+"\n"+json),actor,json);return ApiResponse.ok(new RestoreTask(job.getId(),job.getJobNo(),job.getStatus()),rid());}
 static String restoreRequestKey(String supplied) { return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied.trim(); }
 private void enabled(){if(!configs.getBoolean("online.enabled",false))throw new IllegalStateException("线上通信已禁用");if(!configs.getBoolean("restore.enabled",true))throw new IllegalStateException("线上恢复功能已禁用");}
 /** 任务入队前拒绝零字节占位包，避免生成注定失败的恢复任务。 */
 private void rejectSimulation(List<Long> ids, LocalDateTime from, LocalDateTime to) {
  var query=Wrappers.<OrderSnapshotEntity>lambdaQuery().eq(OrderSnapshotEntity::getBackupMode,"METADATA_SIMULATION");
  if(ids!=null&&!ids.isEmpty())query.in(OrderSnapshotEntity::getId,ids);
  else query.ge(from!=null,OrderSnapshotEntity::getOrderCreatedAt,from).le(to!=null,OrderSnapshotEntity::getOrderCreatedAt,to);
  if(snapshots.selectCount(query)>0)throw new IllegalArgumentException("选中范围包含模拟备份，模拟包仅供测试下载，禁止线上恢复");
 }
 private static String rid(){return UUID.randomUUID().toString();}
 public record RestoreCommand(Long orderSnapshotId){} public record BatchRestoreCommand(List<Long> orderSnapshotIds,LocalDateTime createdFrom,LocalDateTime createdTo){} public record RestorePayload(List<Long> orderSnapshotIds,LocalDateTime createdFrom,LocalDateTime createdTo,String conflictPolicy){} public record RestoreTask(Long jobId,String jobNo,String status){}
}
