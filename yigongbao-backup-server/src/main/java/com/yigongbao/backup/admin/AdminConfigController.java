package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.config.ConfigService;
import com.yigongbao.backup.config.PersistentConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 管理员版本化配置查询和 CAS 更新接口。 */
@RestController
@RequestMapping("/api/v1/admin/configs")
@RequiredArgsConstructor
public class AdminConfigController {
    private final PersistentConfigService configs;
    private final AuditLogService audit;

    private static String rid() { return UUID.randomUUID().toString(); }

    @GetMapping("/categories")
    public ApiResponse<java.util.List<PersistentConfigService.Category>> categories() {
        StpUtil.checkRole("ADMIN");
        return ApiResponse.ok(configs.categories(), rid());
    }

    @GetMapping
    public ApiResponse<java.util.List<ConfigService.Entry>> list() {
        StpUtil.checkRole("ADMIN");
        return ApiResponse.ok(configs.list(), rid());
    }

    @GetMapping("/{key}")
    public ApiResponse<ConfigService.Entry> get(@PathVariable("key") String key) {
        StpUtil.checkRole("ADMIN");
        return ApiResponse.ok(configs.get(key), rid());
    }

    @PutMapping("/{key}")
    public ApiResponse<ConfigService.Entry> put(@PathVariable("key") String key, @RequestBody Command command,
                                                HttpServletRequest request) {
        StpUtil.checkRole("ADMIN");
        long actor = StpUtil.getLoginIdAsLong();
        try {
            ConfigService.Entry saved = configs.put(key, command.value(), command.expectedVersion(), actor);
            audit.appendActor(request, actor, "ADMIN", "CONFIG_CHANGE", "CONFIG", key,
                    null, "OK", null);
            return ApiResponse.ok(saved, rid());
        } catch (RuntimeException failure) {
            audit.appendActor(request, actor, "ADMIN", "CONFIG_CHANGE", "CONFIG", key,
                    null, "FAILED", failure.getMessage());
            throw failure;
        }
    }

    public record Command(String value, int expectedVersion) { }
}
