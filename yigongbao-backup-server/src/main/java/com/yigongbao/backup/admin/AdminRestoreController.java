package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 线上恢复入口：只持久化创建任务，文件上传和提交由后台执行器处理。 */
@RestController @RequestMapping("/api/v1/admin/restores") @RequiredArgsConstructor
public class AdminRestoreController {
 private final PersistentJobCoordinator jobs; private final ObjectMapper mapper;
 @PostMapping public ApiResponse<RestoreTask> restore(@RequestBody RestoreCommand command) throws Exception {StpUtil.checkRole("ADMIN");if(command==null||command.orderSnapshotId()==null||command.orderSnapshotId()<1)throw new IllegalArgumentException("恢复订单不能为空");return created(new RestorePayload(List.of(command.orderSnapshotId()),null,null));}
 @PostMapping("/batch") public ApiResponse<RestoreTask> restoreBatch(@RequestBody BatchRestoreCommand command) throws Exception {StpUtil.checkRole("ADMIN");if(command==null||(command.orderSnapshotIds()==null||command.orderSnapshotIds().isEmpty())&&command.createdFrom()==null&&command.createdTo()==null)throw new IllegalArgumentException("必须指定订单或创建时间范围");return created(new RestorePayload(command.orderSnapshotIds()==null?List.of():command.orderSnapshotIds(),command.createdFrom(),command.createdTo()));}
 private ApiResponse<RestoreTask> created(RestorePayload payload) throws Exception {List<Long> ids=payload.orderSnapshotIds()==null?List.of():payload.orderSnapshotIds().stream().distinct().sorted().toList();RestorePayload normalized=new RestorePayload(ids,payload.createdFrom(),payload.createdTo());long actor=StpUtil.getLoginIdAsLong();String json=mapper.writeValueAsString(normalized);JobEntity job=jobs.create("RESTORE",JobIdempotency.key("RESTORE",actor,json),actor,json);return ApiResponse.ok(new RestoreTask(job.getId(),job.getJobNo(),job.getStatus()),rid());}
 private static String rid(){return UUID.randomUUID().toString();}
 public record RestoreCommand(Long orderSnapshotId){} public record BatchRestoreCommand(List<Long> orderSnapshotIds,LocalDateTime createdFrom,LocalDateTime createdTo){} public record RestorePayload(List<Long> orderSnapshotIds,LocalDateTime createdFrom,LocalDateTime createdTo){} public record RestoreTask(Long jobId,String jobNo,String status){}
}
