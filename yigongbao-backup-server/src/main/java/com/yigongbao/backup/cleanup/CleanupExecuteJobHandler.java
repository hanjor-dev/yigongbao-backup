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

/** 后台轮询并消费 COS 清理 permit。 */
@Component
public class CleanupExecuteJobHandler implements JobHandler {
    private final PermitPollingService poller;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;

    @Autowired
    public CleanupExecuteJobHandler(PermitPollingService poller, ObjectMapper mapper,
                                    SpaceReservationService reservations) {
        this.poller = poller;
        this.mapper = mapper;
        this.reservations = reservations;
    }

    /** 兼容无 Spring 容器的旧测试调用；生产运行使用持久化预留 bean。 */
    public CleanupExecuteJobHandler(PermitPollingService poller, ObjectMapper mapper) {
        this(poller, mapper, noOpReservations());
    }

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        SpaceReservation reservation = reservations.reserve("hot", 1L, Math.max(1L, job.getId() == null ? 0L : job.getId()));
        try {
            context.fileStarted(null, payload.taskId(), "COS_CLEANUP", 1);
            context.safePoint("POLL_PERMIT", 0);
            CleanupService.CleanupResult result = poller.pollAndExecute(payload.taskId());
            context.result(mapper.writeValueAsString(result));
            context.fileSucceeded(null, result.deleted());
            context.safePoint("CLEANUP_COMPLETED", 1);
        } finally {
            reservations.release(reservation);
        }
    }

    private static SpaceReservationService noOpReservations() {
        InMemorySpaceReservationService service = new InMemorySpaceReservationService();
        service.register("hot", Long.MAX_VALUE);
        return service;
    }

    public record Payload(String taskId) {
    }
}
