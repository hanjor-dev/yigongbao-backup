package com.yigongbao.backup.account;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.audit.AuditLogService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 查询本地账号列表，密码哈希永不返回。 */
    @GetMapping
    public ApiResponse<List<AccountView>> list() {
        admin();
        List<AccountView> result = mapper.selectList(Wrappers.<BkUserEntity>lambdaQuery().orderByAsc(BkUserEntity::getUsername))
                .stream().map(u -> new AccountView(u.getId(), u.getUsername(), u.getRealName(), u.getRoleCode(), u.getEnabledStatus(), u.getSourceUpdatedAt(), u.getLastSyncedAt())).toList();
        return ApiResponse.ok(result, rid());
    }

    /** 仅修改本地启用状态，保留线上同步值作为来源信息。 */
    @PutMapping("/{id}/status")
    public ApiResponse<AccountView> status(@PathVariable long id, @RequestBody StatusCommand command) {
        admin();
        BkUserEntity user = required(id);
        if (command == null || !command.enabledStatus().matches("ENABLED|DISABLED")) throw new IllegalArgumentException("账号状态非法");
        long actor = StpUtil.getLoginIdAsLong(); user.setEnabledStatus(command.enabledStatus()); user.setUpdateBy(actor); mapper.updateById(user); fieldControl.markLocal(user.getId(), BkUserFieldControlService.STATUS_FIELD, actor); audit.append(new AuditLogService.Event("ACCOUNT_STATUS_CHANGE", "USER", String.valueOf(id), "OK"));
        return ApiResponse.ok(view(user), rid());
    }

    /** 仅修改本地密码，不同步线上。 */
    @PutMapping("/{id}/password")
    public ApiResponse<Void> password(@PathVariable long id, @RequestBody PasswordCommand command) {
        admin();
        if (command == null || command.password() == null || command.password().length() < 8) throw new IllegalArgumentException("密码至少八位");
        long actor = StpUtil.getLoginIdAsLong(); BkUserEntity user = required(id); user.setPasswordHash(encoder.encode(command.password())); user.setUpdateBy(actor); mapper.updateById(user); fieldControl.markLocal(user.getId(), BkUserFieldControlService.PASSWORD_FIELD, actor); audit.append(new AuditLogService.Event("ADMIN_PASSWORD_RESET", "USER", String.valueOf(id), "OK"));
        return ApiResponse.ok(null, rid());
    }

    private BkUserEntity required(long id) { BkUserEntity user = mapper.selectById(id); if (user == null) throw new IllegalArgumentException("账号不存在"); return user; }
    private void admin() { StpUtil.checkRole("ADMIN"); }
    private static String rid() { return UUID.randomUUID().toString(); }
    private static AccountView view(BkUserEntity u) { return new AccountView(u.getId(), u.getUsername(), u.getRealName(), u.getRoleCode(), u.getEnabledStatus(), u.getSourceUpdatedAt(), u.getLastSyncedAt()); }
    public record StatusCommand(String enabledStatus) {}
    public record PasswordCommand(String password) {}
    public record AccountView(Long id, String username, String realName, String roleCode, String enabledStatus, java.time.LocalDateTime sourceUpdatedAt, java.time.LocalDateTime lastSyncedAt) {}
}
