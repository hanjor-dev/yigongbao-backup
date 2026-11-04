package com.yigongbao.backup.backup;

import com.yigongbao.backup.account.BkSyncCursorEntity;
import com.yigongbao.backup.account.BkSyncCursorMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BackupScheduleCheckpointServiceTest {
    @Test
    void completedWindowAdvancesIncrementalCursorToHighWatermark() {
        BkSyncCursorMapper mapper = mock(BkSyncCursorMapper.class);
        BkSyncCursorEntity row = row();
        row.setCursorValue("old-cursor");
        when(mapper.findForUpdate("BACKUP_CANDIDATE")).thenReturn(row);
        BackupScheduleCheckpointService service = new BackupScheduleCheckpointService(mapper);

        service.complete(200, "new-high-watermark");

        ArgumentCaptor<BkSyncCursorEntity> saved = ArgumentCaptor.forClass(BkSyncCursorEntity.class);
        verify(mapper).updateById(saved.capture());
        assertThat(saved.getValue().getCursorValue()).isEqualTo("new-high-watermark");
        assertThat(saved.getValue().getHighWatermark()).isEmpty();
        assertThat(saved.getValue().getLastItemCount()).isEqualTo(200);
    }

    @Test
    void exposesLastSuccessfulScheduledRun() {
        BkSyncCursorMapper mapper = mock(BkSyncCursorMapper.class);
        BkSyncCursorEntity row = row();
        row.setLastSuccessAt(LocalDateTime.of(2026, 11, 2, 5, 0));
        when(mapper.find("BACKUP_CANDIDATE")).thenReturn(row);

        assertThat(new BackupScheduleCheckpointService(mapper).lastSuccessAt())
                .isEqualTo(row.getLastSuccessAt());
    }

    private static BkSyncCursorEntity row() {
        BkSyncCursorEntity row = new BkSyncCursorEntity();
        row.setId(1L);
        row.setCursorType("BACKUP_CANDIDATE");
        row.setCursorValue("");
        row.setHighWatermark("");
        row.setLastItemCount(0);
        return row;
    }
}
