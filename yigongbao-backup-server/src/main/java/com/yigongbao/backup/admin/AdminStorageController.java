package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.UUID;

/** 兼容的容量探测接口：只允许探测已纳管卷，禁止任意路径输入。 */
@RestController
@RequestMapping("/api/v1/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {
    private final StorageProbe probe;
    private final StorageVolumeLifecycleService lifecycleService;

    @PostMapping("/probe")
    public ApiResponse<StorageProbe.StorageSnapshot> probe(@RequestBody ProbeCommand command) {
        StpUtil.checkRole("ADMIN");
        if (command == null || command.storageVolumeId() == null) {
            throw new IllegalArgumentException("必须指定已纳管存储卷");
        }
        StorageVolumeEntity volume = lifecycleService.required(command.storageVolumeId());
        return ApiResponse.ok(probe.probe(Path.of(volume.getRootPath())), requestId());
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    public record ProbeCommand(Long storageVolumeId) {
    }
}
