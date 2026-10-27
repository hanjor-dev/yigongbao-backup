package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
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
    private final SpaceReservationService reservations;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        ColdBatchEntity batch = batches.selectById(payload.batchId());
        if (batch == null || !"VERIFIED".equals(batch.getStatus())) throw new IllegalStateException("批次未通过验证");
        SpaceReservation reservation = reservations.reserve("hot", cleanup.estimateBytes(payload.batchId()), job.getId());
        try {
            context.safePoint("HOT_CLEANUP_START", 0);
            cleanup.registerVerified(payload.batchId(), payload.packageKeys());
            for (String key : payload.packageKeys()) {
                context.fileStarted(null, key, "HOT_CLEANUP", 0);
            }
            cleanup.cleanup(payload.batchId(), batch.getBatchNo());
            for (String key : payload.packageKeys()) {
                context.fileSucceeded(null, key, "HOT_CLEANUP", 0);
            }
            workflow.markHotCleaned(payload.batchId());
            context.safePoint("HOT_CLEANUP_COMPLETED", payload.packageKeys().size());
        } finally {
            reservations.release(reservation);
        }
    }

    public record Payload(Long batchId, List<String> packageKeys) {
    }
}
