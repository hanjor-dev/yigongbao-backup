package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 后台执行已验证冷备批次的整批热备清理。 */
@Component
@RequiredArgsConstructor
public class HotCleanupJobHandler implements JobHandler {
    private final ColdBatchMapper batches;
    private final ColdBatchWorkflowService workflow;
    private final HotCleanupService cleanup;
    private final ObjectMapper mapper;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        ColdBatchEntity batch = batches.selectById(payload.batchId());
        if (batch == null || !"VERIFIED".equals(batch.getStatus())) throw new IllegalStateException("批次未通过验证");
        context.safePoint("HOT_CLEANUP_START", 0);
        for (String key : payload.packageKeys()) {
            context.fileStarted(null, key, "HOT_CLEANUP", 0);
        }
        cleanup.cleanup(payload.batchId(), batch.getBatchNo(), payload.packageKeys());
        for (String key : payload.packageKeys()) {
            context.fileSucceeded(null, key, "HOT_CLEANUP", 0);
        }
        workflow.markHotCleaned(payload.batchId());
        context.safePoint("HOT_CLEANUP_COMPLETED", payload.packageKeys().size());
    }

    public record Payload(Long batchId, List<String> packageKeys) {
    }
}
