package com.yigongbao.backup.account;

import java.time.LocalDateTime;

/** 线上账号增量同步 DTO。 */
public record RemoteAccount(Long id, String username, String passwordHash, Integer status,
                            String realName, String phone, String email,
                            LocalDateTime updateTime, boolean deleted) {
    public RemoteAccount(Long id, String username, String passwordHash, Integer status,
                         String realName, String phone, String email, LocalDateTime updateTime) {
        this(id, username, passwordHash, status, realName, phone, email, updateTime, false);
    }
}
