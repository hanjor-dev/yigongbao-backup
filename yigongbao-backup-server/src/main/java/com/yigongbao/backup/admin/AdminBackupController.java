package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.Job;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.job.PersistentJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 订单备份任务管理接口；候选发现与任务创建由定时调度自动完成。 */
@RestController
@RequestMapping("/api/v1/admin/backup")
@RequiredArgsConstructor
public class AdminBackupController {
    private final PersistentJobService jobs;
    private final PersistentJobCoordinator coordinator;
    private final ObjectMapper mapper;

    /** 查询订单备份任务，每行代表一次调度批次。 */
    @GetMapping("/jobs")
    public ApiResponse<PersistentJobService.JobPage> listJobs(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        StpUtil.checkRole("ADMIN");
        return ApiResponse.ok(jobs.listBackupJobs(current, size), requestId());
    }

    /** 只将原任务中失败的订单创建为新的补偿任务。 */
    @PostMapping("/jobs/{id}/retry-failed")
    public ApiResponse<JobEntity> retryFailed(@PathVariable("id") long id) throws Exception {
        StpUtil.checkRole("ADMIN");
        List<Long> failedOrderIds = jobs.listOrders(id).stream()
                .filter(row -> "FAILED".equals(row.getStatus()) || "SIMULATION_FAILED".equals(row.getStatus()))
                .map(row -> row.getSourceOrderId())
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();
        if (failedOrderIds.isEmpty()) throw new IllegalStateException("该任务没有可重试的失败订单");
        long actor = StpUtil.getLoginIdAsLong();
        String payload = mapper.writeValueAsString(retryPayload(mapper, jobs.configJson(id), failedOrderIds));
        String key = JobIdempotency.key("ORDER_BACKUP_RETRY", actor, id + ":" + failedOrderIds);
        return ApiResponse.ok(coordinator.create("ORDER_BACKUP", key, actor, payload), requestId());
    }

    /** 重建失败订单任务参数，并保留原任务的模式和时间窗口，避免模拟重试意外变成真实下载。 */
    static ManualBackupJobHandler.Payload retryPayload(ObjectMapper mapper, String originalJson,
                                                       List<Long> failedOrderIds) throws Exception {
        ManualBackupJobHandler.Payload original = mapper.readValue(originalJson, ManualBackupJobHandler.Payload.class);
        return new ManualBackupJobHandler.Payload(failedOrderIds, failedOrderIds.size(), original.backupDate(),
                original.daysBefore(), original.orderCreatedBefore(), original.executionMode(), original.simulationRoot());
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }
}
