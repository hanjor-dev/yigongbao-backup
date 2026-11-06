-- 动态存储卷管理迁移。
-- 本脚本只增加结构和兼容台账，不删除历史路径、索引或业务数据。
-- 历史路径无法可靠归属时写入迁移异常报告：不可靠，不猜测。

CREATE TABLE IF NOT EXISTS bk_storage_volume (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', volume_code VARCHAR(64) NOT NULL COMMENT '逻辑卷编码', display_name VARCHAR(128) NOT NULL COMMENT '显示名称', access_type VARCHAR(32) NOT NULL COMMENT 'LOCAL/REMOVABLE/MAPPED_NETWORK', mount_path VARCHAR(1024) NOT NULL COMMENT 'Windows挂载点', root_path VARCHAR(1024) NOT NULL COMMENT '受控业务根目录', volume_fingerprint VARCHAR(255) NULL COMMENT '系统卷指纹', marker_json JSON NULL COMMENT '受控目录卷标记', capacity_pool_id BIGINT NULL COMMENT '容量域ID', tier VARCHAR(32) NOT NULL COMMENT 'HOT/COLD/IMPORT', mount_status VARCHAR(32) NOT NULL COMMENT 'ONLINE/OFFLINE/UNAVAILABLE', write_status VARCHAR(32) NOT NULL COMMENT 'ACTIVE/STANDBY/READ_ONLY/DISABLED', priority INT NOT NULL DEFAULT 0 COMMENT '切换优先级', last_probed_at DATETIME(3) NULL COMMENT '最近探测时间', last_error_code VARCHAR(64) NULL COMMENT '最近错误码', last_error_message TEXT NULL COMMENT '最近错误信息', hot_active_singleton TINYINT GENERATED ALWAYS AS (CASE WHEN tier='HOT' AND write_status='ACTIVE' AND is_deleted=0 THEN 1 ELSE NULL END) STORED COMMENT 'HOT活动卷唯一约束键', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态存储卷台账';

CREATE TABLE IF NOT EXISTS bk_storage_volume_migration_issue (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', issue_type VARCHAR(64) NOT NULL COMMENT '异常类型', artifact_type VARCHAR(32) NULL COMMENT '制品类型', artifact_id BIGINT NULL COMMENT '制品ID', legacy_storage_key VARCHAR(1024) NULL COMMENT '历史存储键', issue_message TEXT NOT NULL COMMENT '异常说明', resolution_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/IGNORED', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储卷迁移异常报告';

DELIMITER $$
DROP PROCEDURE IF EXISTS add_storage_volume_column_if_missing$$
CREATE PROCEDURE add_storage_volume_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column) THEN
    SET @storage_volume_ddl=p_ddl;
    PREPARE storage_volume_stmt FROM @storage_volume_ddl;
    EXECUTE storage_volume_stmt;
    DEALLOCATE PREPARE storage_volume_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_storage_volume_column_if_missing('bk_order_package','storage_volume_id','ALTER TABLE bk_order_package ADD COLUMN storage_volume_id BIGINT NULL COMMENT ''存储卷ID'' AFTER snapshot_id');
CALL add_storage_volume_column_if_missing('bk_supplement_file','storage_volume_id','ALTER TABLE bk_supplement_file ADD COLUMN storage_volume_id BIGINT NULL COMMENT ''存储卷ID'' AFTER snapshot_id');
CALL add_storage_volume_column_if_missing('bk_artifact_copy','storage_volume_id','ALTER TABLE bk_artifact_copy ADD COLUMN storage_volume_id BIGINT NULL COMMENT ''存储卷ID'' AFTER location_ref_id');
CALL add_storage_volume_column_if_missing('bk_cold_batch_artifact','storage_volume_id','ALTER TABLE bk_cold_batch_artifact ADD COLUMN storage_volume_id BIGINT NULL COMMENT ''实际所在存储卷ID'' AFTER batch_id');
CALL add_storage_volume_column_if_missing('bk_job','target_storage_volume_id','ALTER TABLE bk_job ADD COLUMN target_storage_volume_id BIGINT NULL COMMENT ''备份目标存储卷ID'' AFTER config_snapshot_json');
CALL add_storage_volume_column_if_missing('bk_job_order','source_package_id','ALTER TABLE bk_job_order ADD COLUMN source_package_id BIGINT NULL COMMENT ''恢复源订单包ID'' AFTER snapshot_id');
CALL add_storage_volume_column_if_missing('bk_job_order','source_storage_volume_id','ALTER TABLE bk_job_order ADD COLUMN source_storage_volume_id BIGINT NULL COMMENT ''恢复源存储卷ID'' AFTER source_package_id');
CALL add_storage_volume_column_if_missing('bk_job_order','source_storage_key','ALTER TABLE bk_job_order ADD COLUMN source_storage_key VARCHAR(1024) NULL COMMENT ''恢复源存储键快照'' AFTER source_storage_volume_id');
CALL add_storage_volume_column_if_missing('bk_job_order','source_package_sha256','ALTER TABLE bk_job_order ADD COLUMN source_package_sha256 CHAR(64) NULL COMMENT ''恢复源包哈希快照'' AFTER source_storage_key');
DROP PROCEDURE IF EXISTS add_storage_volume_column_if_missing;

DELIMITER $$
DROP PROCEDURE IF EXISTS replace_storage_volume_index_if_present$$
CREATE PROCEDURE replace_storage_volume_index_if_present(IN p_table VARCHAR(64), IN p_old_index VARCHAR(64), IN p_new_index VARCHAR(64), IN p_new_ddl TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_old_index) THEN
    SET @storage_volume_drop_ddl=CONCAT('DROP INDEX ', p_old_index, ' ON ', p_table);
    PREPARE storage_volume_drop_stmt FROM @storage_volume_drop_ddl;
    EXECUTE storage_volume_drop_stmt;
    DEALLOCATE PREPARE storage_volume_drop_stmt;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_new_index) THEN
    SET @storage_volume_create_ddl=p_new_ddl;
    PREPARE storage_volume_create_stmt FROM @storage_volume_create_ddl;
    EXECUTE storage_volume_create_stmt;
    DEALLOCATE PREPARE storage_volume_create_stmt;
  END IF;
END$$
DELIMITER ;
CALL replace_storage_volume_index_if_present('bk_order_package','uk_bk_package_storage','uk_bk_package_volume_key','CREATE UNIQUE INDEX uk_bk_package_volume_key ON bk_order_package(storage_volume_id,storage_key(191))');
CALL replace_storage_volume_index_if_present('bk_supplement_file','uk_bk_supplement_storage','uk_bk_supplement_volume_key','CREATE UNIQUE INDEX uk_bk_supplement_volume_key ON bk_supplement_file(storage_volume_id,storage_key(191))');
CALL replace_storage_volume_index_if_present('bk_artifact_copy','uk_bk_artifact_copy_volume_key','uk_bk_artifact_copy_volume_key','CREATE UNIQUE INDEX uk_bk_artifact_copy_volume_key ON bk_artifact_copy(storage_volume_id,storage_key(191))');
DROP PROCEDURE IF EXISTS replace_storage_volume_index_if_present;

DELIMITER $$
DROP PROCEDURE IF EXISTS add_storage_volume_index_if_missing$$
CREATE PROCEDURE add_storage_volume_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_index) THEN
    SET @storage_volume_index_ddl=p_ddl;
    PREPARE storage_volume_index_stmt FROM @storage_volume_index_ddl;
    EXECUTE storage_volume_index_stmt;
    DEALLOCATE PREPARE storage_volume_index_stmt;
  END IF;
END$$
DELIMITER ;
CALL add_storage_volume_index_if_missing('bk_storage_volume','uk_bk_storage_volume_code','CREATE UNIQUE INDEX uk_bk_storage_volume_code ON bk_storage_volume(volume_code)');
CALL add_storage_volume_index_if_missing('bk_storage_volume','uk_bk_storage_hot_active','CREATE UNIQUE INDEX uk_bk_storage_hot_active ON bk_storage_volume(hot_active_singleton)');
CALL add_storage_volume_index_if_missing('bk_storage_volume','idx_bk_storage_volume_status','CREATE INDEX idx_bk_storage_volume_status ON bk_storage_volume(tier,mount_status,write_status,priority)');
CALL add_storage_volume_index_if_missing('bk_storage_volume','idx_bk_storage_volume_fingerprint','CREATE INDEX idx_bk_storage_volume_fingerprint ON bk_storage_volume(volume_fingerprint)');
CALL add_storage_volume_index_if_missing('bk_storage_volume_migration_issue','idx_bk_storage_volume_migration_issue','CREATE INDEX idx_bk_storage_volume_migration_issue ON bk_storage_volume_migration_issue(resolution_status,issue_type)');
CALL add_storage_volume_index_if_missing('bk_job','idx_bk_job_target_volume','CREATE INDEX idx_bk_job_target_volume ON bk_job(target_storage_volume_id,status)');
CALL add_storage_volume_index_if_missing('bk_job_order','idx_bk_job_order_source_volume','CREATE INDEX idx_bk_job_order_source_volume ON bk_job_order(source_storage_volume_id,status)');
DROP PROCEDURE IF EXISTS add_storage_volume_index_if_missing;

-- 使用既有热备配置生成 legacy-hot 卷；路径暂只作为迁移兼容依据，后续新写入不再读取该配置。
INSERT INTO bk_storage_volume(volume_code,display_name,access_type,mount_path,root_path,tier,mount_status,write_status,priority,version,is_deleted)
SELECT 'legacy-hot','历史热备卷','LOCAL',COALESCE(NULLIF(config_value,''),'./data'),COALESCE(NULLIF(config_value,''),'./data'),'HOT','OFFLINE','READ_ONLY',-100,0,0
FROM bk_config WHERE config_key='storage.hot-root' AND is_deleted=0
  AND NOT EXISTS (SELECT 1 FROM bk_storage_volume WHERE volume_code='legacy-hot' AND is_deleted=0)
LIMIT 1;

INSERT INTO bk_storage_volume(volume_code,display_name,access_type,mount_path,root_path,tier,mount_status,write_status,priority,version,is_deleted)
SELECT 'legacy-hot','历史热备卷','LOCAL','./data','./data','HOT','OFFLINE','READ_ONLY',-100,0,0
WHERE NOT EXISTS (SELECT 1 FROM bk_storage_volume WHERE volume_code='legacy-hot' AND is_deleted=0);

SET @legacy_hot_volume_id=(SELECT id FROM bk_storage_volume WHERE volume_code='legacy-hot' AND is_deleted=0 ORDER BY id DESC LIMIT 1);
UPDATE bk_order_package SET storage_volume_id=@legacy_hot_volume_id WHERE storage_volume_id IS NULL AND is_deleted=0;
UPDATE bk_supplement_file SET storage_volume_id=@legacy_hot_volume_id WHERE storage_volume_id IS NULL AND is_deleted=0;
UPDATE bk_artifact_copy SET storage_volume_id=@legacy_hot_volume_id WHERE storage_volume_id IS NULL AND location_type='HOT_NAS' AND is_deleted=0;

-- 已登记冷盘可映射为离线只读卷；物理路径是否已重新挂载由后续探测确认。
INSERT INTO bk_storage_volume(volume_code,display_name,access_type,mount_path,root_path,volume_fingerprint,marker_json,tier,mount_status,write_status,priority,version,is_deleted)
SELECT CONCAT('cold-disk-',d.id),CONCAT('历史冷备盘-',d.disk_code),'REMOVABLE',COALESCE(NULLIF(d.physical_location,''),CONCAT('OFFLINE://',d.disk_code)),COALESCE(NULLIF(d.physical_location,''),CONCAT('OFFLINE://',d.disk_code)),d.volume_serial,JSON_OBJECT('legacyMarkerUuid',d.marker_uuid),'COLD','OFFLINE','READ_ONLY',-100,0,0
FROM bk_cold_disk d
WHERE d.is_deleted=0
  AND NOT EXISTS (SELECT 1 FROM bk_storage_volume v WHERE v.volume_code=CONCAT('cold-disk-',d.id) AND v.is_deleted=0);

UPDATE bk_cold_batch_artifact a
JOIN bk_cold_batch b ON b.id=a.batch_id AND b.is_deleted=0
JOIN bk_storage_volume v ON v.volume_code=CONCAT('cold-disk-',b.disk_id) AND v.is_deleted=0
SET a.storage_volume_id=v.id
WHERE a.storage_volume_id IS NULL AND a.is_deleted=0;

UPDATE bk_artifact_copy c
JOIN bk_cold_batch b ON b.id=c.location_ref_id AND b.is_deleted=0
JOIN bk_storage_volume v ON v.volume_code=CONCAT('cold-disk-',b.disk_id) AND v.is_deleted=0
SET c.storage_volume_id=v.id
WHERE c.storage_volume_id IS NULL AND c.location_type='COLD_BATCH' AND c.is_deleted=0;

INSERT INTO bk_artifact_copy(artifact_type,artifact_id,location_type,location_ref_id,storage_volume_id,storage_key,state,lifecycle,size,sha256,version,is_deleted)
SELECT 'ORDER_PACKAGE',p.id,'HOT_NAS',@legacy_hot_volume_id,@legacy_hot_volume_id,p.storage_key,'PRESENT','ACTIVE',p.package_size,p.package_sha256,0,0
FROM bk_order_package p
WHERE p.is_deleted=0 AND p.storage_volume_id=@legacy_hot_volume_id
  AND NOT EXISTS (SELECT 1 FROM bk_artifact_copy c WHERE c.artifact_type='ORDER_PACKAGE' AND c.artifact_id=p.id AND c.storage_volume_id=@legacy_hot_volume_id AND c.is_deleted=0);

INSERT INTO bk_artifact_copy(artifact_type,artifact_id,location_type,location_ref_id,storage_volume_id,storage_key,state,lifecycle,size,sha256,version,is_deleted)
SELECT 'SUPPLEMENT',s.id,'HOT_NAS',@legacy_hot_volume_id,@legacy_hot_volume_id,s.storage_key,'PRESENT','ACTIVE',s.size,s.sha256,0,0
FROM bk_supplement_file s
WHERE s.is_deleted=0 AND s.storage_volume_id=@legacy_hot_volume_id
  AND NOT EXISTS (SELECT 1 FROM bk_artifact_copy c WHERE c.artifact_type='SUPPLEMENT' AND c.artifact_id=s.id AND c.storage_volume_id=@legacy_hot_volume_id AND c.is_deleted=0);

INSERT INTO bk_storage_volume_migration_issue(issue_type,artifact_type,artifact_id,legacy_storage_key,issue_message,version,is_deleted)
SELECT 'COLD_LOCATION_UNRESOLVED',a.artifact_type,a.artifact_id,a.relative_path,'历史冷备批次仅有批次内相对路径，无法可靠映射实际物理卷；不可靠，不猜测，需管理员在存储卷管理页确认。',0,0
FROM bk_cold_batch_artifact a WHERE a.is_deleted=0 AND a.storage_volume_id IS NULL;
