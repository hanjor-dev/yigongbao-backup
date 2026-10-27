package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 后台执行冷备选择性回迁，并持久化任务级空间预留。 */
@Component
@RequiredArgsConstructor
public class ColdImportJobHandler implements JobHandler {
    private final ColdImportService imports;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        Set<String> orderIds = payload.orderIds() == null ? Set.of() : payload.orderIds();
        long estimatedBytes = imports.estimateRestoreBytes(payload.batchId(), orderIds);
        SpaceReservation reservation = reservations.reserve("hot", estimatedBytes, job.getId());
        try {
            context.fileStarted(null, payload.batchId(), "COLD_IMPORT", estimatedBytes);
            context.safePoint("COLD_IMPORT_VALIDATE", 0);
            int restored = imports.restore(payload.batchId(), orderIds);
            context.fileSucceeded(null, restored);
            context.safePoint("COLD_IMPORT_COMPLETED", restored);
        } finally {
            reservations.release(reservation);
        }
    }

    public record Payload(String batchId, Set<String> orderIds) {
    }
}
