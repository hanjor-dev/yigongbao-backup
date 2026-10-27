package com.yigongbao.backup.account;

import lombok.Data;

import java.time.LocalDateTime;

/** 本地账号；密码和启停状态以本地值为准，线上同步仅更新被托管字段。 */
/** 本地账号领域对象；密码和状态以本地值为准。 */
@Data
public class BkUser {
    private Long id;
    private Long sourceUserId;
    private String username;
    private String passwordHash;
    private String realName;
    private String phone;
    private String email;
    private Integer enabledStatus;
    private String localPasswordOwnership = "LOCAL";
    private String localStatusOwnership = "LOCAL";
    private LocalDateTime sourceUpdatedAt;
    private LocalDateTime lastSyncedAt;
    private int failedLoginCount;
    private LocalDateTime lockedUntil;
}
