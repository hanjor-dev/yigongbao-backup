package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 自动COS清理长任务：固化候选快照后获取线上permit并逐对象执行。 */
@Component
@RequiredArgsConstructor
public class AutomaticCleanupJobHandler implements JobHandler {
    private final CleanupService cleanup;
    private final PermitPollingService permits;
    private final CleanupTaskService tasks;
    private final ObjectMapper mapper;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        context.safePoint("CLEANUP_EVALUATE", 0);
        CleanupClient.CleanupRequest request = new CleanupClient.CleanupRequest(
                payload.retentionDays(), true, payload.batchSize());
        CleanupClient.Evaluation evaluation = cleanup.evaluate(request);
        tasks.create(evaluation, request, job.getRequestedBy(), job.getIdempotencyKey());
        context.safePoint("CLEANUP_PERMIT", evaluation.objectKeys().size());
        CleanupService.CleanupResult result = permits.pollAndExecute(evaluation.taskId());
        if (!result.failedKeys().isEmpty()) {
            throw new IllegalStateException("COS清理存在失败对象: " + result.failedKeys().size());
        }
        context.result(mapper.writeValueAsString(result));
        context.safePoint("CLEANUP_COMPLETED", result.deleted());
    }

    /** 创建任务时固化的清理策略，避免执行中配置变化。 */
    public record Payload(int retentionDays, int batchSize) { }
}
