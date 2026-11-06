package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.volume.DiscoveredStorageVolume;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import com.yigongbao.backup.storage.volume.StorageVolumeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 管理员存储卷纳管与生命周期操作接口。 */
@RestController
@RequestMapping("/api/v1/admin/storage-volumes")
@RequiredArgsConstructor
public class AdminStorageVolumeController {
    private final StorageVolumeLifecycleService lifecycleService;

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
                command == null || command.mountPath() == null ? null : Path.of(command.mountPath()),
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

    public record RegisterCommand(String mountPath, String volumeCode, String displayName,
                                  StorageVolumeStatus.Tier tier, Integer priority) {
    }

    public record StateCommand(StorageVolumeStatus.Tier tier, StorageVolumeStatus.Write writeStatus) {
    }
}
