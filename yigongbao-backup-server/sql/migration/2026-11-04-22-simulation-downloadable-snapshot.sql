-- 模拟备份写入可查询、可下载但禁止恢复的订单快照和订单包。
DROP PROCEDURE IF EXISTS add_simulation_mode_column;
DELIMITER $$
CREATE PROCEDURE add_simulation_mode_column(IN p_table VARCHAR(64))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = 'backup_mode'
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table,
      '` ADD COLUMN backup_mode VARCHAR(32) NOT NULL DEFAULT ''REAL'' COMMENT ''REAL/METADATA_SIMULATION'' AFTER id');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_simulation_mode_column('bk_order_snapshot');
CALL add_simulation_mode_column('bk_order_package');
DROP PROCEDURE IF EXISTS add_simulation_mode_column;
