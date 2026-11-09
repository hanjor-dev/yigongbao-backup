package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.volume.DiscoveredStorageVolume;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileStore;
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

    /** 已纳管卷清单；管理页不再从配置中的路径文本推导实际存储位置。 */
    @GetMapping
    public ApiResponse<List<StorageVolumeView>> list() {
        admin();
        return ApiResponse.ok(volumeMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<StorageVolumeEntity>lambdaQuery().eq(StorageVolumeEntity::getIsDeleted, 0)
                .orderByAsc(StorageVolumeEntity::getTier).orderByAsc(StorageVolumeEntity::getPriority)).stream()
                .map(StorageVolumeView::from).toList(), requestId());
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
    public ApiResponse<StorageVolumeEntity> confirmMounted(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.confirmMounted(id, StpUtil.getLoginIdAsLong()), requestId());
    }

    /** 重新探测同时校验卷标记，避免盘符复用时错误接管介质。 */
    @PostMapping("/{id}/probe")
    public ApiResponse<StorageVolumeEntity> probe(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.confirmMounted(id, StpUtil.getLoginIdAsLong()), requestId());
    }

    @PostMapping("/{id}/set-standby")
    public ApiResponse<StorageVolumeEntity> setStandby(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.changeTier(id, StorageVolumeStatus.Tier.HOT,
                StorageVolumeStatus.Write.STANDBY, StpUtil.getLoginIdAsLong()), requestId());
    }

    @PostMapping("/{id}/to-cold")
    public ApiResponse<StorageVolumeEntity> toCold(@PathVariable("id") long id) {
        admin();
        return ApiResponse.ok(lifecycleService.changeTier(id, StorageVolumeStatus.Tier.COLD,
                StorageVolumeStatus.Write.READ_ONLY, StpUtil.getLoginIdAsLong()), requestId());
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

    /** 管理页容量为实时探测值；不可达卷返回 null，不能用历史数值伪装为在线。 */
    public record StorageVolumeView(Long id, String volumeCode, String displayName, String rootPath, String tier,
                                    String mountStatus, String writeStatus, Integer priority, java.time.LocalDateTime lastProbedAt,
                                    String lastErrorMessage, Long totalBytes, Long usableBytes, Long usedBytes) {
        static StorageVolumeView from(StorageVolumeEntity value) {
            Long total = null, usable = null;
            try { FileStore store = Files.getFileStore(Path.of(value.getRootPath())); total = store.getTotalSpace(); usable = store.getUsableSpace(); }
            catch (Exception ignored) { }
            return new StorageVolumeView(value.getId(), value.getVolumeCode(), value.getDisplayName(), value.getRootPath(), value.getTier(),
                    value.getMountStatus(), value.getWriteStatus(), value.getPriority(), value.getLastProbedAt(), value.getLastErrorMessage(),
                    total, usable, total == null || usable == null ? null : total - usable);
        }
    }
}
