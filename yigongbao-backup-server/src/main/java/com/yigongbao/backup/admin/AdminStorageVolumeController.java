package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.volume.DiscoveredStorageVolume;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import com.yigongbao.backup.storage.location.ArtifactCopyEntity;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.reservation.SpaceReservationMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.audit.AuditLogEntity;
import com.yigongbao.backup.audit.AuditLogMapper;
import com.yigongbao.backup.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** 管理员存储卷纳管与生命周期操作接口。 */
@RestController
@RequestMapping("/api/v1/admin/storage-volumes")
@RequiredArgsConstructor
public class AdminStorageVolumeController {
    private final StorageVolumeLifecycleService lifecycleService;
    private final StorageVolumeMapper volumeMapper;
    private final ArtifactCopyMapper copyMapper;
    private final JobMapper jobMapper;
    private final JobOrderMapper jobOrderMapper;
    private final SpaceReservationMapper reservationMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditLogService auditLogService;

    /** 已纳管卷清单；管理页不再从配置中的路径文本推导实际存储位置。 */
    @GetMapping
    public ApiResponse<List<StorageVolumeView>> list() {
        admin();
        return ApiResponse.ok(volumeMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<StorageVolumeEntity>lambdaQuery().eq(StorageVolumeEntity::getIsDeleted, 0)
                .orderByAsc(StorageVolumeEntity::getTier).orderByAsc(StorageVolumeEntity::getPriority)).stream()
                .map(StorageVolumeView::from).toList(), requestId());
    }

    /** 卷详情包含身份标记、当前引用和最近审计，不再使用前端占位说明。 */
    @GetMapping("/{id}")
    public ApiResponse<StorageVolumeDetailView> detail(@PathVariable("id") long id) {
        admin();
        StorageVolumeEntity volume = lifecycleService.required(id);
        long activeCopies = copyMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ArtifactCopyEntity>lambdaQuery().eq(ArtifactCopyEntity::getStorageVolumeId, id)
                .eq(ArtifactCopyEntity::getLifecycle, "ACTIVE").eq(ArtifactCopyEntity::getIsDeleted, 0));
        List<ArtifactCopyEntity> orderPackageCopies = copyMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ArtifactCopyEntity>lambdaQuery().eq(ArtifactCopyEntity::getStorageVolumeId, id)
                .eq(ArtifactCopyEntity::getArtifactType, "ORDER_PACKAGE").eq(ArtifactCopyEntity::getIsDeleted, 0));
        long orderPackageCount = orderPackageCopies.stream().map(ArtifactCopyEntity::getArtifactId)
                .filter(java.util.Objects::nonNull).distinct().count();
        long orderPackageBytes = orderPackageCopies.stream().map(ArtifactCopyEntity::getSize)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
        long runningTargets = jobMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<JobEntity>lambdaQuery().eq(JobEntity::getTargetStorageVolumeId, id)
                .in(JobEntity::getStatus, "CREATED", "RUNNING", "RETRYING_FAILED_ORDERS")
                .eq(JobEntity::getIsDeleted, 0));
        long runningTasks = runningTargets + jobOrderMapper.countRunningSourceReferences(id);
        long reservations = volume.getCapacityPoolId() == null ? 0L
                : reservationMapper.countActiveAt(volume.getCapacityPoolId(), java.time.LocalDateTime.now());
        List<StorageVolumeAuditView> audits = auditLogMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<AuditLogEntity>lambdaQuery().eq(AuditLogEntity::getObjectType, "STORAGE_VOLUME")
                        .eq(AuditLogEntity::getObjectId, String.valueOf(id)).eq(AuditLogEntity::getIsDeleted, 0)
                        .orderByDesc(AuditLogEntity::getOccurredAt).orderByDesc(AuditLogEntity::getId).last("LIMIT 20"))
                .stream().map(StorageVolumeAuditView::from).toList();
        return ApiResponse.ok(new StorageVolumeDetailView(StorageVolumeView.from(volume), volume.getVolumeFingerprint(),
                volume.getMarkerJson(), activeCopies, orderPackageCount, orderPackageBytes, runningTasks, reservations, audits), requestId());
    }

    /** 扫描服务账号当前可见的候选盘；该接口不会修改目录和数据库。 */
    @PostMapping("/scan")
    public ApiResponse<List<DiscoveredStorageVolume>> scan() {
        admin();
        return ApiResponse.ok(lifecycleService.scan(), requestId());
    }

    /** 仅将扫描到的挂载点纳管，根目录由服务端固定派生。 */
    @PostMapping
    public ApiResponse<StorageVolumeEntity> register(@RequestBody RegisterCommand command) {
        admin();
        StorageVolumeLifecycleService.RegisterCommand request = new StorageVolumeLifecycleService.RegisterCommand(
                command == null || command.mountPath() == null ? null : parseMountPath(command.mountPath()),
                command == null ? null : command.volumeCode(), command == null ? null : command.displayName(),
                command == null ? null : command.tier(), command == null ? null : command.priority());
        return ApiResponse.ok(lifecycleService.register(request, StpUtil.getLoginIdAsLong()), requestId());
    }

    /** 确认已经重新挂载的冷备或热备卷。 */
    @PostMapping("/{id}/confirm-mounted")
    public ApiResponse<StorageVolumeEntity> confirmMounted(@PathVariable("id") long id,
            @RequestBody(required = false) MountCommand command) {
        admin();
        long actor=StpUtil.getLoginIdAsLong();
        try { return ApiResponse.ok(command == null || command.mountPath() == null || command.mountPath().isBlank()
                ? lifecycleService.confirmMounted(id, actor)
                : lifecycleService.confirmMounted(id, parseMountPath(command.mountPath()), actor), requestId()); }
        catch(RuntimeException failure){auditIdentityFailure(id,actor,failure);throw failure;}
    }

    /** 重新探测同时校验卷标记，避免盘符复用时错误接管介质。 */
    @PostMapping("/{id}/probe")
    public ApiResponse<StorageVolumeEntity> probe(@PathVariable("id") long id) {
        admin();
        long actor=StpUtil.getLoginIdAsLong();
        try{return ApiResponse.ok(lifecycleService.confirmMounted(id, actor), requestId());}
        catch(RuntimeException failure){auditIdentityFailure(id,actor,failure);throw failure;}
    }

    @PostMapping("/{id}/set-standby")
    public ApiResponse<StorageVolumeEntity> setStandby(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.changeTier(id, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.STANDBY, StpUtil.getLoginIdAsLong()), requestId());
    }

    @PostMapping("/{id}/to-hot")
    public ApiResponse<StorageVolumeEntity> toHot(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.changeTier(id, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.READ_ONLY, StpUtil.getLoginIdAsLong()), requestId());
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<StorageVolumeEntity> disable(@PathVariable("id") long id) {
        admin();
        StorageVolumeEntity before = lifecycleService.required(id);
        return ApiResponse.ok(lifecycleService.changeTier(id, StorageVolumeStatus.Tier.valueOf(before.getTier()),
                StorageVolumeStatus.Write.DISABLED, StpUtil.getLoginIdAsLong()), requestId());
    }

    /** 修改分层与写入状态，不能绕过后端状态机。 */
    @PostMapping("/{id}/state")
    public ApiResponse<StorageVolumeEntity> changeState(@PathVariable("id") long id, @RequestBody StateCommand command) {
        admin();
        return ApiResponse.ok(lifecycleService.changeTier(id, command.tier(), command.writeStatus(),
                StpUtil.getLoginIdAsLong()), requestId());
    }

    /** 设置唯一活动热备卷。 */
    @PostMapping("/{id}/set-active")
    public ApiResponse<StorageVolumeEntity> setActive(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.setActive(id, StpUtil.getLoginIdAsLong()), requestId());
    }

    private static void admin() {
        StpUtil.checkRole("ADMIN");
    }

    private void auditIdentityFailure(long id,long actor,RuntimeException failure){auditLogService.append(new AuditLogService.Event(AuditLogService.DEFAULT_CHAIN,actor,"ADMIN",null,"STORAGE_VOLUME_IDENTITY_VERIFY_FAILED","STORAGE_VOLUME",String.valueOf(id),null,null,"FAILED",failure.getMessage(),java.time.LocalDateTime.now()));}

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    /** 浏览器序列化 Path 时可能得到 file:///D:/；纳管接口同时兼容该 URI 和原生路径文本。 */
    static Path parseMountPath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("挂载点不能为空");
        String normalized = value.trim();
        return normalized.regionMatches(true, 0, "file:", 0, 5)
                ? Path.of(URI.create(normalized)) : Path.of(normalized);
    }

    public record RegisterCommand(String mountPath, String volumeCode, String displayName,
                                  StorageVolumeStatus.Tier tier, Integer priority) {
    }

    public record StateCommand(StorageVolumeStatus.Tier tier, StorageVolumeStatus.Write writeStatus) {
    }

    public record MountCommand(String mountPath) { }

    /** 管理页读取最近一次健康探测的容量快照，避免列表请求被慢速或离线网络卷阻塞。 */
    public record StorageVolumeView(Long id, String volumeCode, String displayName, String rootPath, String tier,
                                    String mountStatus, String writeStatus, Integer priority, java.time.LocalDateTime lastProbedAt,
                                    String lastErrorMessage, Long totalBytes, Long usableBytes, Long usedBytes,
                                    String adminStatus, String operationStatus, String archiveNote, String offlineLabelNote,
                                    String physicalLocation, java.time.LocalDateTime archivedAt,
                                    java.time.LocalDateTime orderCreatedFrom, java.time.LocalDateTime orderCreatedTo,
                                    Long archivedOrderCount, Long archivedFileCount, Long archivedBytes) {
        static StorageVolumeView from(StorageVolumeEntity value) {
            Long total = value.getTotalBytes();
            Long usable = value.getUsableBytes();
            return new StorageVolumeView(value.getId(), value.getVolumeCode(), value.getDisplayName(), value.getRootPath(), value.getTier(),
                    value.getMountStatus(), value.getWriteStatus(), value.getPriority(), value.getLastProbedAt(), value.getLastErrorMessage(),
                    total, usable, total == null || usable == null ? null : total - usable,
                    value.getAdminStatus(), value.getOperationStatus(), value.getArchiveNote(), value.getOfflineLabelNote(),
                    value.getPhysicalLocation(), value.getArchivedAt(), value.getOrderCreatedFrom(), value.getOrderCreatedTo(),
                    value.getArchivedOrderCount(), value.getArchivedFileCount(), value.getArchivedBytes());
        }
    }

    public record StorageVolumeDetailView(StorageVolumeView volume, String volumeFingerprint, String markerJson,
                                          long activeCopyCount, long orderPackageCount, long orderPackageBytes,
                                          long runningTaskCount, long activeReservationCount,
                                          List<StorageVolumeAuditView> recentAudits) {
    }

    public record StorageVolumeAuditView(Long id, String action, Long actorUserId, String resultCode,
                                         String failureReason, java.time.LocalDateTime occurredAt) {
        static StorageVolumeAuditView from(AuditLogEntity value) {
            return new StorageVolumeAuditView(value.getId(), value.getAction(), value.getActorUserId(), value.getResultCode(),
                    value.getFailureReason(), value.getOccurredAt());
        }
    }
}
