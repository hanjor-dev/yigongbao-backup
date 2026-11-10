package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import com.yigongbao.backup.backup.ScheduledBackupService;
import com.yigongbao.backup.backup.BackupJobCreationService;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.Job;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.job.JobExecutionLogQueryService;
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
    private final BackupJobCreationService jobCreation;
    private final JobExecutionLogQueryService executionLogs;
    private final ScheduledBackupService scheduledBackups;
    private final ObjectMapper mapper;

    /** 查询订单备份任务，每行代表一次调度批次。 */
    @GetMapping("/jobs")
    public ApiResponse<PersistentJobService.JobPage> listJobs(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        StpUtil.checkRole("ADMIN");
        return ApiResponse.ok(jobs.listBackupJobs(current, size), requestId());
    }

    /**
     * 管理员立即执行一次自动备份扫描：复用定时采集规则，仅跳过每日计划时间限制。
     * 扫描同步完成后才返回，便于页面随即刷新查看本次创建的任务或“无待备份订单”结果。
     */
    @PostMapping("/schedule/run-now")
    public ApiResponse<Void> runScheduleNow() {
        StpUtil.checkRole("ADMIN");
        scheduledBackups.runNowByAdministrator();
        return ApiResponse.ok(null, requestId());
    }

    /** 读取订单备份任务自身的生命周期日志。 */
    @GetMapping("/jobs/{id}/logs")
    public ApiResponse<JobExecutionLogQueryService.LogPage> taskLogs(@PathVariable("id") long id,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        StpUtil.checkRole("ADMIN");
        jobs.getBackupJob(id);
        return ApiResponse.ok(executionLogs.taskPage(id, current, size), requestId());
    }

    /** 读取备份任务中单个订单的清单、下载、打包和回执过程日志。 */
    @GetMapping("/jobs/{id}/orders/{sourceOrderId}/logs")
    public ApiResponse<JobExecutionLogQueryService.LogPage> orderLogs(@PathVariable("id") long id,
            @PathVariable("sourceOrderId") long sourceOrderId,
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        StpUtil.checkRole("ADMIN");
        jobs.getBackupJob(id);
        return ApiResponse.ok(executionLogs.page(id, sourceOrderId, current, size), requestId());
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
        return ApiResponse.ok(jobCreation.createForOrders("ORDER_BACKUP", key, actor, payload, failedOrderIds.size()), requestId());
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
