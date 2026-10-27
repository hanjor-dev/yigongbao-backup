package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.order.ColdSupplementCoverageService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 后台执行冷备归档写出，并在任务生命周期内持有目标盘空间预留。 */
@Component
@RequiredArgsConstructor
public class ColdWriteJobHandler implements JobHandler {
    private final ColdBatchMapper batches;
    private final ColdBatchWorkflowService workflow;
    private final ColdBatchService writer;
    private final ColdSupplementCoverageService coverage;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        ColdBatchEntity batch = batches.selectById(payload.batchId());
        if (batch == null) {
            throw new IllegalArgumentException("批次不存在");
        }
        if ("COMPLETED".equals(batch.getStatus()) || "VERIFIED".equals(batch.getStatus())) {
            context.safePoint("COLD_WRITE_ALREADY_COMPLETED", payload.orderPackages().size());
            return;
        }
        long estimatedBytes = writer.estimateBatch(payload.orderPackages());
        SpaceReservation reservation = reservations.reserve("cold", estimatedBytes, job.getId());
        try {
            context.safePoint("COLD_WRITE_START", 0);
            if ("CREATED".equals(batch.getStatus())) {
                workflow.start(payload.batchId());
            } else if (!"WRITING".equals(batch.getStatus())) {
                throw new IllegalStateException("冷备批次状态不可写入");
            }
            for (String packageKey : payload.orderPackages()) {
                context.fileStarted(null, packageKey, "COLD_WRITE", estimatedBytes);
            }
            Path root = Path.of(payload.coldRoot()).toRealPath();
            Path archive = root.resolve(batch.getBatchNo() + ".zip");
            if (!Files.isRegularFile(archive)) {
                archive = writer.writeBatch(batch.getBatchNo(), root, payload.orderPackages());
            }
            context.safePoint("COLD_WRITE_VERIFY", payload.orderPackages().size());
            workflow.complete(payload.batchId(), payload.orderPackages().size(),
                    payload.orderPackages().size(), Files.size(archive), payload.sha256());
            for (String packageKey : payload.orderPackages()) {
                context.fileSucceeded(null, packageKey, "COLD_WRITE", estimatedBytes);
            }
            coverage.markCovered(payload.batchId(), orderCodes(payload.orderPackages()), job.getRequestedBy());
        } finally {
            reservations.release(reservation);
        }
    }

    private static List<String> orderCodes(List<String> keys) {
        return keys.stream().map(key -> {
            String[] parts = key.replace('\\', '/').split("/");
            return parts.length >= 3 && "orders".equals(parts[0]) ? parts[1] : null;
        }).filter(java.util.Objects::nonNull).distinct().toList();
    }

    public record Payload(Long batchId, String coldRoot, List<String> orderPackages, String sha256) {
    }
}
