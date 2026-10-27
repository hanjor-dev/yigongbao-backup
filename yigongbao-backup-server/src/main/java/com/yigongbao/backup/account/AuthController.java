package com.yigongbao.backup.account;

import cn.dev33.satoken.stp.StpUtil; import com.yigongbao.backup.audit.AuditLogService; import com.yigongbao.backup.common.ApiResponse; import jakarta.servlet.http.HttpServletRequest; import lombok.RequiredArgsConstructor; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; import org.springframework.web.bind.annotation.*;

/** 本地账号登录、退出和当前用户信息接口。 */
@RestController @RequestMapping("/api/v1/auth") @RequiredArgsConstructor
public class AuthController {
    private final BkUserMapper mapper; private final PersistentAccountSyncService auth; private final AuditLogService audit; private final BkUserFieldControlService fieldControl; private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private static String requestId(){return java.util.UUID.randomUUID().toString();}
    @PostMapping("/login") public ApiResponse<LoginResult> login(@RequestBody LoginCommand cmd, HttpServletRequest request){ if(cmd==null||cmd.username()==null||cmd.password()==null||cmd.username().isBlank())throw new IllegalArgumentException("账号或密码不能为空"); BkUserEntity user=mapper.findByUsername(cmd.username()); boolean valid=user!=null&&auth.verify(cmd.username(),cmd.password()); if(!valid){audit.append(new AuditLogService.Event("LOGIN_FAILURE","USER",cmd.username(),"AUTH_FAILED"));throw new IllegalArgumentException("账号或密码错误");} StpUtil.login(user.getId()); audit.append(new AuditLogService.Event("LOGIN_SUCCESS","USER",String.valueOf(user.getId()),"OK")); return ApiResponse.ok(new LoginResult(StpUtil.getTokenValue(),user.getId(),user.getUsername(),user.getRoleCode()),requestId()); }
    @PostMapping("/logout") public ApiResponse<Void> logout(){StpUtil.logout();return ApiResponse.ok(null,requestId());}
    /** 返回当前账号非敏感资料，禁止把密码哈希等认证字段输出给浏览器。 */
    @GetMapping("/profile") public ApiResponse<ProfileView> profile(){StpUtil.checkLogin(); BkUserEntity user=mapper.selectById(StpUtil.getLoginIdAsLong()); if(user==null)throw new IllegalArgumentException("账号不存在"); return ApiResponse.ok(new ProfileView(user.getId(),user.getUsername(),user.getRealName(),user.getRoleCode(),user.getEnabledStatus()),requestId());}
    /** 修改当前账号本地密码，线上账号密码不受影响。 */
    @PutMapping("/password") public ApiResponse<Void> password(@RequestBody PasswordCommand command){StpUtil.checkLogin();if(command==null||command.password()==null||command.password().length()<8)throw new IllegalArgumentException("密码至少八位");long actor=StpUtil.getLoginIdAsLong();BkUserEntity user=mapper.selectById(actor);if(user==null)throw new IllegalArgumentException("账号不存在");user.setPasswordHash(encoder.encode(command.password()));user.setUpdateBy(actor);mapper.updateById(user);fieldControl.markLocal(user.getId(), BkUserFieldControlService.PASSWORD_FIELD, actor);audit.append(new AuditLogService.Event("PASSWORD_CHANGE","USER",String.valueOf(user.getId()),"OK"));return ApiResponse.ok(null,requestId());}
    public record LoginCommand(String username,String password){} public record PasswordCommand(String password){} public record LoginResult(String token,Long userId,String username,String roleCode){} public record ProfileView(Long id,String username,String realName,String roleCode,String enabledStatus){}
}
