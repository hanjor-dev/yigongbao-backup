package com.yigongbao.backup.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 将账号页合并和游标推进放入同一事务，保证失败后能够从最后成功页续传。 */
@Service
@RequiredArgsConstructor
public class AccountSyncCheckpointService {
    private static final String TYPE = "USER";
    private static final int MAX_ERROR_LENGTH = 1024;
    private final BkSyncCursorMapper cursors;
    private final PersistentAccountSyncService accounts;

    /** 返回最后成功提交页的游标；首次同步返回空值。 */
    public String currentCursor() {
        BkSyncCursorEntity row = cursors.find(TYPE);
        return row == null || row.getCursorValue() == null || row.getCursorValue().isBlank()
                ? null : row.getCursorValue();
    }

    /** 标记一次同步开始，并保留既有游标。 */
    @Transactional
    public RunState begin() {
        BkSyncCursorEntity row = requiredForUpdate();
        boolean resume = ("FAILED".equals(row.getSyncStatus()) || "RUNNING".equals(row.getSyncStatus()))
                && !blank(row.getHighWatermark());
        String startCursor = resume ? emptyToNull(row.getCursorValue()) : lookback(row.getCursorValue(), row.getLookbackSeconds());
        if (!resume) row.setHighWatermark("");
        row.setSyncStatus("RUNNING");
        row.setLastAttemptAt(LocalDateTime.now());
        row.setLastItemCount(0);
        // MyBatis-Plus 默认忽略 null 更新，使用空串显式清除上一轮失败信息。
        row.setLastErrorMessage("");
        cursors.updateById(row);
        return new RunState(startCursor, resume ? emptyToNull(row.getHighWatermark()) : null);
    }

    /** 原子合并一页账号并推进游标；游标为空表示本页没有数据。 */
    @Transactional
    public void commitPage(List<RemoteAccount> page, String nextCursor) {
        commitPage(page, nextCursor, null);
    }

    @Transactional
    public void commitPage(List<RemoteAccount> page, String nextCursor, String highWatermark) {
        if (page == null) throw new IllegalArgumentException("账号同步页为空");
        BkSyncCursorEntity row = requiredForUpdate();
        page.forEach(accounts::merge);
        if (nextCursor != null && !nextCursor.isBlank()) row.setCursorValue(nextCursor);
        if (!blank(highWatermark)) row.setHighWatermark(highWatermark);
        row.setLastItemCount(value(row.getLastItemCount()) + page.size());
        row.setSyncStatus("RUNNING");
        cursors.updateById(row);
    }

    /** 将本轮同步标记成功。 */
    @Transactional
    public void complete(int count) {
        BkSyncCursorEntity row = requiredForUpdate();
        row.setSyncStatus("SUCCEEDED");
        row.setLastSuccessAt(LocalDateTime.now());
        row.setLastItemCount(count);
        // MyBatis-Plus 默认忽略 null 更新，使用空串显式清除上一轮失败信息。
        row.setLastErrorMessage("");
        row.setHighWatermark("");
        cursors.updateById(row);
    }

    /** 记录失败原因但不改变已提交游标。 */
    @Transactional
    public void fail(String message) {
        BkSyncCursorEntity row = requiredForUpdate();
        row.setSyncStatus("FAILED");
        String safe = message == null || message.isBlank() ? "未知同步异常" : message;
        row.setLastErrorMessage(safe.substring(0, Math.min(safe.length(), MAX_ERROR_LENGTH)));
        cursors.updateById(row);
    }

    /** 查询供管理页面展示的非敏感同步状态。 */
    public Status status() {
        BkSyncCursorEntity row = cursors.find(TYPE);
        if (row == null) return new Status("IDLE", null, null, 0, null, null);
        return new Status(row.getSyncStatus(), row.getLastAttemptAt(), row.getLastSuccessAt(),
                value(row.getLastItemCount()), emptyToNull(row.getLastErrorMessage()), emptyToNull(row.getCursorValue()));
    }

    private BkSyncCursorEntity requiredForUpdate() {
        BkSyncCursorEntity row = cursors.findForUpdate(TYPE);
        if (row != null) return row;
        row = new BkSyncCursorEntity();
        row.setCursorType(TYPE);
        row.setCursorValue("");
        row.setLookbackSeconds(60);
        row.setSyncStatus("IDLE");
        row.setLastItemCount(0);
        cursors.insert(row);
        return row;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static String lookback(String cursor, Integer configuredSeconds) {
        if (blank(cursor)) return null;
        String[] parts = cursor.split("\\|", 2);
        if (parts.length != 2) throw new IllegalStateException("持久化账号同步游标非法");
        int seconds = configuredSeconds == null || configuredSeconds <= 0 ? 60 : configuredSeconds;
        return OffsetDateTime.parse(parts[0]).minusSeconds(seconds) + "|0";
    }

    public record RunState(String cursor, String highWatermark) { }

    public record Status(String status, LocalDateTime lastAttemptAt, LocalDateTime lastSuccessAt,
                         int lastItemCount, String lastErrorMessage, String cursor) { }
}
