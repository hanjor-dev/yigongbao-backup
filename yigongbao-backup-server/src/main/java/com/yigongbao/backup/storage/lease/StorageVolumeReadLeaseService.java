package com.yigongbao.backup.storage.lease;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.volume.*;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 把副本选择和卷下线互斥固化为数据库租约。 */
@Service
@RequiredArgsConstructor
public class StorageVolumeReadLeaseService {
    private static final ScheduledExecutorService STREAM_HEARTBEATS=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"storage-read-lease-heartbeat");t.setDaemon(true);return t;});
    private final StorageVolumeReadLeaseMapper leaseMapper;
    private final StorageVolumeMapper volumeMapper;
    private final ArtifactReplicaResolver resolver;
    private final JobOrderMapper jobOrderMapper;
    private final RuntimeConfigService configs;
    private final ArtifactCopyMapper copyMapper;
    private final AuditLogService auditLogs;

    public Duration defaultTtl() { return Duration.ofSeconds(configs.getLong("storage.read-lease.ttl-seconds", 600, 30)); }
    public Duration heartbeatInterval() {
        long seconds=configs.getLong("storage.read-lease.heartbeat-seconds",30,5);
        long ttl=defaultTtl().toSeconds();
        if(seconds*2>=ttl) throw new IllegalStateException("读取租约 TTL 必须大于两倍心跳间隔");
        return Duration.ofSeconds(seconds);
    }

    /** 返回持有租约的输入流；长下载按配置周期续租，关闭流时可靠释放租约。 */
    public InputStream wrap(InputStream input, LeasedArtifact lease) {
        if(input==null||lease==null) throw new IllegalArgumentException("租约输入流不能为空");
        long intervalMillis=heartbeatInterval().toMillis();
        AtomicReference<RuntimeException> heartbeatFailure=new AtomicReference<>();
        ScheduledFuture<?> heartbeat=STREAM_HEARTBEATS.scheduleAtFixedRate(()->{try{lease.heartbeat();}catch(RuntimeException failure){heartbeatFailure.compareAndSet(null,failure);}},intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);
        return new FilterInputStream(input) {
            private boolean closed;
            private void requireLease()throws IOException{RuntimeException failure=heartbeatFailure.get();if(failure!=null)throw new IOException("读取租约续租失败",failure);}
            @Override public int read() throws IOException {requireLease();return super.read();}
            @Override public int read(byte[] b,int off,int len)throws IOException{requireLease();return super.read(b,off,len);}
            @Override public void close() throws IOException {if(closed)return;closed=true;heartbeat.cancel(false);try{super.close();}finally{lease.close();}}
        };
    }

    @Transactional
    public void markDamaged(LeasedArtifact lease, String errorCode, String message, Long actorId) {
        if(lease==null||lease.replica().copyId()==null)return;
        var copy=copyMapper.selectById(lease.replica().copyId());
        if(copy==null)return;
        copy.setState("DAMAGED"); copy.setErrorCode(errorCode); copy.setLastVerifiedAt(LocalDateTime.now());
        copyMapper.updateById(copy);
        auditLogs.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN,actorId,"USER",null,
                "ARTIFACT_CHECKSUM_FAILED","ARTIFACT_COPY",String.valueOf(copy.getId()),null,null,
                "FAILED",message,LocalDateTime.now()));
    }

    @Transactional
    public LeasedArtifact acquire(String artifactType, long artifactId, String purpose, String owner, Duration ttl) {
        if (owner == null || owner.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("读取租约参数无效");
        ArtifactReplicaResolver.ReadableArtifactReplica replica = resolver.resolveReadable(artifactType, artifactId);
        StorageVolumeEntity volume = volumeMapper.findByIdForUpdate(replica.storageVolumeId());
        if (volume == null || !"ONLINE".equals(volume.getMountStatus())
                || (volume.getAdminStatus() != null && !"ENABLED".equals(volume.getAdminStatus()))
                || (volume.getOperationStatus() != null && !"IDLE".equals(volume.getOperationStatus())))
            throw new StorageVolumeUnavailableException("存储卷正在离线或不可读");
        LocalDateTime now = LocalDateTime.now();
        StorageVolumeReadLeaseEntity lease = new StorageVolumeReadLeaseEntity();
        lease.setStorageVolumeId(volume.getId()); lease.setArtifactType(artifactType); lease.setArtifactId(artifactId);
        lease.setPurpose(purpose); lease.setOwner(owner);
        lease.setFencingToken(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        lease.setStatus("ACTIVE"); lease.setHeartbeatAt(now); lease.setExpiresAt(now.plus(ttl));
        leaseMapper.insert(lease);
        return new LeasedArtifact(this, lease.getId(), lease.getFencingToken(), ttl, replica);
    }

    /** 为任务创建时已经固化的副本加租约，禁止恢复重试时悄悄切换到另一份副本。 */
    @Transactional
    public LeasedArtifact acquirePinned(String artifactType, long artifactId, long volumeId, String storageKey,
                                        String sha256, String purpose, String owner, Duration ttl) {
        if (storageKey == null || storageKey.isBlank() || owner == null || owner.isBlank()
                || ttl == null || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("固化读取租约参数无效");
        StorageVolumeEntity volume = volumeMapper.findByIdForUpdate(volumeId);
        if (volume == null || !"ONLINE".equals(volume.getMountStatus())
                || (volume.getAdminStatus() != null && !"ENABLED".equals(volume.getAdminStatus()))
                || (volume.getOperationStatus() != null && !"IDLE".equals(volume.getOperationStatus())))
            throw new StorageVolumeUnavailableException("固化恢复源卷正在离线或不可读");
        LocalDateTime now = LocalDateTime.now();
        StorageVolumeReadLeaseEntity lease = new StorageVolumeReadLeaseEntity();
        lease.setStorageVolumeId(volumeId); lease.setArtifactType(artifactType); lease.setArtifactId(artifactId);
        lease.setPurpose(purpose); lease.setOwner(owner);
        lease.setFencingToken(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        lease.setStatus("ACTIVE"); lease.setHeartbeatAt(now); lease.setExpiresAt(now.plus(ttl));
        leaseMapper.insert(lease);
        var replica = new ArtifactReplicaResolver.ReadableArtifactReplica(null, artifactType, artifactId, volumeId,
                storageKey, sha256, volume.getTier());
        return new LeasedArtifact(this, lease.getId(), lease.getFencingToken(), ttl, replica);
    }

    @Transactional
    public StorageVolumeEntity beginOffline(long volumeId, long actorId) {
        StorageVolumeEntity volume = volumeMapper.findByIdForUpdate(volumeId);
        if (volume == null) throw new StorageVolumeOperationException("存储卷不存在");
        if (!StorageVolumeStatus.Tier.COLD.name().equals(volume.getTier())
                || !StorageVolumeStatus.Write.READ_ONLY.name().equals(volume.getWriteStatus())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus()))
            throw new StorageVolumeOperationException("只有已封存且在线的 COLD/READ_ONLY 卷可以准备离线");
        if (volume.getOperationStatus() != null && !"IDLE".equals(volume.getOperationStatus()))
            throw new StorageVolumeOperationException("存储卷正在执行其他操作");
        volume.setOperationStatus(StorageVolumeStatus.Operation.GOING_OFFLINE.name());
        volume.setUpdateBy(actorId);
        volumeMapper.updateById(volume);
        long active = leaseMapper.selectCount(Wrappers.<StorageVolumeReadLeaseEntity>lambdaQuery()
                .eq(StorageVolumeReadLeaseEntity::getStorageVolumeId, volumeId)
                .eq(StorageVolumeReadLeaseEntity::getStatus, "ACTIVE")
                .gt(StorageVolumeReadLeaseEntity::getExpiresAt, LocalDateTime.now())
                .eq(StorageVolumeReadLeaseEntity::getIsDeleted, 0));
        if (active > 0) throw new StorageVolumeOperationException("存储卷仍有活动读取租约");
        if (jobOrderMapper.countRunningSourceReferences(volumeId) > 0)
            throw new StorageVolumeOperationException("存储卷仍被排队或运行中的恢复任务引用");
        volume.setMountStatus(StorageVolumeStatus.Mount.OFFLINE.name());
        volume.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name());
        volumeMapper.updateById(volume);
        auditLogs.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN,actorId,"ADMIN",null,
                "STORAGE_VOLUME_PREPARE_OFFLINE","STORAGE_VOLUME",String.valueOf(volumeId),null,null,
                "SUCCESS",null,LocalDateTime.now()));
        return volume;
    }

    void heartbeat(long leaseId, long token, Duration ttl) {
        StorageVolumeReadLeaseEntity lease = leaseMapper.selectById(leaseId);
        if (lease == null || !"ACTIVE".equals(lease.getStatus()) || lease.getFencingToken() != token)
            throw new IllegalStateException("读取租约已失效");
        LocalDateTime now = LocalDateTime.now(); lease.setHeartbeatAt(now); lease.setExpiresAt(now.plus(ttl));
        if (leaseMapper.updateById(lease) != 1) throw new IllegalStateException("读取租约续租失败");
    }

    void release(long leaseId, long token) {
        StorageVolumeReadLeaseEntity lease = leaseMapper.selectById(leaseId);
        if (lease == null || !"ACTIVE".equals(lease.getStatus()) || lease.getFencingToken() != token) return;
        lease.setStatus("RELEASED"); lease.setReleasedAt(LocalDateTime.now()); leaseMapper.updateById(lease);
    }

    /** 清理无心跳的遗留租约；运行中恢复任务仍由任务源卷引用阻止离线。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    @Transactional
    public void expireStaleLeases() {
        LocalDateTime now=LocalDateTime.now();
        for(StorageVolumeReadLeaseEntity lease:leaseMapper.selectList(Wrappers.<StorageVolumeReadLeaseEntity>lambdaQuery()
                .eq(StorageVolumeReadLeaseEntity::getStatus,"ACTIVE").le(StorageVolumeReadLeaseEntity::getExpiresAt,now)
                .eq(StorageVolumeReadLeaseEntity::getIsDeleted,0))){leaseMapper.expireIfStale(lease.getId(),lease.getFencingToken(),lease.getVersion(),now);}
    }

    public static final class LeasedArtifact implements AutoCloseable {
        private final StorageVolumeReadLeaseService owner; private final long leaseId; private final long token;
        private final Duration ttl; private final ArtifactReplicaResolver.ReadableArtifactReplica replica;
        private boolean closed;
        private LeasedArtifact(StorageVolumeReadLeaseService owner, long leaseId, long token, Duration ttl,
                               ArtifactReplicaResolver.ReadableArtifactReplica replica) {
            this.owner=owner; this.leaseId=leaseId; this.token=token; this.ttl=ttl; this.replica=replica;
        }
        public ArtifactReplicaResolver.ReadableArtifactReplica replica() { return replica; }
        public long leaseId() { return leaseId; }
        public void heartbeat() { if (closed) throw new IllegalStateException("读取租约已关闭"); owner.heartbeat(leaseId, token, ttl); }
        @Override public void close() { if (!closed) { closed=true; owner.release(leaseId, token); } }
    }
}
