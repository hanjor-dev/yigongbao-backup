package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.job.*;
import com.yigongbao.backup.storage.volume.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.yigongbao.backup.audit.AuditLogService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 整盘封存的事务边界和状态收尾。 */
@Service
@RequiredArgsConstructor
public class VolumeArchiveService {
    private final VolumeArchiveMapper archiveMapper;
    private final VolumeArchiveItemMapper itemMapper;
    private final StorageVolumeMapper volumeMapper;
    private final StorageVolumeLifecycleService lifecycle;
    private final VolumeArchiveBlockerService blockers;
    private final ManagedVolumeArtifactCatalog catalog;
    private final PersistentJobCoordinator jobs;
    private final JobMapper jobMapper;
    private final AuditLogService auditLogs;
    private final TransactionTemplate transactions;

    public JobEntity start(StartArchiveCommand command, long actorId) {
        if (command == null || command.sourceVolumeId() == null || command.archiveNote() == null || command.archiveNote().isBlank())
            throw new StorageVolumeOperationException("源卷和封存备注不能为空");
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            VolumeArchiveEntity existing = archiveMapper.selectOne(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                    .eq(VolumeArchiveEntity::getIdempotencyKey, command.idempotencyKey())
                    .eq(VolumeArchiveEntity::getIsDeleted, 0).last("LIMIT 1"));
            if (existing != null && existing.getJobId() != null) return jobMapper.selectById(existing.getJobId());
        }
        Prepared prepared=transactions.execute(status -> prepare(command,actorId));
        if(prepared==null) throw new IllegalStateException("封存准备事务未返回结果");
        if(prepared.existingJob()!=null)return prepared.existingJob();
        try {
            ManagedVolumeArtifactCatalog.FrozenManifest manifest=catalog.inspect(prepared.archive().getId(),prepared.sourceVolumeId());
            return transactions.execute(status -> finishPreparation(prepared.archive().getId(),manifest,actorId));
        } catch(RuntimeException failure) {
            cleanupPreparation(prepared.archive().getId(),actorId,failure);
            throw failure;
        }
    }

    private Prepared prepare(StartArchiveCommand command,long actorId){
        StorageVolumeEntity observed=volumeMapper.selectById(command.sourceVolumeId());
        Long successorId=command.successorVolumeId();
        if(observed!=null&&!"ACTIVE".equals(observed.getWriteStatus())&&successorId==null){StorageVolumeEntity active=volumeMapper.findActiveForUpdate();successorId=active==null?null:active.getId();}
        StorageVolumeEntity source=volumeMapper.findByIdForUpdate(command.sourceVolumeId());
        if(source==null)throw new StorageVolumeOperationException("源卷不存在");
        if(command.idempotencyKey()!=null&&!command.idempotencyKey().isBlank()){VolumeArchiveEntity existing=archiveMapper.selectOne(Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getIdempotencyKey,command.idempotencyKey()).eq(VolumeArchiveEntity::getIsDeleted,0).last("LIMIT 1"));if(existing!=null){if(existing.getJobId()!=null)return new Prepared(existing,source.getId(),jobMapper.selectById(existing.getJobId()));return resumePrepared(command,existing,source);}}
        List<VolumeArchiveBlockerService.Blocker> found=blockers.inspect(source.getId(),successorId);if(!found.isEmpty())throw new StorageVolumeOperationException(found.getFirst().message());
        VolumeArchiveEntity archive=new VolumeArchiveEntity();archive.setSourceVolumeId(source.getId());archive.setSuccessorVolumeId(successorId);archive.setAttemptNo((int)(archiveMapper.selectCount(Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getSourceVolumeId,source.getId()).eq(VolumeArchiveEntity::getIsDeleted,0))+1));archive.setIdempotencyKey(command.idempotencyKey()==null||command.idempotencyKey().isBlank()?UUID.randomUUID().toString():command.idempotencyKey());archive.setPhase("REQUESTED");archive.setArchiveNote(command.archiveNote().trim());archive.setOfflineLabelNote(command.offlineLabelNote());archive.setPhysicalLocation(command.physicalLocation());archive.setDisplayNameSnapshot(source.getDisplayName());archive.setRequestedBy(actorId);archiveMapper.insert(archive);
        if("ACTIVE".equals(source.getWriteStatus())){lifecycle.switchActive(source.getId(),successorId,actorId);archive.setPhase("SWITCHED");}else{source.setOperationStatus(StorageVolumeStatus.Operation.SEALING.name());source.setUpdateBy(actorId);volumeMapper.updateById(source);StorageVolumeEntity guarantee=volumeMapper.findByIdForUpdate(successorId);if(guarantee==null||!StorageVolumeStatus.Write.ACTIVE.name().equals(guarantee.getWriteStatus())||!StorageVolumeStatus.Operation.IDLE.name().equals(guarantee.getOperationStatus()))throw new StorageVolumeOperationException("运行保障卷已不是空闲的当前 ACTIVE 卷");guarantee.setOperationStatus(StorageVolumeStatus.Operation.ARCHIVE_GUARD.name());guarantee.setUpdateBy(actorId);volumeMapper.updateById(guarantee);}
        source=volumeMapper.findByIdForUpdate(source.getId());source.setOperationRefId(archive.getId());volumeMapper.updateById(source);StorageVolumeEntity guarantee=volumeMapper.findByIdForUpdate(successorId);if(guarantee!=null&&"ARCHIVE_GUARD".equals(guarantee.getOperationStatus())){guarantee.setOperationRefId(archive.getId());volumeMapper.updateById(guarantee);}archiveMapper.updateById(archive);return new Prepared(archive,source.getId(),null);
    }

    private JobEntity finishPreparation(long archiveId,ManagedVolumeArtifactCatalog.FrozenManifest manifest,long actorId){VolumeArchiveEntity archive=required(archiveId);StorageVolumeEntity source=volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());StorageVolumeEntity guarantee=volumeMapper.findByIdForUpdate(archive.getSuccessorVolumeId());if(source==null||!archiveIdEquals(source.getOperationRefId(),archiveId)||!"SEALING".equals(source.getOperationStatus())||guarantee==null||!archiveIdEquals(guarantee.getOperationRefId(),archiveId)||!"ARCHIVE_GUARD".equals(guarantee.getOperationStatus()))throw new IllegalStateException("封存准备期间卷保护状态已变化");catalog.persist(manifest);archive.setPhase("MANIFEST_FROZEN");archive.setOrderCount(manifest.orderCount());archive.setOrderPackageCount(manifest.items().stream().filter(v->"ORDER_PACKAGE".equals(v.getArtifactType())).count());archive.setManagedFileCount((long)manifest.items().size());archive.setTotalBytes(manifest.totalBytes());archive.setOrderCreatedFrom(manifest.orderCreatedFrom());archive.setOrderCreatedTo(manifest.orderCreatedTo());archive.setPeriodLabel(VolumeArchivePreviewService.periodLabel(manifest.orderCreatedFrom(),manifest.orderCreatedTo()));archive.setContentManifestSha256(manifest.sha256());archive.setManifestVersion(1L);JobEntity job=jobs.create("VOLUME_ARCHIVE","volume-archive:"+archive.getId(),actorId,"{\"archiveId\":"+archive.getId()+"}");jobs.setProgressTotal(job.getId(),manifest.items().size());job.setProgressTotal((long)manifest.items().size());archive.setJobId(job.getId());archiveMapper.updateById(archive);audit(actorId,"STORAGE_VOLUME_ARCHIVE_STARTED",archive,"SUCCESS",null);return job;}
    private void cleanupPreparation(long archiveId,long actorId,RuntimeException failure){transactions.executeWithoutResult(status->{VolumeArchiveEntity archive=required(archiveId);StorageVolumeEntity source=volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());if(source!=null&&archiveIdEquals(source.getOperationRefId(),archiveId)){source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());source.setUpdateBy(actorId);volumeMapper.updateById(source);}releaseSuccessorGuard(archive);archive.setPhase("PREPARE_FAILED");archive.setFailureCode("ARCHIVE_PREPARE_FAILED");archive.setFailureMessage(failure.getMessage());archive.setVerificationCompletedAt(LocalDateTime.now());archiveMapper.updateById(archive);audit(actorId,"STORAGE_VOLUME_ARCHIVE_PREPARE_FAILED",archive,"FAILED",failure.getMessage());});}
    private static boolean archiveIdEquals(Long value,long archiveId){return value!=null&&value==archiveId;}
    private Prepared resumePrepared(StartArchiveCommand command, VolumeArchiveEntity archive, StorageVolumeEntity source) {
        boolean sameRequest=java.util.Objects.equals(archive.getSourceVolumeId(),command.sourceVolumeId())
                && (command.successorVolumeId()==null||java.util.Objects.equals(archive.getSuccessorVolumeId(),command.successorVolumeId()));
        boolean resumable=List.of("REQUESTED","SWITCHED").contains(archive.getPhase());
        StorageVolumeEntity successor=archive.getSuccessorVolumeId()==null?null:volumeMapper.findByIdForUpdate(archive.getSuccessorVolumeId());
        boolean protectedState=archive.getId()!=null
                && archiveIdEquals(source.getOperationRefId(),archive.getId())
                && "SEALING".equals(source.getOperationStatus())
                && successor!=null
                && archiveIdEquals(successor.getOperationRefId(),archive.getId())
                && "ARCHIVE_GUARD".equals(successor.getOperationStatus());
        if(!sameRequest||!resumable||!protectedState)
            throw new StorageVolumeOperationException("相同幂等请求的封存准备状态无法安全恢复，请先修复卷保护状态");
        return new Prepared(archive,source.getId(),null);
    }
    private record Prepared(VolumeArchiveEntity archive,long sourceVolumeId,JobEntity existingJob){}

    /** CREATED 状态尚无执行器，必须在同一事务中先释放卷状态再把任务置为取消终态。 */
    @Transactional
    public boolean cancelQueuedJob(long jobId, long actorId) {
        VolumeArchiveEntity archive = archiveMapper.selectOne(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                .eq(VolumeArchiveEntity::getJobId, jobId).eq(VolumeArchiveEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (archive == null) return false;
        JobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在");
        if (!"CREATED".equals(job.getStatus()) && !"PAUSED".equals(job.getStatus())) return false;
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        if (source != null && archive.getId().equals(source.getOperationRefId())) {
            source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
            source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());
            source.setUpdateBy(actorId); volumeMapper.updateById(source);
        }
        releaseSuccessorGuard(archive);
        archive.setPhase("CANCELLED"); archive.setInterruptedReason("管理员在任务领取前取消");
        archive.setVerificationCompletedAt(LocalDateTime.now()); archive.setUpdateBy(actorId); archiveMapper.updateById(archive);
        int rows=jobMapper.update(null,Wrappers.<JobEntity>lambdaUpdate().eq(JobEntity::getId,jobId)
                .eq(JobEntity::getStatus,job.getStatus()).set(JobEntity::getCancelRequested,true)
                .set(JobEntity::getStatus,"CANCELLED").set(JobEntity::getFinishedAt,LocalDateTime.now())
                .set(JobEntity::getLeaseOwner,null).set(JobEntity::getLeaseUntil,null));
        if(rows!=1) throw new IllegalStateException("任务状态已变化，取消失败");
        audit(actorId,"STORAGE_VOLUME_ARCHIVE_CANCELLED",archive,"SUCCESS",archive.getInterruptedReason());
        return true;
    }

    @Transactional
    public VolumeArchiveEntity abandon(long archiveId, long actorId, String reason) {
        VolumeArchiveEntity archive=required(archiveId);
        if (!List.of("VERIFY_FAILED","COMMIT_FAILED","CANCELLED").contains(archive.getPhase()))
            throw new StorageVolumeOperationException("只有失败或取消的封存尝试可以放弃");
        StorageVolumeEntity source=volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        if(source!=null){source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());source.setUpdateBy(actorId);volumeMapper.updateById(source);}
        releaseSuccessorGuard(archive);
        archive.setPhase("ABANDONED"); archive.setInterruptedReason(reason==null||reason.isBlank()?"管理员放弃本次封存尝试":reason.trim());
        archive.setUpdateBy(actorId); archiveMapper.updateById(archive);
        audit(actorId,"STORAGE_VOLUME_ARCHIVE_ABANDONED",archive,"SUCCESS",archive.getInterruptedReason()); return archive;
    }

    @Transactional
    public void complete(long archiveId, String owner, long fencingToken) {
        VolumeArchiveEntity archive = required(archiveId);
        assertFencing(archive, owner, fencingToken);
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        StorageVolumeEntity successor = volumeMapper.findByIdForUpdate(archive.getSuccessorVolumeId());
        if (source == null || !StorageVolumeStatus.Tier.HOT.name().equals(source.getTier())
                || !StorageVolumeStatus.Write.READ_ONLY.name().equals(source.getWriteStatus())
                || !StorageVolumeStatus.Operation.SEALING.name().equals(source.getOperationStatus())
                || !archiveIdEquals(source.getOperationRefId(), archiveId)
                || successor == null || !StorageVolumeStatus.Tier.HOT.name().equals(successor.getTier())
                || !StorageVolumeStatus.Write.ACTIVE.name().equals(successor.getWriteStatus())
                || !StorageVolumeStatus.Operation.ARCHIVE_GUARD.name().equals(successor.getOperationStatus())
                || !archiveIdEquals(successor.getOperationRefId(), archiveId))
            throw new IllegalStateException("封存提交前卷状态已变化，拒绝提交 COLD｜"
                    + volumeState("源卷", source) + "｜" + volumeState("保障卷", successor));
        source.setTier(StorageVolumeStatus.Tier.COLD.name()); source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        source.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name()); source.setOperationRefId(null);
        source.setArchiveNote(archive.getArchiveNote()); source.setOfflineLabelNote(archive.getOfflineLabelNote());
        source.setPhysicalLocation(archive.getPhysicalLocation()); source.setArchivedAt(LocalDateTime.now());
        source.setOrderCreatedFrom(archive.getOrderCreatedFrom()); source.setOrderCreatedTo(archive.getOrderCreatedTo());
        source.setArchivedOrderCount(archive.getOrderCount()); source.setArchivedFileCount(archive.getManagedFileCount());
        source.setArchivedBytes(archive.getTotalBytes()); source.setLastVerifiedAt(LocalDateTime.now());
        volumeMapper.updateById(source);
        releaseSuccessorGuard(archive);
        archive.setPhase("SUCCEEDED"); archive.setVerificationCompletedAt(LocalDateTime.now()); archive.setArchivedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
        audit(archive.getRequestedBy(),"STORAGE_VOLUME_ARCHIVE_SUCCEEDED",archive,"SUCCESS",null);
    }

    private static String volumeState(String role, StorageVolumeEntity volume) {
        if (volume == null) return role + "不存在";
        return role + "ID=" + volume.getId()
                + ",tier=" + volume.getTier()
                + ",mountStatus=" + volume.getMountStatus()
                + ",writeStatus=" + volume.getWriteStatus()
                + ",operationStatus=" + volume.getOperationStatus()
                + ",operationRefId=" + volume.getOperationRefId();
    }

    @Transactional
    public void fail(long archiveId, ArchiveFailure failure, String owner, long fencingToken) {
        VolumeArchiveEntity archive = required(archiveId);
        assertFencing(archive, owner, fencingToken);
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name()); volumeMapper.updateById(source);
        releaseSuccessorGuard(archive);
        archive.setPhase("VERIFY_FAILED"); archive.setFailureCode(failure.code()); archive.setFailureMessage(failure.message());
        archive.setVerificationCompletedAt(LocalDateTime.now()); archiveMapper.updateById(archive);
        audit(archive.getRequestedBy(),"STORAGE_VOLUME_ARCHIVE_VERIFY_FAILED",archive,"FAILED",failure.message());
    }

    @Transactional
    public void commitFailed(long archiveId, ArchiveFailure failure, String owner, long fencingToken) {
        VolumeArchiveEntity archive = required(archiveId);
        assertFencing(archive, owner, fencingToken);
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        if (source != null) {
            source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
            source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());
            volumeMapper.updateById(source);
        }
        releaseSuccessorGuard(archive);
        archive.setPhase("COMMIT_FAILED"); archive.setFailureCode(failure.code());
        archive.setFailureMessage(failure.message()); archive.setVerificationCompletedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
        audit(archive.getRequestedBy(),"STORAGE_VOLUME_ARCHIVE_COMMIT_FAILED",archive,"FAILED",failure.message());
    }

    @Transactional
    public void cancel(long archiveId, String reason, String owner, long fencingToken) {
        VolumeArchiveEntity archive = required(archiveId);
        assertFencing(archive, owner, fencingToken);
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(archive.getSourceVolumeId());
        source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name()); volumeMapper.updateById(source);
        releaseSuccessorGuard(archive);
        archive.setPhase("CANCELLED"); archive.setInterruptedReason(reason); archive.setVerificationCompletedAt(LocalDateTime.now());
        archiveMapper.updateById(archive);
        audit(archive.getRequestedBy(),"STORAGE_VOLUME_ARCHIVE_CANCELLED",archive,"SUCCESS",reason);
    }

    private void releaseSuccessorGuard(VolumeArchiveEntity archive) {
        if (archive.getSuccessorVolumeId() == null) return;
        StorageVolumeEntity successor = volumeMapper.findByIdForUpdate(archive.getSuccessorVolumeId());
        if (successor != null && "ARCHIVE_GUARD".equals(successor.getOperationStatus())
                && archive.getId() != null && archiveIdEquals(successor.getOperationRefId(), archive.getId())) {
            successor.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name()); successor.setOperationRefId(null);
            volumeMapper.updateById(successor);
        }
    }
    /** 锁住任务行直到卷和 attempt 状态提交，租约回收/重新领取不能穿过本事务。 */
    private void assertFencing(VolumeArchiveEntity archive, String owner, long token) {
        JobEntity job = archive.getJobId() == null ? null : jobMapper.findByIdForUpdate(archive.getJobId());
        boolean valid = job != null && "RUNNING".equals(job.getStatus())
                && java.util.Objects.equals(owner, job.getLeaseOwner())
                && job.getLeaseUntil()!=null && job.getLeaseUntil().isAfter(LocalDateTime.now());
        if (!valid || job.getFencingToken() == null || job.getFencingToken() != token)
            throw new IllegalStateException("封存执行器租约或 fencing token 已失效");
    }
    public VolumeArchiveEntity required(long id) { VolumeArchiveEntity v=archiveMapper.selectById(id);if(v==null)throw new IllegalArgumentException("封存记录不存在");return v; }
    public void validateFrozenManifest(long archiveId, List<VolumeArchiveItemEntity> items) {
        VolumeArchiveEntity archive = required(archiveId);
        catalog.validateFrozen(archiveId, archive.getSourceVolumeId(), archive.getContentManifestSha256(), items);
    }
    public record StartArchiveCommand(Long sourceVolumeId, Long successorVolumeId, String archiveNote,
                                      String offlineLabelNote, String physicalLocation, String idempotencyKey) { }
    public record ArchiveFailure(String code,String message) { }
    private void audit(Long actorId,String action,VolumeArchiveEntity archive,String result,String failure){auditLogs.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN,actorId,"ADMIN",null,action,"VOLUME_ARCHIVE",String.valueOf(archive.getId()),archive.getJobId(),null,result,failure,LocalDateTime.now()));}
}
