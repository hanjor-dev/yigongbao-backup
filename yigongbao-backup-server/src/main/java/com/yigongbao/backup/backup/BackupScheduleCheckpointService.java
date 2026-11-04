package com.yigongbao.backup.backup;

import com.yigongbao.backup.account.BkSyncCursorEntity;
import com.yigongbao.backup.account.BkSyncCursorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 持久化自动备份扫描状态和最近运行结果。 */
@Service
@RequiredArgsConstructor
public class BackupScheduleCheckpointService {
    private static final String TYPE = "BACKUP_CANDIDATE";
    private final BkSyncCursorMapper cursors;

    public LocalDateTime lastSuccessAt() {
        BkSyncCursorEntity row = cursors.find(TYPE);
        return row == null ? null : row.getLastSuccessAt();
    }

    public Window window() {
        BkSyncCursorEntity row = cursors.find(TYPE);
        return row == null ? new Window("", "")
                : new Window(value(row.getCursorValue()), value(row.getHighWatermark()));
    }

    @Transactional
    public void begin() {
        BkSyncCursorEntity row = required();
        row.setSyncStatus("RUNNING");
        row.setLastAttemptAt(LocalDateTime.now());
        row.setLastErrorMessage("");
        cursors.updateById(row);
    }

    @Transactional
    public void page(String cursor, String highWatermark, int count) {
        BkSyncCursorEntity row = required();
        row.setCursorValue(value(cursor));
        row.setHighWatermark(value(highWatermark));
        row.setLastItemCount(number(row.getLastItemCount()) + count);
        cursors.updateById(row);
    }

    @Transactional
    public void complete(int count, String completedHighWatermark) {
        BkSyncCursorEntity row = required();
        // 成熟集合每轮从起点重新求值，成功凭证负责排除已备份订单；游标只用于单轮稳定分页。
        row.setCursorValue(value(completedHighWatermark));
        row.setHighWatermark("");
        row.setSyncStatus("SUCCEEDED");
        row.setLastSuccessAt(LocalDateTime.now());
        row.setLastItemCount(count);
        row.setLastErrorMessage("");
        cursors.updateById(row);
    }

    @Transactional
    public void fail(Throwable failure) {
        BkSyncCursorEntity row = required();
        row.setSyncStatus("FAILED");
        String message = failure == null || failure.getMessage() == null ? "自动备份扫描失败" : failure.getMessage();
        row.setLastErrorMessage(message.substring(0, Math.min(message.length(), 1024)));
        cursors.updateById(row);
    }

    private BkSyncCursorEntity required() {
        BkSyncCursorEntity row = cursors.findForUpdate(TYPE);
        if (row != null) return row;
        row = new BkSyncCursorEntity();
        row.setCursorType(TYPE);
        row.setCursorValue("");
        row.setHighWatermark("");
        row.setLookbackSeconds(0);
        row.setSyncStatus("IDLE");
        row.setLastItemCount(0);
        cursors.insert(row);
        return row;
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static int number(Integer value) { return value == null ? 0 : value; }
    public record Window(String cursor, String highWatermark) { }
}
