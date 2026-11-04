package com.yigongbao.backup.account;

import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 账号同步协调器的游标推进、失败恢复与互斥行为。 */
class AccountSyncCoordinatorTest {

    @Test
    void resumesFromPersistentCursorAndCommitsEveryPage() {
        RemoteAccountClient client = mock(RemoteAccountClient.class);
        AccountSyncCheckpointService checkpoints = mock(AccountSyncCheckpointService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getBoolean("online.enabled", false)).thenReturn(true);
        when(configs.getBoolean("account.sync-enabled", true)).thenReturn(true);
        when(configs.getInt("account.sync-page-size", 200, 1)).thenReturn(200);
        when(checkpoints.currentCursor()).thenReturn("2026-10-29T01:00:00Z|5");
        RemoteAccount account = new RemoteAccount(6L, "u6", "$2a$10$stored-hash", 1,
                "U6", null, null, LocalDateTime.of(2026, 10, 29, 2, 0), false);
        when(client.fetch("2026-10-29T01:00:00Z|5", 200))
                .thenReturn(new RemoteAccountClient.Page(List.of(account), "2026-10-29T02:00:00Z|6", false));
        AccountSyncCoordinator coordinator = new AccountSyncCoordinator(client, checkpoints, configs);

        AccountSyncCoordinator.Result result = coordinator.synchronize(false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        verify(checkpoints).commitPage(List.of(account), "2026-10-29T02:00:00Z|6");
        verify(checkpoints).complete(1);
    }

    @Test
    void recordsFailureWithoutAdvancingUncommittedPage() {
        RemoteAccountClient client = mock(RemoteAccountClient.class);
        AccountSyncCheckpointService checkpoints = mock(AccountSyncCheckpointService.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(configs.getInt(anyString(), anyInt(), anyInt())).thenReturn(200);
        when(checkpoints.currentCursor()).thenReturn("cursor-before");
        when(client.fetch("cursor-before", 200)).thenThrow(new IllegalStateException("remote down"));
        AccountSyncCoordinator coordinator = new AccountSyncCoordinator(client, checkpoints, configs);

        AccountSyncCoordinator.Result result = coordinator.synchronize(false);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(checkpoints, never()).commitPage(anyList(), anyString());
        verify(checkpoints).fail("remote down");
    }
}
