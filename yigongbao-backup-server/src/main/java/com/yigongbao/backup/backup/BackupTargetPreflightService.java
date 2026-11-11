package com.yigongbao.backup.backup;

import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobPreconditionException;
import com.yigongbao.backup.storage.archive.VolumeArchiveEntity;
import com.yigongbao.backup.storage.archive.VolumeArchiveMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeAccessPolicy;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeHealthService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMarkerService;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** 在任何线上清单或文件请求前，校验任务冻结卷此刻仍可安全写入。 */
@Service
@RequiredArgsConstructor
public class BackupTargetPreflightService {
    private final StorageVolumeMapper volumes;
    private final StorageVolumeMarkerService markers;
    private final StorageVolumeHealthService health;
    private final RuntimeConfigService configs;
    private final VolumeArchiveMapper archives;
    private final StorageVolumeAccessPolicy accessPolicy;

    public void requireWritable(long volumeId) {
        StorageVolumeEntity volume = volumes.selectById(volumeId);
        if (volume == null || Integer.valueOf(1).equals(volume.getIsDeleted())) {
            throw unavailable("目标热备存储卷不存在或已删除");
        }
        StorageVolumeAccessPolicy.AccessDecision access = accessPolicy.writable(volume, guard(volume));
        if (!access.allowed()) throw unavailable(access.message());
        StorageVolumeMarkerService.VerificationResult marker = markers.verify(
                Path.of(volume.getRootPath()), markers.expectedVolumeId(volume));
        if (!marker.valid()) throw unavailable("目标热备存储卷标记校验失败：" + marker.reason());
        StorageVolumeHealthService.HealthSnapshot snapshot = health.probe(volume);
        if (!snapshot.accessible()) throw unavailable("目标热备存储卷不可访问：" + snapshot.errorMessage());
        if (!snapshot.writable()) throw unavailable("目标热备存储卷不可写");
        int threshold = configs.getInt("storage.volume-switch.threshold-percent", 90, 1);
        if (threshold < 1 || threshold > 100) threshold = 90;
        boolean thresholdReached = snapshot.totalBytes() <= 0 || snapshot.usableBytes() <= 0
                || (snapshot.totalBytes() - snapshot.usableBytes()) * 100L >= snapshot.totalBytes() * threshold;
        if (thresholdReached) throw unavailable("目标热备存储卷容量不足或已达切换阈值");
    }

    private static JobPreconditionException unavailable(String message) {
        return new JobPreconditionException("STORAGE_VOLUME_UNAVAILABLE", message);
    }

    private StorageVolumeAccessPolicy.ArchiveGuardContext guard(StorageVolumeEntity volume) {
        if (volume == null || volume.getOperationRefId() == null
                || !StorageVolumeStatus.Operation.ARCHIVE_GUARD.name().equals(volume.getOperationStatus())) return null;
        VolumeArchiveEntity archive = archives.selectById(volume.getOperationRefId());
        return archive == null ? null : new StorageVolumeAccessPolicy.ArchiveGuardContext(
                archive.getId(), archive.getSuccessorVolumeId(), archive.getPhase(),
                !Integer.valueOf(1).equals(archive.getIsDeleted()));
    }
}
