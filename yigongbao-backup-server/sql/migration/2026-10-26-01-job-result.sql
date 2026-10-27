-- 为已存在的本地备份库增加可串联后续任务的结果字段。
SET @column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'bk_job' AND column_name = 'result_json'
);
SET @ddl = IF(@column_exists = 0,
  'ALTER TABLE bk_job ADD COLUMN result_json JSON NULL COMMENT ''任务结果'' AFTER config_snapshot_json',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
