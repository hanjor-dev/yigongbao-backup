package com.yigongbao.backup.storage.volume;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
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

        StorageVolumeEntity entity = new StorageVolumeEntity();
        entity.setVolumeCode(command.volumeCode() == null || command.volumeCode().isBlank()
                ? "volume-" + UUID.randomUUID().toString().substring(0, 8) : command.volumeCode().trim());
        entity.setDisplayName(command.displayName().trim());
        entity.setAccessType(candidate.accessType().name());
        entity.setMountPath(candidate.mountPath().toString());
        entity.setRootPath(candidate.rootPath().toString());
        entity.setTier(command.tier() == null ? StorageVolumeStatus.Tier.HOT.name() : command.tier().name());
        entity.setMountStatus(StorageVolumeStatus.Mount.OFFLINE.name());
        entity.setWriteStatus(StorageVolumeStatus.Write.READ_ONLY.name());
        entity.setPriority(command.priority() == null ? 0 : command.priority());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        volumeMapper.insert(entity);

        UUID markerId = markerId(entity.getId());
        try {
            markerService.initialize(candidate.rootPath(), markerId);
            StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
            entity.setMarkerJson(objectMapper.writeValueAsString(new MarkerInfo(markerId.toString(), 1)));
            entity.setMountStatus(health.accessible() ? StorageVolumeStatus.Mount.ONLINE.name() : StorageVolumeStatus.Mount.UNAVAILABLE.name());
            entity.setLastProbedAt(LocalDateTime.now());
            entity.setLastErrorCode(health.errorCode());
            entity.setLastErrorMessage(health.errorMessage());
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
        StorageVolumeMarkerService.VerificationResult verification = markerService.verify(Path.of(entity.getRootPath()), markerId(volumeId));
        if (!verification.valid()) {
            throw new StorageVolumeOperationException("卷挂载确认失败：" + verification.reason());
        }
        StorageVolumeHealthService.HealthSnapshot health = healthService.probe(entity);
        entity.setMountStatus(health.accessible() ? StorageVolumeStatus.Mount.ONLINE.name() : StorageVolumeStatus.Mount.UNAVAILABLE.name());
        entity.setLastProbedAt(LocalDateTime.now());
        entity.setLastErrorCode(health.errorCode());
        entity.setLastErrorMessage(health.errorMessage());
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, "CONFIRM_MOUNTED", entity, health.accessible() ? "SUCCESS" : "FAILED", health.errorMessage());
        return entity;
    }

    /** 修改卷分层与写状态；ACTIVE 的全局互斥由后续切换服务及数据库唯一键共同保证。 */
    @Transactional
    public StorageVolumeEntity changeTier(long volumeId, StorageVolumeStatus.Tier tier,
                                          StorageVolumeStatus.Write writeStatus, long operatorId) {
        StorageVolumeEntity entity = required(volumeId);
        if (tier == null || writeStatus == null) {
            throw new StorageVolumeOperationException("存储卷目标状态不能为空");
        }
        if (writeStatus == StorageVolumeStatus.Write.ACTIVE
                && (!StorageVolumeStatus.Tier.HOT.name().equals(tier.name())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(entity.getMountStatus()))) {
            throw new StorageVolumeOperationException("仅在线 HOT 卷可以设为 ACTIVE");
        }
        entity.setTier(tier.name());
        entity.setWriteStatus(writeStatus.name());
        entity.setUpdateBy(operatorId);
        volumeMapper.updateById(entity);
        audit(operatorId, "CHANGE_STATE", entity, "SUCCESS", null);
        return entity;
    }

    /** 显式激活一个在线热备卷；已有活动卷必须由管理员先转为只读，避免隐式切换。 */
    @Transactional
    public StorageVolumeEntity setActive(long volumeId, long operatorId) {
        StorageVolumeEntity entity = required(volumeId);
        if (!StorageVolumeStatus.Tier.HOT.name().equals(entity.getTier())
                || !StorageVolumeStatus.Mount.ONLINE.name().equals(entity.getMountStatus())) {
            throw new StorageVolumeOperationException("只有在线 HOT 卷可设为活动卷");
        }
        long activeCount = volumeMapper.selectCount(Wrappers.<StorageVolumeEntity>lambdaQuery()
                .eq(StorageVolumeEntity::getTier, StorageVolumeStatus.Tier.HOT.name())
                .eq(StorageVolumeEntity::getWriteStatus, StorageVolumeStatus.Write.ACTIVE.name())
                .ne(StorageVolumeEntity::getId, volumeId).eq(StorageVolumeEntity::getIsDeleted, 0));
        if (activeCount > 0) {
            throw new StorageVolumeOperationException("已有活动热备卷，请先完成受控切换");
        }
        return changeTier(volumeId, StorageVolumeStatus.Tier.HOT, StorageVolumeStatus.Write.ACTIVE, operatorId);
    }

    /** 按主键读取逻辑卷。 */
    public StorageVolumeEntity required(long volumeId) {
        StorageVolumeEntity entity = volumeMapper.selectById(volumeId);
        if (entity == null || Integer.valueOf(1).equals(entity.getIsDeleted())) {
            throw new StorageVolumeOperationException("存储卷不存在");
        }
        return entity;
    }

    private void audit(long operatorId, String action, StorageVolumeEntity volume, String result, String failureReason) {
        auditLogService.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN, operatorId, "ADMIN", null,
                "STORAGE_VOLUME_" + action, "STORAGE_VOLUME", String.valueOf(volume.getId()), null, null,
                result, failureReason, LocalDateTime.now()));
    }

    private UUID markerId(long volumeId) {
        return UUID.nameUUIDFromBytes(("yigongbao-storage-volume:" + volumeId).getBytes(StandardCharsets.UTF_8));
    }

    public record RegisterCommand(Path mountPath, String volumeCode, String displayName,
                                  StorageVolumeStatus.Tier tier, Integer priority) {
    }

    private record MarkerInfo(String volumeId, int formatVersion) {
    }
}
