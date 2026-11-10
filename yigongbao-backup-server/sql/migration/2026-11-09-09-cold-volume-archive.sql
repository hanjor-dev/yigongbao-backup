-- 整盘冷备封存：卷操作状态、封存 attempt/manifest 与持久化读取租约。
-- 旧复制式冷备 handler 已停用；升级前必须先人工终结遗留任务，禁止把非终态任务永久搁置。
DELIMITER $$
DROP PROCEDURE IF EXISTS assert_no_legacy_cold_jobs$$
CREATE PROCEDURE assert_no_legacy_cold_jobs()
BEGIN
    IF EXISTS (
        SELECT 1 FROM bk_job
        WHERE job_type IN ('COLD_WRITE','HOT_CLEANUP','COLD_IMPORT')
          AND status NOT IN ('SUCCEEDED','FAILED','PARTIAL_FAILED','CANCELLED')
          AND is_deleted=0
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='存在未终结的旧冷备任务，请先取消或失败收尾后再执行冷备生命周期迁移';
    END IF;
END$$
DELIMITER ;
CALL assert_no_legacy_cold_jobs();
DROP PROCEDURE assert_no_legacy_cold_jobs;

ALTER TABLE bk_storage_volume
    ADD COLUMN admin_status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/RETIRED' AFTER write_status,
    ADD COLUMN operation_status VARCHAR(32) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/SEALING/ARCHIVE_GUARD/GOING_OFFLINE/MOUNTING/REPAIR_REQUIRED' AFTER admin_status,
    ADD COLUMN operation_ref_id BIGINT NULL COMMENT '当前卷操作引用ID' AFTER operation_status,
    ADD COLUMN archive_note VARCHAR(512) NULL COMMENT '冷备介质查找备注' AFTER operation_ref_id,
    ADD COLUMN offline_label_note VARCHAR(512) NULL COMMENT '线下标签备注' AFTER archive_note,
    ADD COLUMN physical_location VARCHAR(512) NULL COMMENT '离线存放位置' AFTER offline_label_note,
    ADD COLUMN archived_at DATETIME(3) NULL COMMENT '最近成功封存时间' AFTER physical_location,
    ADD COLUMN order_created_from DATETIME(3) NULL COMMENT '封存订单创建时间起点' AFTER archived_at,
    ADD COLUMN order_created_to DATETIME(3) NULL COMMENT '封存订单创建时间终点' AFTER order_created_from,
    ADD COLUMN archived_order_count BIGINT NULL COMMENT '封存订单数' AFTER order_created_to,
    ADD COLUMN archived_file_count BIGINT NULL COMMENT '封存文件数' AFTER archived_order_count,
    ADD COLUMN archived_bytes BIGINT NULL COMMENT '封存总字节数' AFTER archived_file_count,
    ADD COLUMN last_verified_at DATETIME(3) NULL COMMENT '最近全量校验完成时间' AFTER archived_bytes;

CREATE TABLE IF NOT EXISTS bk_volume_archive (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NULL COMMENT 'VOLUME_ARCHIVE任务ID', source_volume_id BIGINT NOT NULL COMMENT '待封存卷ID', successor_volume_id BIGINT NOT NULL COMMENT '保障运行的HOT/ACTIVE卷ID', attempt_no INT NOT NULL COMMENT '同一源卷尝试序号', idempotency_key VARCHAR(128) NOT NULL COMMENT '请求幂等键', phase VARCHAR(32) NOT NULL COMMENT '封存阶段', archive_note VARCHAR(512) NOT NULL COMMENT '介质查找备注', display_name_snapshot VARCHAR(128) NULL COMMENT '卷名称快照', offline_label_note VARCHAR(512) NULL COMMENT '线下标签备注', physical_location VARCHAR(512) NULL COMMENT '计划存放位置', remark VARCHAR(1024) NULL COMMENT '其他说明', order_count BIGINT NOT NULL DEFAULT 0 COMMENT '订单数', order_package_count BIGINT NOT NULL DEFAULT 0 COMMENT '订单包数', managed_file_count BIGINT NOT NULL DEFAULT 0 COMMENT '受管文件数', total_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '总字节数', order_created_from DATETIME(3) NULL COMMENT '订单创建时间起点', order_created_to DATETIME(3) NULL COMMENT '订单创建时间终点', period_label VARCHAR(32) NULL COMMENT 'yyyyMMdd-yyyyMMdd', content_manifest_sha256 CHAR(64) NULL COMMENT '冻结清单SHA-256', manifest_version BIGINT NOT NULL DEFAULT 0 COMMENT '清单版本', checkpoint_item_id BIGINT NULL COMMENT '最近完成条目ID', verification_started_at DATETIME(3) NULL, verification_completed_at DATETIME(3) NULL, archived_at DATETIME(3) NULL, requested_by BIGINT NOT NULL, failure_code VARCHAR(64) NULL, failure_message TEXT NULL, interrupted_reason VARCHAR(1024) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整盘冷备封存尝试';

CREATE TABLE IF NOT EXISTS bk_volume_archive_item (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', archive_id BIGINT NOT NULL COMMENT '封存尝试ID', storage_volume_id BIGINT NOT NULL COMMENT '源卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对路径', artifact_type VARCHAR(32) NOT NULL COMMENT 'ORDER_PACKAGE/SUPPLEMENT', artifact_id BIGINT NOT NULL COMMENT '制品ID', snapshot_id BIGINT NULL COMMENT '订单快照ID', source_order_id BIGINT NULL COMMENT '线上订单ID', order_created_at DATETIME(3) NULL COMMENT '订单业务创建时间', expected_size BIGINT NOT NULL COMMENT '预期字节数', expected_sha256 CHAR(64) NOT NULL COMMENT '预期SHA-256', expected_manifest_sha256 CHAR(64) NULL COMMENT '订单清单SHA-256', verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/VERIFYING/VERIFIED/FAILED', actual_size BIGINT NULL, actual_sha256 CHAR(64) NULL, verified_at DATETIME(3) NULL, failure_code VARCHAR(64) NULL, failure_message TEXT NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卷封存冻结清单条目';

CREATE TABLE IF NOT EXISTS bk_storage_volume_read_lease (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', storage_volume_id BIGINT NOT NULL COMMENT '读取卷ID', artifact_type VARCHAR(32) NOT NULL, artifact_id BIGINT NOT NULL, purpose VARCHAR(32) NOT NULL COMMENT 'DOWNLOAD/RESTORE/VERIFY', owner VARCHAR(128) NOT NULL COMMENT '租约持有者', fencing_token BIGINT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL COMMENT 'ACTIVE/RELEASED/EXPIRED', heartbeat_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL, released_at DATETIME(3) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储卷读取租约';

CREATE UNIQUE INDEX uk_bk_volume_archive_attempt ON bk_volume_archive(source_volume_id, attempt_no);
CREATE UNIQUE INDEX uk_bk_volume_archive_idempotency ON bk_volume_archive(idempotency_key);
CREATE UNIQUE INDEX uk_bk_volume_archive_item_path ON bk_volume_archive_item(archive_id, storage_volume_id, storage_key(191));
CREATE INDEX idx_bk_volume_archive_phase ON bk_volume_archive(phase, update_time);
CREATE INDEX idx_bk_volume_archive_item_progress ON bk_volume_archive_item(archive_id, verification_status, id);
CREATE INDEX idx_bk_volume_read_lease_active ON bk_storage_volume_read_lease(storage_volume_id, status, expires_at);

INSERT INTO bk_config(config_key,config_name,category_code,category_name,scope,value_type,config_value,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'storage.read-lease.ttl-seconds','存储读取租约时长（秒）','STORAGE_MANAGEMENT','存储与容量','GLOBAL','INTEGER','600','读取卷的租约时长，必须大于两倍心跳间隔。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS(SELECT 1 FROM bk_config WHERE config_key='storage.read-lease.ttl-seconds' AND is_deleted=0);
INSERT INTO bk_config(config_key,config_name,category_code,category_name,scope,value_type,config_value,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'storage.read-lease.heartbeat-seconds','存储读取租约心跳（秒）','STORAGE_MANAGEMENT','存储与容量','GLOBAL','INTEGER','30','长下载读取租约的续租间隔。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS(SELECT 1 FROM bk_config WHERE config_key='storage.read-lease.heartbeat-seconds' AND is_deleted=0);
