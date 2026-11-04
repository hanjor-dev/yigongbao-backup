-- 账号增量同步生产化：增加运行状态、结果和错误信息字段。
-- 可重复执行，不修改既有游标和成功时间。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_account_sync_column_if_missing$$
CREATE PROCEDURE add_account_sync_column_if_missing(IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='bk_sync_cursor' AND column_name=p_column
  ) THEN
    SET @account_sync_ddl=CONCAT('ALTER TABLE bk_sync_cursor ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE account_sync_stmt FROM @account_sync_ddl;
    EXECUTE account_sync_stmt;
    DEALLOCATE PREPARE account_sync_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_account_sync_column_if_missing('last_attempt_at', 'DATETIME(3) NULL COMMENT ''最近尝试时间'' AFTER last_success_at');
CALL add_account_sync_column_if_missing('sync_status', 'VARCHAR(16) NOT NULL DEFAULT ''IDLE'' COMMENT ''IDLE/RUNNING/SUCCEEDED/FAILED'' AFTER last_attempt_at');
CALL add_account_sync_column_if_missing('last_item_count', 'INT NOT NULL DEFAULT 0 COMMENT ''最近一轮已提交数量'' AFTER sync_status');
CALL add_account_sync_column_if_missing('last_error_message', 'VARCHAR(1024) NULL COMMENT ''最近失败原因'' AFTER last_item_count');

DROP PROCEDURE IF EXISTS add_account_sync_column_if_missing;

UPDATE bk_sync_cursor
SET sync_status='IDLE', last_item_count=0
WHERE cursor_type='USER' AND is_deleted=0 AND (sync_status IS NULL OR sync_status='');
