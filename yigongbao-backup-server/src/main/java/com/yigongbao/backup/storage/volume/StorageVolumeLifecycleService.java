package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.config.RuntimeConfigService;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationEntity;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 存储卷的纳管、重新挂载确认和分层状态转换服务。
 *
 * <p>本服务不接受任意业务根目录：所有根目录均由发现结果派生为
 * {@code <挂载点>/yigongbao-backup}。</p>
 */
@Service
@RequiredArgsConstructor
public class StorageVolumeLifecycleService {
    private final StorageVolumeMapper volumeMapper;
    private final StorageVolumeDiscoveryService discoveryService;
    private final StorageVolumeMarkerService markerService;
    private final StorageVolumeHealthService healthService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final JobMapper jobMapper;
    private final JobOrderMapper jobOrderMapper;
    private final ArtifactCopyMapper artifactCopyMapper;
    private final SpaceReservationMapper reservationMapper;
    private final RuntimeConfigService configs;

    /** 返回只读候选卷，调用方必须显式执行 register 才会创建目录或台账。 */
    public List<DiscoveredStorageVolume> scan() {
        return discoveryService.scan();
    }

    /** 将扫描到的候选卷纳入管理，并在其受控子目录内创建卷标记。 */
    @Transactional
    public StorageVolumeEntity register(RegisterCommand command, long operatorId) {
        if (command == null || command.mountPath() == null || command.displayName() == null || command.displayName().isBlank()) {
            throw new StorageVolumeOperationException("存储卷纳管参数不完整");
        }
        DiscoveredStorageVolume candidate = discoveryService.discoverMountedPaths(List.of(command.mountPath())).stream()
                .findFirst().orElseThrow(() -> new StorageVolumeOperationException("挂载点不可见或不可访问"));
        if (volumeMapper.selectCount(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getRootPath, candidate.rootPath().toString()).eq(StorageVolumeEntity::getIsDeleted, 0)) > 0) {
            throw new StorageVolumeOperationException("该受控存储目录已纳管");
        }

        // 与任务选卷、人工切换共用同一 ACTIVE 行锁。没有 ACTIVE 时，数据库的唯一索引和
        // 该范围锁共同保证并发纳管最多只有一个首卷能够成为 ACTIVE。
        StorageVolumeEntity currentActive = volumeMapper.findActiveForUpdate();

        StorageVolumeMarkerService.MarkerIdentity existingMarker = markerService.inspect(candidate.rootPath());
        if (existingMarker.exists() && !existingMarker.valid()) {
            throw new StorageVolumeOperationException("受控目录卷标记损坏或非法：" + existingMarker.reason());
        }
        if (existingMarker.valid()) {
            StorageVolumeEntity duplicate = volumeMapper.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                            .eq(StorageVolumeEntity::getIsDeleted, 0)).stream()
                    .filter(volume -> existingMarker.volumeId().equals(persistedMarkerId(volume))).findFirst().orElse(null);
            if (duplicate != null) {
                throw new StorageVolumeOperationException("该物理存储卷已纳管：" + duplicate.getDisplayName());
            }
        }

        StorageVolumeEntity entity = new StorageVolumeEntity();
        entity.setVolumeCode(command.volumeCode() == null || command.volumeCode().isBlank()
                ? "volume-" + UUID.randomUUID().toString().substring(0, 8) : command.volumeCode().trim());
        entity.setDisplayName(command.displayName().trim());
        entity.setAccessType(candidate.accessType().name());
        entity.setMountPath(candidate.mountPath().toString());
        entity.setRootPath(candidate.rootPath().toString());
        entity.setVolumeFingerprint(fingerprint(candidate));
        entity.setTier(command.tier() == null ? StorageVolumeStatus.Tier.HOT.name() : command.tier().name());
        entity.setMountStatus(StorageVolumeStatus.Mount.OFFLINE.name());
        entity.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        entity.setAdminStatus(StorageVolumeStatus.Admin.ENABLED.name());
        entity.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name());
        entity.setPriority(command.priority() == null ? 0 : command.priority());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        volumeMapper.insert(entity);

        UUID markerId = existingMarker.valid() ? existingMarker.volumeId() : UUID.randomUUID();
        try {
            markerService.initialize(candidate.rootPath(), markerId);
            StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
            entity.setMarkerJson(objectMapper.writeValueAsString(new MarkerInfo(markerId.toString(), 1)));
            entity.setMountStatus(health.accessible() ? StorageVolumeStatus.Mount.ONLINE.name() : StorageVolumeStatus.Mount.UNAVAILABLE.name());
            entity.setLastProbedAt(LocalDateTime.now());
            entity.setLastErrorCode(health.errorCode());
            entity.setLastErrorMessage(health.errorMessage());
            updateCapacitySnapshot(entity, health);
            if (StorageVolumeStatus.Tier.HOT.name().equals(entity.getTier())
                    && health.accessible() && health.writable() && health.totalBytes() > 0) {
                entity.setWriteStatus(currentActive == null
                        ? StorageVolumeStatus.Write.ACTIVE.name()
                        : StorageVolumeStatus.Write.STANDBY.name());
            }
            volumeMapper.updateById(entity);
        } catch (Exception exception) {
            throw new StorageVolumeOperationException("无法初始化受控存储卷：" + exception.getMessage());
        }
        audit(operatorId, "REGISTER", entity, "SUCCESS", null);
        return entity;
    }

    /** 验证重新接入的卷标记并刷新在线状态；标记不匹配时绝不接管该目录。 */
    @Transactional
    public StorageVolumeEntity confirmMounted(long volumeId, long operatorId) {
        StorageVolumeEntity entity = required(volumeId);
        StorageVolumeMarkerService.VerificationResult verification = markerService.verify(Path.of(entity.getRootPath()), markerId(entity));
        if (!verification.valid()) {
            throw new StorageVolumeOperationException("卷挂载确认失败：" + verification.reason());
        }
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
        entity.setMountStatus(health.accessible() ? StorageVolumeStatus.Mount.ONLINE.name() : StorageVolumeStatus.Mount.UNAVAILABLE.name());
        entity.setLastProbedAt(LocalDateTime.now());
        entity.setLastErrorCode(health.errorCode());
        entity.setLastErrorMessage(health.errorMessage());
        updateCapacitySnapshot(entity, health);
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, "CONFIRM_MOUNTED", entity, health.accessible() ? "SUCCESS" : "FAILED", health.errorMessage());
        return entity;
    }

    /** 在盘符或挂载点变化后，按持久化 marker 身份确认原盘并原子重绑定路径。 */
    @Transactional
    public StorageVolumeEntity confirmMounted(long volumeId, Path candidateMountPath, long operatorId) {
        if (candidateMountPath == null) throw new StorageVolumeOperationException("候选挂载点不能为空");
        StorageVolumeEntity entity = volumeMapper.findByIdForUpdate(volumeId);
        if (entity == null) entity = required(volumeId);
        if (entity.getOperationStatus() != null && !StorageVolumeStatus.Operation.IDLE.name().equals(entity.getOperationStatus())
                && !StorageVolumeStatus.Operation.MOUNTING.name().equals(entity.getOperationStatus()))
            throw new StorageVolumeOperationException("存储卷正在执行其他生命周期操作");
        DiscoveredStorageVolume candidate = discoveryService.discoverMountedPaths(List.of(candidateMountPath)).stream()
                .findFirst().orElseThrow(() -> new StorageVolumeOperationException("候选挂载点不可见或不可访问"));
        StorageVolumeEntity pathOwner = volumeMapper.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                        .eq(StorageVolumeEntity::getIsDeleted, 0).ne(StorageVolumeEntity::getId, volumeId)).stream()
                .filter(other -> candidate.rootPath().toString().equals(other.getRootPath())
                        || candidate.mountPath().toString().equals(other.getMountPath())).findFirst().orElse(null);
        if (pathOwner != null) throw new StorageVolumeOperationException("候选挂载点已绑定其他存储卷：" + pathOwner.getDisplayName());
        StorageVolumeMarkerService.VerificationResult verification = markerService.verify(candidate.rootPath(), markerId(entity));
        if (!verification.valid()) throw new StorageVolumeOperationException("卷挂载确认失败：" + verification.reason());
        assertUniqueMountedMarker(markerId(entity), candidate.rootPath());
        String candidateFingerprint=fingerprint(candidate);
        if(entity.getVolumeFingerprint()!=null&&!entity.getVolumeFingerprint().isBlank()
                && !entity.getVolumeFingerprint().equals(candidateFingerprint))
            throw new StorageVolumeOperationException("候选介质物理指纹与登记记录不一致，拒绝重新绑定");
        entity.setOperationStatus(StorageVolumeStatus.Operation.MOUNTING.name());
        entity.setMountPath(candidate.mountPath().toString());
        entity.setRootPath(candidate.rootPath().toString());
        if(entity.getVolumeFingerprint()==null||entity.getVolumeFingerprint().isBlank()) entity.setVolumeFingerprint(candidateFingerprint);
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
        entity.setMountStatus(health.accessible() ? StorageVolumeStatus.Mount.ONLINE.name()
                : StorageVolumeStatus.Mount.UNAVAILABLE.name());
        entity.setOperationStatus(health.accessible() ? StorageVolumeStatus.Operation.IDLE.name()
                : StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());
        // 故障切换后重新出现的旧 ACTIVE 绝不能抢回写权限。
        if (StorageVolumeStatus.Write.ACTIVE.name().equals(entity.getWriteStatus())
                && volumeMapper.findActiveForUpdate() != null
                && !java.util.Objects.equals(volumeMapper.findActiveForUpdate().getId(), entity.getId())) {
            entity.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        }
        entity.setLastProbedAt(LocalDateTime.now());
        entity.setLastErrorCode(health.errorCode());
        entity.setLastErrorMessage(health.errorMessage());
        updateCapacitySnapshot(entity, health);
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, "CONFIRM_MOUNTED", entity, health.accessible() ? "SUCCESS" : "FAILED", health.errorMessage());
        return entity;

    }

    private void assertUniqueMountedMarker(UUID expected, Path selectedRoot) {
        List<DiscoveredStorageVolume> mounted=discoveryService.scan();
        if(mounted==null)return;
        long matches=mounted.stream().filter(candidate -> {
            StorageVolumeMarkerService.MarkerIdentity identity=markerService.inspect(candidate.rootPath());
            return identity.valid()&&expected.equals(identity.volumeId());
        }).map(candidate->candidate.rootPath().toAbsolutePath().normalize()).distinct().count();
        if(matches>1) throw new StorageVolumeOperationException("检测到同一卷标记同时出现在多个挂载点，疑似克隆介质，拒绝挂载");
    }

    private static String fingerprint(DiscoveredStorageVolume candidate) {
        if((candidate.volumeSerial()==null||candidate.volumeSerial().isBlank())
                && candidate.accessType()==StorageAccessType.MAPPED_NETWORK)
            return "NETWORK|"+candidate.fileStoreName()+"|"+candidate.fileStoreType();
        if(candidate.volumeSerial()==null||candidate.volumeSerial().isBlank())
            throw new StorageVolumeOperationException("无法读取物理卷序列号，拒绝建立不可靠的介质身份");
        return "VSN|"+candidate.volumeSerial()+"|"+candidate.fileStoreType()+"|"+candidate.totalBytes();
    }

    /** 修改卷分层与写状态；ACTIVE 的全局互斥由后续切换服务及数据库唯一键共同保证。 */
    @Transactional
    public StorageVolumeEntity changeTier(long volumeId, StorageVolumeStatus.Tier tier,
                                          StorageVolumeStatus.Write writeStatus, long operatorId) {
        // 所有卷状态写入与 setActive 使用相同锁序，避免并发状态转换覆盖刚提交的 ACTIVE。
        StorageVolumeEntity currentActive = volumeMapper.findActiveForUpdate();
        if (currentActive != null && !java.util.Objects.equals(currentActive.getId(), volumeId)
                && StorageVolumeStatus.Operation.ARCHIVE_GUARD.name().equals(currentActive.getOperationStatus()))
            throw new StorageVolumeOperationException("当前 ACTIVE 卷正在保障封存任务，禁止切换");
        StorageVolumeEntity entity = currentActive != null && java.util.Objects.equals(currentActive.getId(), volumeId)
                ? currentActive : volumeMapper.findByIdForUpdate(volumeId);
        if (entity == null) entity = required(volumeId);
        if (tier == null || writeStatus == null) {
            throw new StorageVolumeOperationException("存储卷目标状态不能为空");
        }
        if (writeStatus == StorageVolumeStatus.Write.ACTIVE) {
            // ACTIVE 切换还需要检查正在写入的任务并降级旧卷，不能由通用状态接口绕过。
            throw new StorageVolumeOperationException("ACTIVE 状态必须通过 setActive 受控切换");
        }
        if (tier == StorageVolumeStatus.Tier.COLD) {
            throw new StorageVolumeOperationException("转为 COLD 必须通过整盘封存流程完成");
        }
        if (StorageVolumeStatus.Write.ACTIVE.name().equals(entity.getWriteStatus())) {
            throw new StorageVolumeOperationException("当前 ACTIVE 卷必须先受控切换到其他 HOT 卷");
        }
        boolean tierChanged = !tier.name().equals(entity.getTier());
        boolean disabling = writeStatus == StorageVolumeStatus.Write.DISABLED;
        if (tierChanged || disabling) ensureNoOperationalReferences(entity, true);
        if (tierChanged || writeStatus == StorageVolumeStatus.Write.STANDBY) {
            validateTransitionTarget(entity, tier, writeStatus);
        }
        entity.setTier(tier.name());
        entity.setWriteStatus(writeStatus.name());
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, "CHANGE_STATE", entity, "SUCCESS", null);
        return entity;
    }

    /**
     * 显式切换唯一活动热备卷。
     * 任何仍向原 ACTIVE 卷写入的任务都会阻止切换，已创建任务因已固化目标卷而不受影响。
     */
    @Transactional(noRollbackFor = StorageVolumeUnavailableException.class)
    public StorageVolumeEntity setActive(long volumeId, long operatorId) {
        // 与新任务创建使用同一行锁顺序：先锁当前 ACTIVE，再锁目标卷。
        // 因此切卷不可能插入到“选择活动卷”和“任务固化目标卷”之间。
        StorageVolumeEntity currentActive = volumeMapper.findActiveForUpdate();
        if (currentActive != null && !java.util.Objects.equals(currentActive.getId(), volumeId)
                && StorageVolumeStatus.Operation.ARCHIVE_GUARD.name().equals(currentActive.getOperationStatus()))
            throw new StorageVolumeOperationException("当前 ACTIVE 卷正在保障封存任务，禁止切换");
        StorageVolumeEntity entity = currentActive != null && java.util.Objects.equals(currentActive.getId(), volumeId)
                ? currentActive : volumeMapper.findByIdForUpdate(volumeId);
        // 兼容不经过 MyBatis 代理的单元测试桩。
        if (entity == null) entity = required(volumeId);
        boolean reconnectedColdVolume = StorageVolumeStatus.Tier.COLD.name().equals(entity.getTier())
                && StorageVolumeStatus.Write.READ_ONLY.name().equals(entity.getWriteStatus());
        boolean eligibleTier = StorageVolumeStatus.Tier.HOT.name().equals(entity.getTier()) || reconnectedColdVolume;
        if (!eligibleTier) throw new StorageVolumeOperationException("只有 HOT 卷或已重新接入的 COLD/READ_ONLY 卷可设为活动卷");
        if (!StorageVolumeStatus.Mount.ONLINE.name().equals(entity.getMountStatus()))
            throw new StorageVolumeUnavailableException("目标卷不在线，只有在线 HOT 卷或已重新接入且在线的 COLD/READ_ONLY 卷可设为活动卷");
        if (!StorageVolumeStatus.Admin.ENABLED.name().equals(defaultAdmin(entity)))
            throw new StorageVolumeOperationException("已停用的存储卷不能设为活动卷");
        if (!StorageVolumeStatus.Operation.IDLE.name().equals(defaultOperation(entity)))
            throw new StorageVolumeOperationException("目标卷正在执行其他生命周期操作");
        StorageVolumeMarkerService.VerificationResult marker = markerService.verify(Path.of(entity.getRootPath()), markerId(entity));
        if (!marker.valid()) throw new StorageVolumeOperationException("目标卷标记校验失败：" + marker.reason());
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
        if (!health.accessible()) throw new StorageVolumeUnavailableException("目标卷不可访问：" + health.errorMessage());
        if (!health.writable()) throw new StorageVolumeUnavailableException("目标卷不可写");
        int threshold = configs.getInt("storage.volume-switch.threshold-percent", 90, 1);
        if (threshold < 1) threshold = 90;
        if (health.totalBytes() <= 0 || health.usableBytes() <= 0
                || (health.totalBytes() - health.usableBytes()) * 100L >= health.totalBytes() * threshold) {
            throw new StorageVolumeUnavailableException("目标卷容量不足或已达切换阈值");
        }
        updateCapacitySnapshot(entity, health);
        entity.setLastProbedAt(LocalDateTime.now());
        entity.setLastErrorCode(null);
        entity.setLastErrorMessage(null);
        List<StorageVolumeEntity> activeVolumes = currentActive != null
                && !java.util.Objects.equals(currentActive.getId(), volumeId)
                ? List.of(currentActive)
                : volumeMapper.selectList(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, StorageVolumeStatus.Tier.HOT.name())
                .eq(StorageVolumeEntity::getWriteStatus, StorageVolumeStatus.Write.ACTIVE.name())
                .ne(StorageVolumeEntity::getId, volumeId).eq(StorageVolumeEntity::getIsDeleted, 0));
        for (StorageVolumeEntity active : activeVolumes) {
            ensureNoRunningWritesOrReservations(active);
            active.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
            active.setUpdateBy(operatorId);
            volumeMapper.updateById(active);
        }
        entity.setTier(StorageVolumeStatus.Tier.HOT.name());
        entity.setWriteStatus(StorageVolumeStatus.Write.ACTIVE.name());
        entity.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name());
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, reconnectedColdVolume ? "PROMOTE_COLD_TO_ACTIVE" : "SET_ACTIVE", entity, "SUCCESS", null);
        return entity;
    }

    /** 原子地把写权限从指定源卷交给接替卷；封存编排只允许调用该显式入口。 */
    @Transactional
    public StorageVolumeEntity switchActive(long sourceId, long successorId, long operatorId) {
        if (sourceId == successorId) throw new StorageVolumeOperationException("接替卷不能与源卷相同");
        StorageVolumeEntity source = volumeMapper.findActiveForUpdate();
        if (source == null || !java.util.Objects.equals(source.getId(), sourceId)) {
            throw new StorageVolumeOperationException("源卷已不是当前 ACTIVE 卷");
        }
        StorageVolumeEntity successor = volumeMapper.findByIdForUpdate(successorId);
        if (successor == null) throw new StorageVolumeOperationException("接替卷不存在");
        if (!StorageVolumeStatus.Tier.HOT.name().equals(successor.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(successor.getMountStatus())
                || !StorageVolumeStatus.Write.STANDBY.name().equals(successor.getWriteStatus())
                || !StorageVolumeStatus.Admin.ENABLED.name().equals(defaultAdmin(successor))
                || !StorageVolumeStatus.Operation.IDLE.name().equals(defaultOperation(successor))) {
            throw new StorageVolumeOperationException("接替卷必须是可用的 HOT/ONLINE/STANDBY 卷");
        }
        ensureNoRunningWritesOrReservations(source);
        validateTransitionTarget(successor, StorageVolumeStatus.Tier.HOT, StorageVolumeStatus.Write.STANDBY);
        source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        source.setOperationStatus(StorageVolumeStatus.Operation.SEALING.name());
        source.setUpdateBy(operatorId);
        volumeMapper.updateById(source);
        successor.setWriteStatus(StorageVolumeStatus.Write.ACTIVE.name());
        successor.setOperationStatus(StorageVolumeStatus.Operation.ARCHIVE_GUARD.name());
        successor.setUpdateBy(operatorId);
        volumeMapper.updateById(successor);
        audit(operatorId, "SWITCH_ACTIVE", successor, "SUCCESS", null);
        return successor;
    }

    /** ACTIVE 介质不可用时由管理员显式切换；旧盘重新出现后保持只读。 */
    @Transactional
    public StorageVolumeEntity failover(long unavailableActiveId, long standbyId, long operatorId) {
        StorageVolumeEntity source = volumeMapper.findByIdForUpdate(unavailableActiveId);
        if (source == null || !StorageVolumeStatus.Write.ACTIVE.name().equals(source.getWriteStatus())
                || StorageVolumeStatus.Mount.ONLINE.name().equals(source.getMountStatus())) {
            throw new StorageVolumeOperationException("只有不可用的 ACTIVE 卷可以执行故障切换");
        }
        StorageVolumeEntity standby = volumeMapper.findByIdForUpdate(standbyId);
        if (standby == null || !StorageVolumeStatus.Tier.HOT.name().equals(standby.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(standby.getMountStatus())
                || !StorageVolumeStatus.Write.STANDBY.name().equals(standby.getWriteStatus())) {
            throw new StorageVolumeOperationException("故障接替卷必须是 HOT/ONLINE/STANDBY");
        }
        validateTransitionTarget(standby, StorageVolumeStatus.Tier.HOT, StorageVolumeStatus.Write.STANDBY);
        source.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        source.setOperationStatus(StorageVolumeStatus.Operation.REPAIR_REQUIRED.name());
        source.setUpdateBy(operatorId);
        volumeMapper.updateById(source);
        standby.setWriteStatus(StorageVolumeStatus.Write.ACTIVE.name());
        standby.setOperationStatus(StorageVolumeStatus.Operation.IDLE.name());
        standby.setUpdateBy(operatorId);
        volumeMapper.updateById(standby);
        audit(operatorId, "FAILOVER", standby, "SUCCESS", null);
        return standby;
    }

    private String defaultAdmin(StorageVolumeEntity entity) {
        return entity.getAdminStatus() == null ? StorageVolumeStatus.Admin.ENABLED.name() : entity.getAdminStatus();
    }

    private String defaultOperation(StorageVolumeEntity entity) {
        return entity.getOperationStatus() == null ? StorageVolumeStatus.Operation.IDLE.name() : entity.getOperationStatus();
    }

    private void ensureNoOperationalReferences(StorageVolumeEntity entity, boolean includeCopies) {
        long runningWrites = jobMapper.selectCount(Wrappers.<JobEntity>lambdaQuery()
                .eq(JobEntity::getTargetStorageVolumeId, entity.getId())
                .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS"));
        if (runningWrites > 0) throw new StorageVolumeOperationException("存储卷仍有运行中写入任务");
        if (jobOrderMapper.countRunningSourceReferences(entity.getId()) > 0) {
            throw new StorageVolumeOperationException("存储卷仍被运行中恢复任务引用");
        }
        if (hasActiveReservations(entity)) throw new StorageVolumeOperationException("存储卷仍有活动空间预留");
        if (includeCopies && artifactCopyMapper.selectCount(Wrappers.<ArtifactCopyEntity>lambdaQuery()
                .eq(ArtifactCopyEntity::getStorageVolumeId, entity.getId())
                .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE").eq(ArtifactCopyEntity::getIsDeleted, 0)) > 0) {
            throw new StorageVolumeOperationException("存储卷仍被活跃文件副本引用");
        }
    }

    private void ensureNoRunningWritesOrReservations(StorageVolumeEntity entity) {
        long runningWrites = jobMapper.selectCount(Wrappers.<JobEntity>lambdaQuery()
                .eq(JobEntity::getTargetStorageVolumeId, entity.getId())
                .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS"));
        if (runningWrites > 0) throw new StorageVolumeOperationException("原活动卷仍有运行中的写入任务，禁止切换");
        if (hasActiveReservations(entity)) throw new StorageVolumeOperationException("原活动卷仍有活动空间预留，禁止切换");
    }

    private boolean hasActiveReservations(StorageVolumeEntity entity) {
        return entity.getCapacityPoolId() != null
                && reservationMapper.countActiveAt(entity.getCapacityPoolId(), LocalDateTime.now()) > 0;
    }

    private void validateTransitionTarget(StorageVolumeEntity entity, StorageVolumeStatus.Tier targetTier,
                                          StorageVolumeStatus.Write targetWrite) {
        StorageVolumeMarkerService.VerificationResult marker = markerService.verify(Path.of(entity.getRootPath()), markerId(entity));
        if (!marker.valid()) throw new StorageVolumeOperationException("存储卷卷标记校验失败：" + marker.reason());
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
        if (!health.accessible() || health.totalBytes() <= 0) {
            throw new StorageVolumeOperationException("存储卷健康或容量探测失败：" + health.errorMessage());
        }
        if (targetTier == StorageVolumeStatus.Tier.HOT && targetWrite == StorageVolumeStatus.Write.STANDBY
                && !health.writable()) {
            throw new StorageVolumeOperationException("备用热备卷不可写");
        }
        updateCapacitySnapshot(entity, health);
    }

    private void updateCapacitySnapshot(StorageVolumeEntity entity, StorageVolumeHealthService.HealthSnapshot health) {
        entity.setTotalBytes(health.accessible() && health.totalBytes() > 0 ? health.totalBytes() : null);
        entity.setUsableBytes(health.accessible() && health.totalBytes() > 0 ? health.usableBytes() : null);
    }

    /** 按主键读取逻辑卷。 */
    public StorageVolumeEntity required(long volumeId) {
        StorageVolumeEntity entity = volumeMapper.selectById(volumeId);
        if (entity == null || Integer.valueOf(1).equals(entity.getIsDeleted())) {
            throw new StorageVolumeOperationException("存储卷不存在");
        }
        return entity;
    }

    /** 供后台探测使用：只有已登记标记的介质才能被重新标记为在线。 */
    public boolean hasValidMarker(StorageVolumeEntity entity) {
        return markerService.verify(Path.of(entity.getRootPath()), markerId(entity)).valid();
    }

    private void audit(long operatorId, String action, StorageVolumeEntity volume, String result, String failureReason) {
        auditLogService.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, operatorId, "ADMIN", null,
                "STORAGE_VOLUME_" + action, "STORAGE_VOLUME", String.valueOf(volume.getId()), null, null,
                result, failureReason, LocalDateTime.now()));
    }

    private UUID markerId(StorageVolumeEntity entity) {
        try {
            return UUID.fromString(objectMapper.readTree(entity.getMarkerJson()).path("volumeId").asText());
        } catch (Exception ignored) {
            // 兼容早期尚未记录随机标记的卷；新纳管卷始终走 markerJson。
            return UUID.nameUUIDFromBytes(("yigongbao-storage-volume:" + entity.getId()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private UUID persistedMarkerId(StorageVolumeEntity entity) {
        if (entity == null || entity.getMarkerJson() == null || entity.getMarkerJson().isBlank()) return null;
        try { return UUID.fromString(objectMapper.readTree(entity.getMarkerJson()).path("volumeId").asText()); }
        catch (Exception ignored) { return null; }
    }

    public record RegisterCommand(Path mountPath, String volumeCode, String displayName,
                                  StorageVolumeStatus.Tier tier, Integer priority) {
    }

    private record MarkerInfo(String volumeId, int formatVersion) {
    }
}
