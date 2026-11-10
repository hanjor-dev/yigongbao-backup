package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 账号同步状态持久化契约测试。 */
class AccountSyncCheckpointServiceTest {

    @Test
    void successfulRunClearsPersistedFailureMessage() {
        BkSyncCursorMapper mapper = mock(BkSyncCursorMapper.class);
        BkSyncCursorEntity cursor = new BkSyncCursorEntity();
        cursor.setId(1L);
        cursor.setCursorType("USER");
        cursor.setLastErrorMessage("线上服务返回HTTP 404");
        when(mapper.findForUpdate("USER")).thenReturn(cursor);
        AccountSyncCheckpointService service = new AccountSyncCheckpointService(
                mapper, mock(PersistentAccountSyncService.class));

        service.complete(0);

        ArgumentCaptor<BkSyncCursorEntity> updated = ArgumentCaptor.forClass(BkSyncCursorEntity.class);
        verify(mapper).updateById(updated.capture());
        assertThat(updated.getValue().getSyncStatus()).isEqualTo("SUCCEEDED");
        assertThat(updated.getValue().getLastErrorMessage()).isEmpty();
    }

    @Test
    void blankPersistedFailureMessageIsHiddenFromStatusResponse() {
        BkSyncCursorMapper mapper = mock(BkSyncCursorMapper.class);
        BkSyncCursorEntity cursor = new BkSyncCursorEntity();
        cursor.setSyncStatus("SUCCEEDED");
        cursor.setLastErrorMessage("");
        when(mapper.find("USER")).thenReturn(cursor);
        AccountSyncCheckpointService service = new AccountSyncCheckpointService(
                mapper, mock(PersistentAccountSyncService.class));

        assertThat(service.status().lastErrorMessage()).isNull();
    }

    @Test
    void successfulCheckpointStartsNextRunWithLookbackAndFreshWatermark() {
        BkSyncCursorMapper mapper = mock(BkSyncCursorMapper.class);
        BkSyncCursorEntity cursor = new BkSyncCursorEntity();
        cursor.setId(1L); cursor.setCursorType("USER"); cursor.setSyncStatus("SUCCEEDED");
        cursor.setCursorValue("2026-10-29T01:00:00Z|99"); cursor.setHighWatermark("old-watermark");
        cursor.setLookbackSeconds(60);
        when(mapper.findForUpdate("USER")).thenReturn(cursor);

        AccountSyncCheckpointService.RunState state = new AccountSyncCheckpointService(
                mapper, mock(PersistentAccountSyncService.class)).begin();

        assertThat(state.cursor()).isEqualTo("2026-10-29T00:59Z|0");
        assertThat(state.highWatermark()).isNull();
        assertThat(cursor.getHighWatermark()).isEmpty();
    }
}
