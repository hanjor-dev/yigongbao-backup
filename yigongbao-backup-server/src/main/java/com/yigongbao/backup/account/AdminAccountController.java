package com.yigongbao.backup.account;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.audit.AuditLogService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import com.yigongbao.backup.config.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

/** 管理员本地账号查询、启停和密码重置接口；本地变更不会反向写线上系统。 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {
    private final BkUserMapper mapper;
    private final BkUserFieldControlService fieldControl;
    private final AuditLogService audit;
    private final AccountSyncCheckpointService syncCheckpoints;
    private final AccountSyncCoordinator syncCoordinator;
    private final RuntimeConfigService configs;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 查询本地账号列表，密码哈希永不返回。 */
    @GetMapping
    public ApiResponse<Page<AccountView>> list(
            @RequestParam(name = "current", defaultValue = "1") long current,
            @RequestParam(name = "size", defaultValue = "20") long size) {
        admin();
        if (current < 1 || size < 1 || size > 100) throw new IllegalArgumentException("账号分页参数非法");
        Page<BkUserEntity> source = mapper.selectPage(new Page<>(current, size),
                Wrappers.<BkUserEntity>lambdaQuery().orderByAsc(BkUserEntity::getUsername));
        Page<AccountView> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(source.getRecords().stream().map(AdminAccountController::view).toList());
        return ApiResponse.ok(result, rid());
    }

    /** 查询账号同步开关、持久化游标和最近运行结果。 */
    @GetMapping("/sync/status")
    public ApiResponse<SyncStatusView> syncStatus() {
        admin();
        AccountSyncCheckpointService.Status status = syncCheckpoints.status();
        return ApiResponse.ok(new SyncStatusView(
                configs.getBoolean("account.sync-enabled", true),
                configs.getBoolean("online.enabled", false),
                status.status(), status.lastAttemptAt(), status.lastSuccessAt(), status.lastItemCount(),
                status.lastErrorMessage(), status.cursor()), rid());
    }

    /** 管理员立即触发同步；与定时同步使用同一互斥锁和持久化检查点。 */
    @PostMapping("/sync")
    public ApiResponse<AccountSyncCoordinator.Result> syncNow(HttpServletRequest request) {
        admin();
        long actor = StpUtil.getLoginIdAsLong();
        AccountSyncCoordinator.Result result = syncCoordinator.synchronize(true);
        audit.appendActor(request, actor, "ADMIN", "ACCOUNT_SYNC_MANUAL", "USER",
                String.valueOf(actor), null, result.status(), null);
        return ApiResponse.ok(result, rid());
    }

    /** 仅修改本地启用状态，保留线上同步值作为来源信息。 */
    @PutMapping("/{id}/status")
    public ApiResponse<AccountView> status(@PathVariable("id") long id, @RequestBody StatusCommand command,
                                           HttpServletRequest request) {
        admin();
        BkUserEntity user = required(id);
        if (command == null || !command.enabledStatus().matches("ENABLED|DISABLED")) throw new IllegalArgumentException("账号状态非法");
        long actor = StpUtil.getLoginIdAsLong(); user.setEnabledStatus(command.enabledStatus()); user.setUpdateBy(actor); mapper.updateById(user); fieldControl.markLocal(user.getId(), BkUserFieldControlService.STATUS_FIELD, actor); if ("DISABLED".equals(command.enabledStatus())) StpUtil.kickout(id); audit.appendActor(request, actor, "ADMIN", "ACCOUNT_STATUS_CHANGE", "USER", String.valueOf(id), null, "OK", null);
        return ApiResponse.ok(view(user), rid());
    }

    /** 仅修改本地密码，不同步线上。 */
    @PutMapping("/{id}/password")
    public ApiResponse<Void> password(@PathVariable("id") long id, @RequestBody PasswordCommand command,
                                      HttpServletRequest request) {
        admin();
        if (command == null || command.password() == null || command.password().length() < 8) throw new IllegalArgumentException("密码至少八位");
        long actor = StpUtil.getLoginIdAsLong(); BkUserEntity user = required(id); user.setPasswordHash(encoder.encode(command.password())); user.setUpdateBy(actor); mapper.updateById(user); fieldControl.markLocal(user.getId(), BkUserFieldControlService.PASSWORD_FIELD, actor); StpUtil.kickout(id); audit.appendActor(request, actor, "ADMIN", "ADMIN_PASSWORD_RESET", "USER", String.valueOf(id), null, "OK", null);
        return ApiResponse.ok(null, rid());
    }

    private BkUserEntity required(long id) { BkUserEntity user = mapper.selectById(id); if (user == null) throw new IllegalArgumentException("账号不存在"); return user; }
    private void admin() { StpUtil.checkRole("ADMIN"); }
    private static String rid() { return UUID.randomUUID().toString(); }
    private static AccountView view(BkUserEntity u) { return new AccountView(u.getId(), u.getUsername(), u.getRealName(), u.getRoleCode(), u.getEnabledStatus(), u.getSourceUpdatedAt(), u.getLastSyncedAt()); }
    public record StatusCommand(String enabledStatus) {}
    public record PasswordCommand(String password) {}
    public record AccountView(Long id, String username, String realName, String roleCode, String enabledStatus, java.time.LocalDateTime sourceUpdatedAt, java.time.LocalDateTime lastSyncedAt) {}
    public record SyncStatusView(boolean syncEnabled, boolean onlineEnabled, String status,
                                 java.time.LocalDateTime lastAttemptAt, java.time.LocalDateTime lastSuccessAt,
                                 int lastItemCount, String lastErrorMessage, String cursor) { }
}
