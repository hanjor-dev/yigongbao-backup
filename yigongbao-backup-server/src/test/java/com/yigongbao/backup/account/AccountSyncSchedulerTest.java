package com.yigongbao.backup.account;

import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

/** 定时器必须基于数据库成功时间判断，而不是进程内时间。 */
class AccountSyncSchedulerTest {

    @Test
    void executesWhenPersistentLastSuccessIsOlderThanConfiguredDelay() {
        AccountSyncCoordinator coordinator = mock(AccountSyncCoordinator.class);
        AccountSyncCheckpointService checkpoints = mock(AccountSyncCheckpointService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getLong("account.sync-delay-minutes", 30L, 1L)).thenReturn(30L);
        when(checkpoints.status()).thenReturn(new AccountSyncCheckpointService.Status(
                "SUCCEEDED", LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1),
                2, null, "cursor"));
        when(coordinator.synchronize(false)).thenReturn(
                new AccountSyncCoordinator.Result("SUCCEEDED", 1, null));

        new AccountSyncScheduler(coordinator, checkpoints, configs).sync();

        verify(coordinator).synchronize(false);
    }
}
