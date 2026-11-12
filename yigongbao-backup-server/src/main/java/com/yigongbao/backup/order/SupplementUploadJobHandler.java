package com.yigongbao.backup.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class SupplementUploadJobHandler implements JobHandler {
    private final SupplementService supplements;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;
    private final SupplementStagingService stagingFiles;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        Payload payload = mapper.readValue(job.getConfigSnapshotJson(), Payload.class);
        long volumeId = job.getTargetStorageVolumeId() == null ? payload.storageVolumeId() : job.getTargetStorageVolumeId();
        if (volumeId < 1) throw new IllegalStateException("补传任务未固化目标存储卷");
        Path staged = Path.of(payload.stagedPath()).toAbsolutePath().normalize();
        SpaceReservation reservation = null;
        try {
            staged = staged.toRealPath();
            context.orderStarted(payload.snapshotId(), payload.snapshotId());
            context.fileStarted(payload.snapshotId(), staged.toString(), "SUPPLEMENT", payload.size());
            context.safePoint("SUPPLEMENT_VALIDATE", 0);
            supplements.validateUpload(payload.fileName(), payload.size());
            reservation = reservations.reserve(volumeId, payload.size(), job.getId());
            String fileId;
            try (var input = Files.newInputStream(staged)) {
                fileId = supplements.uploadForSnapshot(payload.snapshotId(), payload.orderCode(), payload.fileName(),
                        input, payload.size(), job.getRequestedBy(), payload.contentType(), payload.remark(), volumeId);
            }
            SupplementService.SupplementInfo location = supplements.locationForSnapshot(payload.snapshotId(), fileId);
            context.fileAtVolume(payload.snapshotId(), fileId, "SUPPLEMENT", "SUCCEEDED",
                    payload.size(), payload.size(), null, volumeId, location.storageKey(), null);
            context.orderAtVolume(payload.snapshotId(), volumeId, location.storageKey());
            context.orderSucceeded();
            context.safePoint("SUPPLEMENT_COMMITTED", payload.size());
        } finally {
            stagingFiles.delete(staged);
            if (reservation != null) reservations.release(reservation);
        }
    }

    public record Payload(long snapshotId, String orderCode, String fileName, long size, String contentType,
                          String remark, String stagedPath, long storageVolumeId) {
        public Payload(long snapshotId, String orderCode, String fileName, long size, String contentType,
                       String remark, String stagedPath) {
            this(snapshotId, orderCode, fileName, size, contentType, remark, stagedPath, 0L);
        }
    }
}
