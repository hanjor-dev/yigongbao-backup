package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.job.*;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseEntity;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseMapper;
import com.yigongbao.backup.storage.volume.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 封存预览与正式启动共用的阻塞条件检查器。 */
@Service
@RequiredArgsConstructor
public class VolumeArchiveBlockerService {
    private final StorageVolumeMapper volumeMapper;
    private final JobMapper jobMapper;
    private final JobOrderMapper jobOrderMapper;
    private final SpaceReservationMapper reservationMapper;
    private final StorageVolumeReadLeaseMapper leaseMapper;

    public List<Blocker> inspect(long sourceId, Long successorId) {
        List<Blocker> blockers = new ArrayList<>();
        StorageVolumeEntity source = volumeMapper.selectById(sourceId);
        if (source == null || Integer.valueOf(1).equals(source.getIsDeleted())) return List.of(new Blocker("SOURCE_NOT_FOUND", "源卷不存在"));
        if (!"HOT".equals(source.getTier()) || !"ONLINE".equals(source.getMountStatus()))
            blockers.add(new Blocker("SOURCE_NOT_READY", "源卷必须是 HOT/ONLINE"));
        if (!List.of(StorageVolumeStatus.Write.ACTIVE.name(), StorageVolumeStatus.Write.READ_ONLY.name())
                .contains(source.getWriteStatus())) {
            String message = StorageVolumeStatus.Write.STANDBY.name().equals(source.getWriteStatus())
                    ? "备用卷不能直接整盘封存，请先取消备用并转为只读"
                    : "整盘封存源卷必须是 ACTIVE 活动卷或 READ_ONLY 只读卷";
            blockers.add(new Blocker("SOURCE_WRITE_STATE_NOT_READY", message));
        }
        if (source.getOperationStatus() != null && !"IDLE".equals(source.getOperationStatus())
                && !"REPAIR_REQUIRED".equals(source.getOperationStatus()))
            blockers.add(new Blocker("SOURCE_BUSY", "源卷正在执行其他操作"));
        long jobs = jobMapper.selectCount(Wrappers.<JobEntity>lambdaQuery()
                .eq(JobEntity::getTargetStorageVolumeId, sourceId)
                .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS")
                .eq(JobEntity::getIsDeleted, 0));
        if (jobs > 0 || jobOrderMapper.countRunningSourceReferences(sourceId) > 0)
            blockers.add(new Blocker("RUNNING_TASK", "存储卷仍被非终态任务引用"));
        if (source.getCapacityPoolId() != null && reservationMapper.countActiveAt(source.getCapacityPoolId(), LocalDateTime.now()) > 0)
            blockers.add(new Blocker("ACTIVE_RESERVATION", "存储卷仍有活动空间预留"));
        if (leaseMapper.selectCount(Wrappers.<StorageVolumeReadLeaseEntity>lambdaQuery()
                .eq(StorageVolumeReadLeaseEntity::getStorageVolumeId, sourceId)
                .eq(StorageVolumeReadLeaseEntity::getStatus, "ACTIVE")
                .gt(StorageVolumeReadLeaseEntity::getExpiresAt, LocalDateTime.now())
                .eq(StorageVolumeReadLeaseEntity::getIsDeleted, 0)) > 0)
            blockers.add(new Blocker("ACTIVE_READ_LEASE", "存储卷仍有活动读取租约"));
        if ("ACTIVE".equals(source.getWriteStatus())) {
            StorageVolumeEntity successor = successorId == null ? null : volumeMapper.selectById(successorId);
            if (successor == null || !"HOT".equals(successor.getTier()) || !"ONLINE".equals(successor.getMountStatus())
                    || !"STANDBY".equals(successor.getWriteStatus()) || sourceId == successorId)
                blockers.add(new Blocker("INVALID_SUCCESSOR", "ACTIVE 卷封存必须选择 HOT/ONLINE/STANDBY 接替卷"));
        } else {
            StorageVolumeEntity successor = successorId == null ? null : volumeMapper.selectById(successorId);
            if (successor == null || !"HOT".equals(successor.getTier()) || !"ONLINE".equals(successor.getMountStatus())
                    || !"ACTIVE".equals(successor.getWriteStatus()))
                blockers.add(new Blocker("INVALID_SUCCESSOR", "整盘封存必须选择当前 HOT/ONLINE/ACTIVE 保障卷"));
        }
        if (successorId != null && successorId != sourceId) {
            StorageVolumeEntity successor = volumeMapper.selectById(successorId);
            if (successor != null && successor.getOperationStatus() != null
                    && !StorageVolumeStatus.Operation.IDLE.name().equals(successor.getOperationStatus())) {
                blockers.add(new Blocker("SUCCESSOR_BUSY", "接替卷正在保障其他封存任务或执行生命周期操作"));
            }
            long successorJobs = jobMapper.selectCount(Wrappers.<JobEntity>lambdaQuery()
                    .eq(JobEntity::getTargetStorageVolumeId, successorId)
                    .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS")
                    .eq(JobEntity::getIsDeleted, 0));
            if (successorJobs > 0 || jobOrderMapper.countRunningSourceReferences(successorId) > 0)
                blockers.add(new Blocker("SUCCESSOR_RUNNING_TASK", "接替卷仍被非终态任务引用"));
            if (successor != null && successor.getCapacityPoolId() != null
                    && reservationMapper.countActiveAt(successor.getCapacityPoolId(), LocalDateTime.now()) > 0)
                blockers.add(new Blocker("SUCCESSOR_RESERVATION", "接替卷仍有活动空间预留"));
            if (leaseMapper.selectCount(Wrappers.<StorageVolumeReadLeaseEntity>lambdaQuery()
                    .eq(StorageVolumeReadLeaseEntity::getStorageVolumeId, successorId)
                    .eq(StorageVolumeReadLeaseEntity::getStatus, "ACTIVE")
                    .gt(StorageVolumeReadLeaseEntity::getExpiresAt, LocalDateTime.now())
                    .eq(StorageVolumeReadLeaseEntity::getIsDeleted, 0)) > 0)
                blockers.add(new Blocker("SUCCESSOR_READ_LEASE", "接替卷仍有活动读取租约"));
        }
        return List.copyOf(blockers);
    }

    public record Blocker(String code, String message) { }
}
