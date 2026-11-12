package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseEntity;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeCapacitySnapshotPolicy;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeHealthService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeMarkerService;
import com.yigongbao.backup.storage.volume.StorageVolumeOperationException;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 仅修复封存业务流程遗留的卷隔离状态，不处理或掩盖任何文件完整性异常。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageVolumeAbnormalStateRepairService {
    private static final List<String> ACTIVE_ARCHIVE_PHASES =
            List.of("REQUESTED", "SWITCHED", "MANIFEST_FROZEN", "VERIFYING", "COMMITTING");

    private final StorageVolumeMapper volumeMapper;
    private final VolumeArchiveMapper archiveMapper;
    private final VolumeArchiveItemMapper itemMapper;
    private final JobMapper jobMapper;
    private final JobOrderMapper jobOrderMapper;
    private final SpaceReservationMapper reservationMapper;
    private final StorageVolumeReadLeaseMapper leaseMapper;
    private final StorageVolumeMarkerService markerService;
    private final StorageVolumeHealthService healthService;
    private final AuditLogService auditLogService;

    @Transactional
    public StorageVolumeEntity repair(long volumeId, long actorId, String reason) {
        String normalizedReason = reason == null ? "" : reason.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (normalizedReason.isEmpty()) throw rejected("修复原因不能为空");
        if (normalizedReason.length() > 500) throw rejected("修复原因不能超过500字");
        long startedAt = System.nanoTime();
        log.info("存储卷异常状态修复开始｜存储卷ID={}｜操作人ID={}｜修复原因={}", volumeId, actorId, normalizedReason);
        try {
            StorageVolumeEntity volume = volumeMapper.findByIdForUpdate(volumeId);
            requireRepairableVolume(volume);
            VolumeArchiveEntity latest = latestArchive(volumeId);
            requireCommitProcessFailure(volume, latest);
            requireArchiveJobFailed(latest);
            requireNoActiveArchive(volumeId);
            requireFullyVerified(latest);
            requireNoOperationalReferences(volume);
            verifyIdentityAndHealth(volume);

            volume.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name());
            volume.setOperationRefId(null);
            volume.setUpdateBy(actorId);
            volumeMapper.updateById(volume);
            auditLogService.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, actorId, "ADMIN", null,
                    "STORAGE_VOLUME_REPAIR_ABNORMAL_STATE", "STORAGE_VOLUME", String.valueOf(volumeId),
                    latest.getJobId(), null, "SUCCESS",
                    "修复原因=" + normalizedReason + "；原封存ID=" + latest.getId() + "；原阶段=" + latest.getPhase(),
                    LocalDateTime.now()));
            log.info("存储卷异常状态修复成功｜存储卷ID={}｜原封存ID={}｜新操作状态={}｜操作人ID={}｜执行耗时={}毫秒",
                    volumeId, latest.getId(), volume.getOperationStatus(), actorId, elapsedMillis(startedAt));
            return volume;
        } catch (RuntimeException failure) {
            log.warn("存储卷异常状态修复失败｜存储卷ID={}｜操作人ID={}｜执行耗时={}毫秒｜异常类型={}｜异常原因={}",
                    volumeId, actorId, elapsedMillis(startedAt), failure.getClass().getSimpleName(), failure.getMessage());
            throw failure;
        }
    }

    private void requireRepairableVolume(StorageVolumeEntity volume) {
        if (volume == null || Integer.valueOf(1).equals(volume.getIsDeleted())) throw rejected("存储卷不存在");
        if (!StorageVolumeStatus.Tier.HOT.name().equals(volume.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())
                || !StorageVolumeStatus.Write.READ_ONLY.name().equals(volume.getWriteStatus())
                || !StorageVolumeStatus.Admin.ENABLED.name().equals(defaultAdmin(volume))
                || !StorageVolumeStatus.Operation.REPAIR_REQUIRED.name().equals(volume.getOperationStatus())) {
            throw rejected("只有 HOT/ONLINE/READ_ONLY/ENABLED/REPAIR_REQUIRED 卷可以修复异常状态");
        }
    }

    private VolumeArchiveEntity latestArchive(long volumeId) {
        VolumeArchiveEntity latest = archiveMapper.selectOne(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                .eq(VolumeArchiveEntity::getSourceVolumeId, volumeId)
                .eq(VolumeArchiveEntity::getIsDeleted, 0)
                .orderByDesc(VolumeArchiveEntity::getId).last("LIMIT 1"));
        if (latest == null) throw rejected("未找到可核验的封存失败记录");
        return latest;
    }

    private void requireCommitProcessFailure(StorageVolumeEntity volume, VolumeArchiveEntity latest) {
        if (!List.of("COMMIT_FAILED", "ABANDONED").contains(latest.getPhase())
                || !"ARCHIVE_COMMIT_FAILED".equals(latest.getFailureCode())) {
            throw rejected("只有文件已全部验证后的提交阶段流程异常可以直接修复");
        }
        if (volume.getOperationRefId() != null && !Objects.equals(volume.getOperationRefId(), latest.getId())) {
            throw rejected("存储卷关联了其他生命周期操作，不能修复异常状态");
        }
    }

    private void requireNoActiveArchive(long volumeId) {
        long active = archiveMapper.selectCount(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                .eq(VolumeArchiveEntity::getSourceVolumeId, volumeId)
                .in(VolumeArchiveEntity::getPhase, ACTIVE_ARCHIVE_PHASES)
                .eq(VolumeArchiveEntity::getIsDeleted, 0));
        if (active > 0) throw rejected("存储卷仍有进行中的封存尝试");
    }

    private void requireArchiveJobFailed(VolumeArchiveEntity archive) {
        JobEntity job = archive.getJobId() == null ? null : jobMapper.selectById(archive.getJobId());
        if (job == null || !"FAILED".equals(job.getStatus())) {
            throw rejected("关联封存任务尚未进入失败终态，不能修复异常状态");
        }
    }

    private void requireFullyVerified(VolumeArchiveEntity archive) {
        VolumeArchiveItemMapper.ArchiveVerificationSummary summary = itemMapper.summarizeVerification(archive.getId());
        long expected = archive.getManagedFileCount() == null ? -1L : archive.getManagedFileCount();
        long total = value(summary == null ? null : summary.totalCount());
        long verified = value(summary == null ? null : summary.verifiedCount());
        long failed = value(summary == null ? null : summary.failedCount());
        long incomplete = value(summary == null ? null : summary.incompleteCount());
        if (expected < 0 || total != expected || verified != expected || failed > 0 || incomplete > 0) {
            throw rejected("文件校验未全部通过，不能通过状态修复解除隔离，请修复文件后重新封存");
        }
    }

    private void requireNoOperationalReferences(StorageVolumeEntity volume) {
        long jobs = jobMapper.selectCount(Wrappers.<JobEntity>lambdaQuery()
                .eq(JobEntity::getTargetStorageVolumeId, volume.getId())
                .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS")
                .eq(JobEntity::getIsDeleted, 0));
        if (jobs > 0) throw rejected("存储卷仍有运行中任务");
        if (jobOrderMapper.countRunningSourceReferences(volume.getId()) > 0)
            throw rejected("存储卷仍被运行中恢复任务引用");
        if (volume.getCapacityPoolId() != null
                && reservationMapper.countActiveAt(volume.getCapacityPoolId(), LocalDateTime.now()) > 0)
            throw rejected("存储卷仍有活动空间预留");
        long leases = leaseMapper.selectCount(Wrappers.<StorageVolumeReadLeaseEntity>lambdaQuery()
                .eq(StorageVolumeReadLeaseEntity::getStorageVolumeId, volume.getId())
                .eq(StorageVolumeReadLeaseEntity::getStatus, "ACTIVE")
                .gt(StorageVolumeReadLeaseEntity::getExpiresAt, LocalDateTime.now())
                .eq(StorageVolumeReadLeaseEntity::getIsDeleted, 0));
        if (leases > 0) throw rejected("存储卷仍有活动读取租约");
    }

    private void verifyIdentityAndHealth(StorageVolumeEntity volume) {
        StorageVolumeMarkerService.VerificationResult marker;
        try {
            marker = markerService.verify(Path.of(volume.getRootPath()), markerService.expectedVolumeId(volume));
        } catch (RuntimeException failure) {
            throw rejected("卷标记校验失败：" + failure.getMessage());
        }
        if (!marker.valid()) throw rejected("卷标记校验失败：" + marker.reason());
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(volume);
        if (!health.accessible()) throw rejected("存储卷健康探测失败：" + health.errorMessage());
        if (!StorageVolumeCapacitySnapshotPolicy.legal(health)) throw rejected("存储卷容量探测结果无效");
        StorageVolumeCapacitySnapshotPolicy.apply(volume, health, LocalDateTime.now());
    }

    private static String defaultAdmin(StorageVolumeEntity volume) {
        return volume.getAdminStatus() == null ? StorageVolumeStatus.Admin.ENABLED.name() : volume.getAdminStatus();
    }

    private static long value(Long value) { return value == null ? 0L : value; }
    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
    private static StorageVolumeOperationException rejected(String message) {
        return new StorageVolumeOperationException(message);
    }
}
