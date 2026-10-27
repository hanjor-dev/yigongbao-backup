package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置周期同步线上账号；线上不可达时保留本地账号继续独立登录。 */
@Component
@RequiredArgsConstructor
public class AccountSyncScheduler {
    private final ObjectProvider<RemoteAccountClient> clients;
    private final PersistentAccountSyncService sync;

    /** 每半小时执行一次；游标由生产实现持久化，默认客户端缺失时安全跳过。 */
    @Scheduled(fixedDelayString = "${backup.account.sync-delay-ms:1800000}")
    public void sync() {
        RemoteAccountClient client = clients.getIfAvailable();
        if (client == null) return;
        String cursor = null;
        do {
            RemoteAccountClient.Page page = client.fetch(cursor, 200);
            if (page == null || page.accounts() == null) return;
            page.accounts().forEach(sync::merge);
            cursor = page.nextCursor();
            if (!page.hasMore()) return;
        } while (cursor != null && !cursor.isBlank());
    }
}
