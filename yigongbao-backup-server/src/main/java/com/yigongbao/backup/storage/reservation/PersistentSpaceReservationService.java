package com.yigongbao.backup.storage.reservation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.api.ArtifactStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.time.LocalDateTime;
import java.nio.file.Path;

/** 基于存储池行锁的空间预留实现，避免多任务并发下载造成 NAS 容量超卖。 */
@Service
public class PersistentSpaceReservationService implements SpaceReservationService {
    private final StoragePoolMapper pools;
    private final SpaceReservationMapper reservations;
    private final StorageProbe probe;
    private final ArtifactStore artifactStore;

    public PersistentSpaceReservationService(StoragePoolMapper pools, SpaceReservationMapper reservations,
                                             StorageProbe probe, ArtifactStore artifactStore) {
        this.pools = pools;
        this.reservations = reservations;
        this.probe = probe;
        this.artifactStore = artifactStore;
    }

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
        refreshHotPool(domain);
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

    /** 从 Windows 本地卷或 NAS 挂载路径实时刷新容量，避免依赖人工初始化容量值。 */
    private void refreshHotPool(String domain) {
        if (!"hot".equals(domain)) return;
        // ArtifactStore 在启动时已完成目录创建和读写校验；容量探测必须使用同一个已绑定根目录，
        // 避免运行期间修改数据库配置后，下载路径与容量探测路径发生漂移。
        Path root = artifactStore.root().toAbsolutePath().normalize();
        StorageProbe.StorageSnapshot snapshot = probe.probe(root);
        if (!snapshot.capacityKnown()) throw new IllegalStateException("热备存储容量无法探测: " + root);
        StoragePoolEntity pool = pools.selectOne(Wrappers.<StoragePoolEntity>lambdaQuery()
                .eq(StoragePoolEntity::getCapacityIdentity, domain).eq(StoragePoolEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (pool == null) {
            pool = new StoragePoolEntity();
            pool.setCapacityIdentity(domain);
            pool.setStorageType("LOCAL");
            pool.setRootPath(root.toString());
            pool.setStatus("AVAILABLE");
            pool.setVersion(0);
            pool.setCapacityBytes(snapshot.totalBytes());
            pool.setAvailableBytes(snapshot.usableBytes());
            try {
                pools.insert(pool);
            } catch (DuplicateKeyException racedRegistration) {
                // 并发首个任务可能同时注册同一容量域；唯一键获胜者的数据会在后续查询中使用。
            }
        } else {
            pool.setRootPath(root.toString());
            pool.setCapacityBytes(snapshot.totalBytes());
            pool.setAvailableBytes(snapshot.usableBytes());
            pool.setStatus("AVAILABLE");
            pools.updateById(pool);
        }
    }

    @Override
    @Transactional
    public void release(SpaceReservation reservation) {
        if (reservation == null) return;
        long token; try { token = Long.parseLong(reservation.token()); } catch (RuntimeException e) { return; }
        reservations.update(null, Wrappers.<SpaceReservationEntity>lambdaUpdate().eq(SpaceReservationEntity::getStatus, "ACTIVE").eq(SpaceReservationEntity::getFencingToken, token).set(SpaceReservationEntity::getStatus, "RELEASED"));
    }
}
