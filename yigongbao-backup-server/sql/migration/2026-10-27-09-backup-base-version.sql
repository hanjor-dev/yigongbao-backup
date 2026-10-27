-- 修复旧版本数据库缺少 BaseEntity.version 导致 MyBatis-Plus 查询失败的问题。
-- 可重复执行，已有字段时自动跳过。
DELIMITER $$
DROP PROCEDURE IF EXISTS add_backup_version_if_missing$$
CREATE PROCEDURE add_backup_version_if_missing(IN p_table VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = p_table)
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = 'version') THEN
    SET @backup_version_ddl = CONCAT('ALTER TABLE `', p_table,
                                     '` ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''MyBatis-Plus乐观锁版本'' AFTER is_deleted');
    PREPARE backup_version_stmt FROM @backup_version_ddl;
    EXECUTE backup_version_stmt;
    DEALLOCATE PREPARE backup_version_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_backup_version_if_missing('bk_user_field_control');
CALL add_backup_version_if_missing('bk_order_snapshot');
CALL add_backup_version_if_missing('bk_asset');
CALL add_backup_version_if_missing('bk_asset_relation');
CALL add_backup_version_if_missing('bk_supplement_file');
CALL add_backup_version_if_missing('bk_cold_batch_artifact');
CALL add_backup_version_if_missing('bk_cold_volume');
CALL add_backup_version_if_missing('bk_job_order');
CALL add_backup_version_if_missing('bk_job_file');
CALL add_backup_version_if_missing('bk_restore_id_mapping');
CALL add_backup_version_if_missing('bk_storage_probe_history');
DROP PROCEDURE IF EXISTS add_backup_version_if_missing;
