package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 基于存储池行锁的空间预留实现，避免多任务并发下载造成 NAS 容量超卖。 */
@Service
public class PersistentSpaceReservationService implements SpaceReservationService {
    private final StoragePoolMapper pools;
    private final SpaceReservationMapper reservations;
    public PersistentSpaceReservationService(StoragePoolMapper pools, SpaceReservationMapper reservations) { this.pools = pools; this.reservations = reservations; }

    @Override
    @Transactional
    public SpaceReservation reserve(String domain, long bytes) {
        throw new IllegalArgumentException("持久化空间预留必须携带真实任务ID");
    }

    @Override
    @Transactional
    public SpaceReservation reserve(String domain, long bytes, long jobId) {
        if (domain == null || domain.isBlank() || bytes < 0) throw new IllegalArgumentException("空间预留参数非法");
        if (jobId <= 0) throw new IllegalArgumentException("空间预留必须绑定真实任务ID");
        StoragePoolEntity pool = pools.selectOne(Wrappers.<StoragePoolEntity>lambdaQuery().eq(StoragePoolEntity::getCapacityIdentity, domain).eq(StoragePoolEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (pool == null || pool.getId() == null) throw new IllegalStateException("容量域不存在");
        pool = pools.selectForUpdate(pool.getId());
        if (pool.getAvailableBytes() == null || pool.getAvailableBytes() < bytes) throw new IllegalStateException("可用空间不足或容量未知");
        Long active = reservations.sumActive(pool.getId());
        if (active == null || active > pool.getAvailableBytes() - bytes) throw new IllegalStateException("空间已被其他任务预留");
        long token = System.nanoTime();
        SpaceReservationEntity row = new SpaceReservationEntity(); row.setStoragePoolId(pool.getId()); row.setJobId(jobId); row.setReservedBytes(bytes); row.setLeaseUntil(LocalDateTime.now().plusHours(2)); row.setFencingToken(token); row.setStatus("ACTIVE"); reservations.insert(row);
        return new SpaceReservation(domain, bytes, Long.toString(token));
    }

    @Override
    @Transactional
    public void release(SpaceReservation reservation) {
        if (reservation == null) return;
        long token; try { token = Long.parseLong(reservation.token()); } catch (RuntimeException e) { return; }
        reservations.update(null, Wrappers.<SpaceReservationEntity>lambdaUpdate().eq(SpaceReservationEntity::getStatus, "ACTIVE").eq(SpaceReservationEntity::getFencingToken, token).set(SpaceReservationEntity::getStatus, "RELEASED"));
    }
}
