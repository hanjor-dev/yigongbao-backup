-- 增加订单备份元数据模拟任务的预计统计与隔离制品路径。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET @schema_name = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='bk_job_order' AND COLUMN_NAME='expected_file_count') = 0,
              'ALTER TABLE bk_job_order ADD COLUMN expected_file_count INT NULL COMMENT ''模拟预计文件数'' AFTER order_business_json',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='bk_job_order' AND COLUMN_NAME='expected_bytes') = 0,
              'ALTER TABLE bk_job_order ADD COLUMN expected_bytes BIGINT NULL COMMENT ''模拟预计文件容量'' AFTER expected_file_count',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='bk_job_order' AND COLUMN_NAME='simulation_path') = 0,
              'ALTER TABLE bk_job_order ADD COLUMN simulation_path VARCHAR(1024) NULL COMMENT ''模拟制品隔离目录'' AFTER expected_bytes',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
