package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.reservation.InMemorySpaceReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 后台执行 COS 清理试运行并固化结果。 */
@Component
public class CleanupDryRunJobHandler implements JobHandler {
    private final CleanupService cleanup;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;

    @Autowired
    public CleanupDryRunJobHandler(CleanupService cleanup, ObjectMapper mapper,
                                   SpaceReservationService reservations) {
        this.cleanup = cleanup;
        this.mapper = mapper;
        this.reservations = reservations;
    }

    public CleanupDryRunJobHandler(CleanupService cleanup, ObjectMapper mapper) {
        this(cleanup, mapper, noOpReservations());
    }

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        SpaceReservation reservation = reservations.reserve("hot", 1L, Math.max(1L, job.getId() == null ? 0L : job.getId()));
        try {
            context.fileStarted(null, "COS_DRY_RUN", "CLEANUP_EVALUATE", 1);
            context.safePoint("CLEANUP_EVALUATE", 0);
            CleanupClient.Evaluation result = cleanup.dryRun(payload.olderThanDays());
            context.result(mapper.writeValueAsString(result));
            context.fileSucceeded(null, 1);
            context.safePoint("CLEANUP_EVALUATED", 1);
        } finally {
            reservations.release(reservation);
        }
    }

    private static SpaceReservationService noOpReservations() {
        InMemorySpaceReservationService service = new InMemorySpaceReservationService();
        service.register("hot", Long.MAX_VALUE);
        return service;
    }

    public record Payload(int olderThanDays) {
    }
}
