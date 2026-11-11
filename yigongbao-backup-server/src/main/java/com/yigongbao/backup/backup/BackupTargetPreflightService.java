package com.yigongbao.backup.backup;

import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.job.JobPreconditionException;
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

    public void requireWritable(long volumeId) {
        StorageVolumeEntity volume = volumes.selectById(volumeId);
        if (volume == null || Integer.valueOf(1).equals(volume.getIsDeleted())) {
            throw unavailable("目标热备存储卷不存在或已删除");
        }
        if (!StorageVolumeStatus.Tier.HOT.name().equals(volume.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())
                || !StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus())) {
            throw unavailable("目标热备存储卷已不再处于 HOT / ONLINE / ACTIVE 状态");
        }
        if (volume.getAdminStatus() != null
                && !StorageVolumeStatus.Admin.ENABLED.name().equals(volume.getAdminStatus())) {
            throw unavailable("目标热备存储卷已被管理员停用");
        }
        if (volume.getOperationStatus() != null
                && !StorageVolumeStatus.Operation.IDLE.name().equals(volume.getOperationStatus())) {
            throw unavailable("目标热备存储卷正在执行其他生命周期操作");
        }
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
}
