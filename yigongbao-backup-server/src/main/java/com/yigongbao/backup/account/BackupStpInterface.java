package com.yigongbao.backup.account;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 本地备份系统的 Sa-Token 权限数据提供器。
 * 登录态只保存账号 ID，角色在鉴权时从本地账号表读取。
 */
@Component
@RequiredArgsConstructor
public class BackupStpInterface implements StpInterface {

    private final BkUserMapper userMapper;

    /** 返回当前账号的角色编码，供 checkRole/checkRoleOr 使用。 */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        BkUserEntity user = userMapper.selectById(Long.valueOf(String.valueOf(loginId)));
        if (user == null || !"ENABLED".equals(user.getEnabledStatus())
                || user.getRoleCode() == null || user.getRoleCode().isBlank()) {
            return Collections.emptyList();
        }
        return List.of(user.getRoleCode().trim().toUpperCase());
    }

    /** 当前系统暂不配置细粒度权限码，具体接口通过角色进行保护。 */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
