package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
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
 private final PersistentJobCoordinator jobs; private final ObjectMapper mapper; private final RuntimeConfigService configs; private final OrderSnapshotMapper snapshots;
 @PostMapping public ApiResponse<RestoreTask> restore(@RequestBody RestoreCommand command) throws Exception {StpUtil.checkRole("ADMIN");enabled();if(command==null||command.orderSnapshotId()==null||command.orderSnapshotId()<1)throw new IllegalArgumentException("恢复订单不能为空");rejectSimulation(List.of(command.orderSnapshotId()),null,null);return created(new RestorePayload(List.of(command.orderSnapshotId()),null,null,configs.get("restore.conflict-policy","KEEP_ONLINE")));}
 @PostMapping("/batch") public ApiResponse<RestoreTask> restoreBatch(@RequestBody BatchRestoreCommand command) throws Exception {StpUtil.checkRole("ADMIN");enabled();if(command==null||(command.orderSnapshotIds()==null||command.orderSnapshotIds().isEmpty())&&command.createdFrom()==null&&command.createdTo()==null)throw new IllegalArgumentException("必须指定订单或创建时间范围");List<Long> ids=command.orderSnapshotIds()==null?List.of():command.orderSnapshotIds();rejectSimulation(ids,command.createdFrom(),command.createdTo());return created(new RestorePayload(ids,command.createdFrom(),command.createdTo(),configs.get("restore.conflict-policy","KEEP_ONLINE")));}
 private ApiResponse<RestoreTask> created(RestorePayload payload) throws Exception {List<Long> ids=payload.orderSnapshotIds()==null?List.of():payload.orderSnapshotIds().stream().distinct().sorted().toList();RestorePayload normalized=new RestorePayload(ids,payload.createdFrom(),payload.createdTo(),payload.conflictPolicy());long actor=StpUtil.getLoginIdAsLong();String json=mapper.writeValueAsString(normalized);JobEntity job=jobs.create("RESTORE",JobIdempotency.key("RESTORE",actor,json),actor,json);return ApiResponse.ok(new RestoreTask(job.getId(),job.getJobNo(),job.getStatus()),rid());}
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
