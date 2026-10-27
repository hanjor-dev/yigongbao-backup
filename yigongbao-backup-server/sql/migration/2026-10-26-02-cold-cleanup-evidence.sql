SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_cold_batch_artifact' AND column_name='failure_message');
SET @ddl = IF(@column_exists=0,'ALTER TABLE bk_cold_batch_artifact ADD COLUMN failure_message VARCHAR(1024) NULL COMMENT ''失败证据'' AFTER write_status','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
