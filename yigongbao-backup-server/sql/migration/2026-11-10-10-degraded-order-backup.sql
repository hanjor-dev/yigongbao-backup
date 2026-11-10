-- 降级订单备份：快照完整性、关系状态摘要与结构化缺口证据。
-- 可重复执行；历史 REAL/METADATA_SIMULATION 快照分别回填 COMPLETE/NOT_APPLICABLE。

DROP PROCEDURE IF EXISTS add_degraded_snapshot_column;
DELIMITER $$
CREATE PROCEDURE add_degraded_snapshot_column(IN p_name VARCHAR(64), IN p_definition VARCHAR(1024))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'bk_order_snapshot' AND column_name = p_name
  ) THEN
    SET @degraded_snapshot_ddl = CONCAT('ALTER TABLE bk_order_snapshot ADD COLUMN ', p_definition);
    PREPARE degraded_snapshot_stmt FROM @degraded_snapshot_ddl;
    EXECUTE degraded_snapshot_stmt;
    DEALLOCATE PREPARE degraded_snapshot_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_degraded_snapshot_column('backup_completeness',
  'backup_completeness VARCHAR(32) NULL COMMENT ''COMPLETE/INCOMPLETE/NOT_APPLICABLE'' AFTER backup_mode');
CALL add_degraded_snapshot_column('manifest_schema_version',
  'manifest_schema_version INT NULL COMMENT ''清单协议版本'' AFTER manifest_sha256');
CALL add_degraded_snapshot_column('relation_gap_count',
  'relation_gap_count INT NULL COMMENT ''关系缺口数量'' AFTER manifest_schema_version');
CALL add_degraded_snapshot_column('relation_gap_hash',
  'relation_gap_hash CHAR(64) NULL COMMENT ''关系缺口摘要'' AFTER relation_gap_count');
CALL add_degraded_snapshot_column('relationship_state_hash',
  'relationship_state_hash CHAR(64) NULL COMMENT ''资产与缺口联合关系状态摘要'' AFTER relation_gap_hash');
DROP PROCEDURE IF EXISTS add_degraded_snapshot_column;

UPDATE bk_order_snapshot
SET backup_completeness = CASE
      WHEN backup_mode = 'METADATA_SIMULATION' THEN 'NOT_APPLICABLE'
      ELSE 'COMPLETE'
    END,
    manifest_schema_version = COALESCE(manifest_schema_version, 1),
    relation_gap_count = COALESCE(relation_gap_count, 0)
WHERE backup_completeness IS NULL OR manifest_schema_version IS NULL OR relation_gap_count IS NULL;

ALTER TABLE bk_order_snapshot
  MODIFY COLUMN backup_completeness VARCHAR(32) NOT NULL DEFAULT 'COMPLETE' COMMENT 'COMPLETE/INCOMPLETE/NOT_APPLICABLE',
  MODIFY COLUMN manifest_schema_version INT NOT NULL DEFAULT 1 COMMENT '清单协议版本',
  MODIFY COLUMN relation_gap_count INT NOT NULL DEFAULT 0 COMMENT '关系缺口数量';

CREATE TABLE IF NOT EXISTS bk_relation_gap (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', snapshot_id BIGINT NOT NULL COMMENT '订单快照ID', relation_kind VARCHAR(64) NOT NULL COMMENT '关系类型', source_record_id VARCHAR(64) NOT NULL COMMENT '来源记录ID', relation_owner_key CHAR(64) NOT NULL COMMENT '关系拥有者摘要', relation_value VARCHAR(1024) NULL COMMENT '原始关系值', original_file_id VARCHAR(64) NULL COMMENT '原始文件ID', reason_code VARCHAR(64) NOT NULL COMMENT '稳定缺口原因码', reason VARCHAR(1024) NOT NULL COMMENT '缺口原因', relation_hash CHAR(64) NOT NULL COMMENT '关系摘要', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 UNIQUE KEY uk_bk_relation_gap_owner (snapshot_id, relation_owner_key),
 KEY idx_bk_relation_gap_reason (reason_code), KEY idx_bk_relation_gap_hash (relation_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='降级备份关系缺口证据';

CREATE TABLE IF NOT EXISTS bk_backup_stage (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '本地任务ID', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', idempotency_key VARCHAR(255) NOT NULL COMMENT '线上回执幂等键', manifest_json JSON NOT NULL COMMENT '待发布清单', result_json JSON NOT NULL COMMENT '待发布订单包结果', manifest_sha256 CHAR(64) NOT NULL COMMENT '清单摘要', package_sha256 CHAR(64) NOT NULL COMMENT '包摘要', package_size BIGINT NOT NULL COMMENT '包大小', storage_volume_id BIGINT NULL COMMENT '存储卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对键', backup_completeness VARCHAR(32) NOT NULL COMMENT 'COMPLETE/INCOMPLETE', stage_status VARCHAR(32) NOT NULL COMMENT 'STAGED/RECEIPT_ACCEPTED/AVAILABLE/SUPERSEDED', published_snapshot_id BIGINT NULL COMMENT '已发布快照ID', receipt_completed_at DATETIME(3) NOT NULL COMMENT '首次回执请求的稳定完成时间', receipt_accepted_at DATETIME(3) NULL, published_at DATETIME(3) NULL, superseded_at DATETIME(3) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 UNIQUE KEY uk_bk_backup_stage_idempotency (idempotency_key),
 UNIQUE KEY uk_bk_backup_stage_job_order (job_id, source_order_id),
 KEY idx_bk_backup_stage_order_status (source_order_id, stage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单备份回执前暂存状态';

DROP PROCEDURE IF EXISTS add_backup_stage_receipt_time;
DELIMITER $$
CREATE PROCEDURE add_backup_stage_receipt_time()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'bk_backup_stage'
      AND column_name = 'receipt_completed_at'
  ) THEN
    ALTER TABLE bk_backup_stage
      ADD COLUMN receipt_completed_at DATETIME(3) NULL COMMENT '首次回执请求的稳定完成时间'
      AFTER published_snapshot_id;
  END IF;
END$$
DELIMITER ;
CALL add_backup_stage_receipt_time();
DROP PROCEDURE IF EXISTS add_backup_stage_receipt_time;
UPDATE bk_backup_stage
SET receipt_completed_at = COALESCE(receipt_completed_at, create_time, CURRENT_TIMESTAMP(3))
WHERE receipt_completed_at IS NULL;
ALTER TABLE bk_backup_stage
  MODIFY COLUMN receipt_completed_at DATETIME(3) NOT NULL COMMENT '首次回执请求的稳定完成时间';

DROP PROCEDURE IF EXISTS add_degraded_restore_column;
DELIMITER $$
CREATE PROCEDURE add_degraded_restore_column(IN p_name VARCHAR(64), IN p_definition VARCHAR(1024))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'bk_restore_session' AND column_name = p_name
  ) THEN
    SET @degraded_restore_ddl = CONCAT('ALTER TABLE bk_restore_session ADD COLUMN ', p_definition);
    PREPARE degraded_restore_stmt FROM @degraded_restore_ddl;
    EXECUTE degraded_restore_stmt;
    DEALLOCATE PREPARE degraded_restore_stmt;
  END IF;
END$$
DELIMITER ;
CALL add_degraded_restore_column('manifest_schema_version', 'manifest_schema_version INT NOT NULL DEFAULT 1 COMMENT ''备份清单协议版本'' AFTER relation_set_hash');
CALL add_degraded_restore_column('backup_completeness', 'backup_completeness VARCHAR(32) NOT NULL DEFAULT ''COMPLETE'' COMMENT ''COMPLETE/INCOMPLETE'' AFTER manifest_schema_version');
CALL add_degraded_restore_column('relationship_state_hash', 'relationship_state_hash CHAR(64) NULL COMMENT ''v2关系状态摘要'' AFTER backup_completeness');
DROP PROCEDURE IF EXISTS add_degraded_restore_column;
