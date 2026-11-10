/*
 * 医工宝本地备份系统——全新环境一体化初始化脚本（MySQL 8）
 *
 * 用途：一次执行完成数据库创建、领域表、默认管理员、索引、版本字段兼容处理和业务配置初始化。
 * 来源：sql/init/00-create-database.sql 至 04-functional-config-defaults.sql。
 * 约束：仅用于全新环境；已有数据库升级请使用 sql/migration 下的迁移脚本。
 * 默认数据库：yigongbao_backup。
 * 默认管理员：admin1（首次登录后必须修改密码）。
 *
 * 推荐执行：mysql --default-character-set=utf8mb4 -u <迁移账号> -p < sql/init/yigongbao-backup-full-init.sql
 */

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ============================================================================
-- BEGIN SOURCE: sql/init/00-create-database.sql
-- ============================================================================
-- 本地备份库初始化（MySQL 8）。脚本可重复执行，不覆盖现有数据。
-- 执行前检查：SELECT VERSION(); SHOW CHARACTER SET LIKE 'utf8mb4';

CREATE DATABASE IF NOT EXISTS yigongbao_backup
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE yigongbao_backup;

-- 应用账号建议（由 DBA 在部署环境替换主机和强随机密码后单独执行）：
-- CREATE USER IF NOT EXISTS 'yigongbao_backup_app'@'10.%' IDENTIFIED BY '<vault-generated-password>';
-- GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON yigongbao_backup.* TO 'yigongbao_backup_app'@'10.%';
-- 禁止授予 DROP、ALTER、FILE、SUPER；迁移账号与运行账号分离。

-- 执行后验证：字符集和排序规则应分别为 utf8mb4、utf8mb4_0900_ai_ci。
SELECT schema_name,default_character_set_name,default_collation_name
FROM information_schema.schemata WHERE schema_name='yigongbao_backup';
-- 人工回滚：数据库含任何备份、审计或恢复证据时禁止 DROP DATABASE；仅撤销未启用的应用账号授权。
-- ============================================================================
-- END SOURCE: sql/init/00-create-database.sql
-- ============================================================================

-- ============================================================================
-- BEGIN SOURCE: sql/init/01-schema.sql
-- ============================================================================
-- 本地备份服务领域表（MySQL 8）。
-- 执行前检查：USE yigongbao_backup; SELECT VERSION();
-- 可重入策略：仅 CREATE TABLE IF NOT EXISTS；不覆盖、截断或删除既有证据。
-- 关联默认采用软关联，业务层限制删除，不使用级联删除。

CREATE TABLE IF NOT EXISTS bk_user (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', source_user_id BIGINT NULL COMMENT '线上用户ID；应急管理员为空',
 username VARCHAR(64) NOT NULL COMMENT '登录账号', active_username VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN enabled_status<>'ARCHIVED' AND is_deleted=0 THEN username ELSE NULL END) STORED COMMENT '活动账号唯一键',
 password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希', real_name VARCHAR(64) NULL COMMENT '姓名', phone VARCHAR(32) NULL COMMENT '电话', email VARCHAR(128) NULL COMMENT '邮箱',
 role_code VARCHAR(16) NOT NULL COMMENT 'USER/ADMIN', enabled_status VARCHAR(16) NOT NULL COMMENT 'ENABLED/DISABLED/ARCHIVED', login_fail_count INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
 lock_until DATETIME(3) NULL COMMENT '登录临时锁定截止', row_version INT NOT NULL DEFAULT 0 COMMENT '同步和本地修改CAS版本', emergency_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否应急管理员',
 source_updated_at DATETIME(3) NULL COMMENT '线上版本时间', last_synced_at DATETIME(3) NULL COMMENT '最近同步时间', version INT NOT NULL DEFAULT 0 COMMENT 'MyBatis-Plus乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地账号';

-- 首次联调管理员（仅当 admin1 账号不存在时创建，不覆盖已有账号）。
-- 初始账号：admin1；密码使用预置 BCrypt 哈希，首次登录后建议立即修改密码。
-- 密码为 BCrypt 哈希，和 PersistentAccountSyncService/AuthController 使用同一校验算法。
INSERT INTO bk_user (source_user_id, username, password_hash, real_name, role_code,
                     enabled_status, login_fail_count, emergency_admin)
SELECT NULL,
       'admin1',
       '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
       '系统管理员',
       'ADMIN',
       'ENABLED',
       0,
       1
WHERE NOT EXISTS (
    SELECT 1 FROM bk_user WHERE username = 'admin1' AND is_deleted = 0
);

CREATE TABLE IF NOT EXISTS bk_user_field_control (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', user_id BIGINT NOT NULL COMMENT '本地用户ID', field_name VARCHAR(64) NOT NULL COMMENT '托管字段', ownership VARCHAR(16) NOT NULL COMMENT 'REMOTE/LOCAL',
 local_changed_at DATETIME(3) NULL COMMENT '本地修改时间', local_changed_by BIGINT NULL COMMENT '本地修改人', last_remote_value_hash CHAR(64) NULL COMMENT '最近线上值摘要', released_at DATETIME(3) NULL COMMENT '解除本地托管时间',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号字段托管权';

CREATE TABLE IF NOT EXISTS bk_backup_candidate (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', source_event_id BIGINT NOT NULL COMMENT '线上候选事件ID', candidate_generation INT NOT NULL COMMENT '资格代次',
 backup_eligible_at DATETIME(3) NOT NULL COMMENT '首次资格时间', candidate_status VARCHAR(32) NOT NULL COMMENT '候选状态', last_attempt_at DATETIME(3) NULL COMMENT '最近尝试时间', next_retry_at DATETIME(3) NULL COMMENT '下次重试时间', failure_code VARCHAR(64) NULL COMMENT '失败码', row_version INT NOT NULL DEFAULT 0 COMMENT 'CAS版本', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地备份候选投影';

CREATE TABLE IF NOT EXISTS bk_order_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', backup_mode VARCHAR(32) NOT NULL DEFAULT 'REAL' COMMENT 'REAL/METADATA_SIMULATION', backup_completeness VARCHAR(32) NOT NULL DEFAULT 'COMPLETE' COMMENT 'COMPLETE/INCOMPLETE/NOT_APPLICABLE', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', order_code VARCHAR(64) NULL COMMENT '订单号', public_order_code VARCHAR(64) NULL COMMENT '对外展示单号', order_created_at DATETIME(3) NULL COMMENT '订单创建时间',
 order_type INT NULL COMMENT '订单类型', order_type_name VARCHAR(64) NULL COMMENT '订单类型名称', needs_physical_delivery INT NULL COMMENT '实体交付类型', needs_physical_delivery_name VARCHAR(64) NULL COMMENT '实体交付名称', business_type VARCHAR(32) NULL COMMENT '业务类型编码', business_type_name VARCHAR(128) NULL COMMENT '业务类型名称',
 org_id BIGINT NULL COMMENT '提单机构ID', org_name VARCHAR(255) NULL COMMENT '提单机构名称', operator_id BIGINT NULL COMMENT '操作员ID', operator_name VARCHAR(128) NULL COMMENT '操作员姓名', operator_phone VARCHAR(64) NULL COMMENT '操作员电话', operator_dept_id BIGINT NULL COMMENT '操作员部门ID', operator_dept_name VARCHAR(255) NULL COMMENT '操作员部门名称',
 hospital_id BIGINT NULL COMMENT '医院ID', hospital_name VARCHAR(255) NULL COMMENT '医院名称', area_id BIGINT NULL COMMENT '地区ID', area_name VARCHAR(255) NULL COMMENT '地区名称', full_area_name VARCHAR(512) NULL COMMENT '完整地区名称', hospital_dept_id BIGINT NULL COMMENT '医院科室ID', hospital_dept_name VARCHAR(255) NULL COMMENT '医院科室名称',
 doctor_id BIGINT NULL COMMENT '医生ID', doctor_name VARCHAR(128) NULL COMMENT '医生姓名', doctor_phone VARCHAR(64) NULL COMMENT '医生电话', patient_name VARCHAR(128) NULL COMMENT '患者姓名', patient_age INT NULL COMMENT '患者年龄', patient_gender VARCHAR(32) NULL COMMENT '患者性别编码', patient_gender_name VARCHAR(32) NULL COMMENT '患者性别名称',
 source_status VARCHAR(32) NOT NULL COMMENT '订单业务状态兼容字段', classic_case TINYINT NOT NULL DEFAULT 0 COMMENT '经典案例快照', is_urgent INT NULL COMMENT '是否加急', is_postal INT NULL COMMENT '是否邮寄', postal_address VARCHAR(1024) NULL COMMENT '邮寄地址',
 designer_id BIGINT NULL COMMENT '设计师ID', designer_name VARCHAR(128) NULL COMMENT '设计师姓名', expected_delivery_date DATETIME(3) NULL COMMENT '期望交付时间', estimated_cost DECIMAL(18,2) NULL COMMENT '预估费用', data_evaluation_opinion TEXT NULL COMMENT '影像数据评估意见', designer_remark TEXT NULL COMMENT '设计师备注',
 phase INT NULL COMMENT '订单阶段', phase_name VARCHAR(64) NULL COMMENT '订单阶段名称', status INT NULL COMMENT '订单状态', status_name VARCHAR(128) NULL COMMENT '订单状态名称', status_color_json JSON NULL COMMENT '状态颜色对象', regional_audit_json JSON NULL COMMENT '区域审核对象', design_audit_json JSON NULL COMMENT '设计审核对象',
 design_start_time DATETIME(3) NULL COMMENT '设计开始时间', design_submit_time DATETIME(3) NULL COMMENT '设计提交时间', production_start_time DATETIME(3) NULL COMMENT '生产开始时间', production_end_time DATETIME(3) NULL COMMENT '生产结束时间', source_version INT NULL COMMENT '线上订单版本', rebuild_project_list_json JSON NULL COMMENT '重建项目列表', rebuild_project_names TEXT NULL COMMENT '重建项目名称检索文本',
 order_raw_json JSON NOT NULL COMMENT 'order_main全列原始快照', display_json JSON NOT NULL COMMENT '列表非操作列展示快照', related_business_json JSON NOT NULL COMMENT '关联业务快照', manifest_revision VARCHAR(128) NOT NULL COMMENT '清单修订号', manifest_sha256 CHAR(64) NOT NULL COMMENT 'JCS清单哈希', manifest_schema_version INT NOT NULL DEFAULT 1 COMMENT '清单协议版本', relation_gap_count INT NOT NULL DEFAULT 0 COMMENT '关系缺口数量', relation_gap_hash CHAR(64) NULL COMMENT '关系缺口摘要', relationship_state_hash CHAR(64) NULL COMMENT '资产与缺口联合状态摘要', snapshot_status VARCHAR(32) NOT NULL COMMENT 'PENDING_RECEIPT/AVAILABLE/RECEIPT_SUSPENDED/CORRUPT', backup_completed_at DATETIME(3) NOT NULL COMMENT '本地订单包写入时间', latest_verified_at DATETIME(3) NULL COMMENT '最近校验时间', cloud_rollup VARCHAR(32) NOT NULL COMMENT '云端聚合状态', hot_rollup VARCHAR(32) NOT NULL COMMENT '热备聚合状态', cold_rollup VARCHAR(32) NOT NULL COMMENT '冷备聚合状态',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变订单快照';

CREATE TABLE IF NOT EXISTS bk_asset (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', snapshot_id BIGINT NOT NULL COMMENT '订单快照ID', asset_identity VARCHAR(255) NOT NULL COMMENT '稳定资产身份', source_file_id BIGINT NULL COMMENT '线上文件ID', asset_type VARCHAR(64) NOT NULL COMMENT '资产类型', original_filename VARCHAR(512) NOT NULL COMMENT '原始文件名', content_type VARCHAR(128) NULL COMMENT '内容类型', extension VARCHAR(32) NULL COMMENT '扩展名', source_size BIGINT NOT NULL COMMENT '源字节数', source_sha256 CHAR(64) NULL COMMENT '源SHA-256', download_sha256 CHAR(64) NOT NULL COMMENT '下载SHA-256', source_platform VARCHAR(64) NULL COMMENT '源平台', source_url TEXT NULL COMMENT '源URL', source_object_key VARCHAR(1024) NULL COMMENT '源对象键', source_etag VARCHAR(255) NULL COMMENT '源ETag', source_version_id VARCHAR(255) NULL COMMENT '源VersionId', source_last_modified DATETIME(3) NULL COMMENT '源修改时间', package_entry_name VARCHAR(1024) NOT NULL COMMENT '包内路径', backup_status VARCHAR(32) NOT NULL COMMENT '备份状态', failure_code VARCHAR(64) NULL COMMENT '失败码', failure_message VARCHAR(1024) NULL COMMENT '失败信息',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单清单资产';

CREATE TABLE IF NOT EXISTS bk_asset_relation (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', asset_id BIGINT NOT NULL COMMENT '资产ID', relation_kind VARCHAR(64) NOT NULL COMMENT '白名单关系类型', source_record_id BIGINT NOT NULL COMMENT '源关系记录ID', relation_version VARCHAR(128) NOT NULL COMMENT '关系版本', relation_hash CHAR(64) NOT NULL COMMENT '关系摘要', original_file_id BIGINT NULL COMMENT '原文件ID', original_url TEXT NULL COMMENT '原URL', relation_payload_json JSON NULL COMMENT '版本化最小重建数据',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资产typed业务关系';

CREATE TABLE IF NOT EXISTS bk_relation_gap (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', snapshot_id BIGINT NOT NULL COMMENT '订单快照ID', relation_kind VARCHAR(64) NOT NULL COMMENT '关系类型', source_record_id VARCHAR(64) NOT NULL COMMENT '来源记录ID', relation_owner_key CHAR(64) NOT NULL COMMENT '关系拥有者摘要', relation_value VARCHAR(1024) NULL COMMENT '原始关系值', original_file_id VARCHAR(64) NULL COMMENT '原始文件ID', reason_code VARCHAR(64) NOT NULL COMMENT '稳定缺口原因码', reason VARCHAR(1024) NOT NULL COMMENT '缺口原因', relation_hash CHAR(64) NOT NULL COMMENT '关系摘要', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='降级备份关系缺口证据';

CREATE TABLE IF NOT EXISTS bk_backup_stage (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '本地任务ID', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', idempotency_key VARCHAR(255) NOT NULL COMMENT '线上回执幂等键', manifest_json JSON NOT NULL COMMENT '待发布清单', result_json JSON NOT NULL COMMENT '待发布订单包结果', manifest_sha256 CHAR(64) NOT NULL COMMENT '清单摘要', package_sha256 CHAR(64) NOT NULL COMMENT '包摘要', package_size BIGINT NOT NULL COMMENT '包大小', storage_volume_id BIGINT NULL COMMENT '存储卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对键', backup_completeness VARCHAR(32) NOT NULL COMMENT 'COMPLETE/INCOMPLETE', stage_status VARCHAR(32) NOT NULL COMMENT 'STAGED/RECEIPT_ACCEPTED/AVAILABLE/SUPERSEDED', published_snapshot_id BIGINT NULL COMMENT '已发布快照ID', receipt_completed_at DATETIME(3) NOT NULL COMMENT '首次回执请求的稳定完成时间', receipt_accepted_at DATETIME(3) NULL, published_at DATETIME(3) NULL, superseded_at DATETIME(3) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 UNIQUE KEY uk_bk_backup_stage_idempotency (idempotency_key), UNIQUE KEY uk_bk_backup_stage_job_order (job_id, source_order_id), KEY idx_bk_backup_stage_order_status (source_order_id, stage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单备份回执前暂存状态';

CREATE TABLE IF NOT EXISTS bk_storage_volume (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', volume_code VARCHAR(64) NOT NULL COMMENT '逻辑卷编码', display_name VARCHAR(128) NOT NULL COMMENT '显示名称', access_type VARCHAR(32) NOT NULL COMMENT 'LOCAL/REMOVABLE/MAPPED_NETWORK', mount_path VARCHAR(1024) NOT NULL COMMENT 'Windows挂载点', root_path VARCHAR(1024) NOT NULL COMMENT '受控业务根目录', volume_fingerprint VARCHAR(255) NULL COMMENT '系统卷指纹', marker_json JSON NULL COMMENT '受控目录卷标记', capacity_pool_id BIGINT NULL COMMENT '容量域ID', tier VARCHAR(32) NOT NULL COMMENT 'HOT/COLD/IMPORT', mount_status VARCHAR(32) NOT NULL COMMENT 'ONLINE/OFFLINE/UNAVAILABLE', write_status VARCHAR(32) NOT NULL COMMENT 'ACTIVE/STANDBY/READ_ONLY/DISABLED', admin_status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/RETIRED', operation_status VARCHAR(32) NOT NULL DEFAULT 'IDLE' COMMENT '卷操作状态', operation_ref_id BIGINT NULL COMMENT '卷操作引用ID', archive_note VARCHAR(512) NULL COMMENT '冷备介质查找备注', offline_label_note VARCHAR(512) NULL COMMENT '线下标签备注', physical_location VARCHAR(512) NULL COMMENT '离线存放位置', archived_at DATETIME(3) NULL COMMENT '最近成功封存时间', order_created_from DATETIME(3) NULL COMMENT '封存订单创建时间起点', order_created_to DATETIME(3) NULL COMMENT '封存订单创建时间终点', archived_order_count BIGINT NULL, archived_file_count BIGINT NULL, archived_bytes BIGINT NULL, last_verified_at DATETIME(3) NULL, priority INT NOT NULL DEFAULT 0 COMMENT '切换优先级', last_probed_at DATETIME(3) NULL COMMENT '最近探测时间', last_error_code VARCHAR(64) NULL COMMENT '最近错误码', last_error_message TEXT NULL COMMENT '最近错误信息', total_bytes BIGINT NULL COMMENT '最近探测总容量', usable_bytes BIGINT NULL COMMENT '最近探测可用容量', hot_active_singleton TINYINT GENERATED ALWAYS AS (CASE WHEN tier='HOT' AND write_status='ACTIVE' AND is_deleted=0 THEN 1 ELSE NULL END) STORED COMMENT 'HOT活动卷唯一约束键', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态存储卷台账';

CREATE TABLE IF NOT EXISTS bk_volume_archive (
 id BIGINT NOT NULL AUTO_INCREMENT, job_id BIGINT NULL, source_volume_id BIGINT NOT NULL, successor_volume_id BIGINT NOT NULL, attempt_no INT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, phase VARCHAR(32) NOT NULL, archive_note VARCHAR(512) NOT NULL, display_name_snapshot VARCHAR(128) NULL, offline_label_note VARCHAR(512) NULL, physical_location VARCHAR(512) NULL, remark VARCHAR(1024) NULL, order_count BIGINT NOT NULL DEFAULT 0, order_package_count BIGINT NOT NULL DEFAULT 0, managed_file_count BIGINT NOT NULL DEFAULT 0, total_bytes BIGINT NOT NULL DEFAULT 0, order_created_from DATETIME(3) NULL, order_created_to DATETIME(3) NULL, period_label VARCHAR(32) NULL, content_manifest_sha256 CHAR(64) NULL, manifest_version BIGINT NOT NULL DEFAULT 0, checkpoint_item_id BIGINT NULL, verification_started_at DATETIME(3) NULL, verification_completed_at DATETIME(3) NULL, archived_at DATETIME(3) NULL, requested_by BIGINT NOT NULL, failure_code VARCHAR(64) NULL, failure_message TEXT NULL, interrupted_reason VARCHAR(1024) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整盘冷备封存尝试';
CREATE TABLE IF NOT EXISTS bk_volume_archive_item (
 id BIGINT NOT NULL AUTO_INCREMENT, archive_id BIGINT NOT NULL, storage_volume_id BIGINT NOT NULL, storage_key VARCHAR(1024) NOT NULL, artifact_type VARCHAR(32) NOT NULL, artifact_id BIGINT NOT NULL, snapshot_id BIGINT NULL, source_order_id BIGINT NULL, order_created_at DATETIME(3) NULL, expected_size BIGINT NOT NULL, expected_sha256 CHAR(64) NOT NULL, expected_manifest_sha256 CHAR(64) NULL, verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING', actual_size BIGINT NULL, actual_sha256 CHAR(64) NULL, verified_at DATETIME(3) NULL, failure_code VARCHAR(64) NULL, failure_message TEXT NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卷封存冻结清单条目';
CREATE TABLE IF NOT EXISTS bk_storage_volume_read_lease (
 id BIGINT NOT NULL AUTO_INCREMENT, storage_volume_id BIGINT NOT NULL, artifact_type VARCHAR(32) NOT NULL, artifact_id BIGINT NOT NULL, purpose VARCHAR(32) NOT NULL, owner VARCHAR(128) NOT NULL, fencing_token BIGINT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL, heartbeat_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL, released_at DATETIME(3) NULL, version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储卷读取租约';

CREATE TABLE IF NOT EXISTS bk_storage_volume_migration_issue (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', issue_type VARCHAR(64) NOT NULL COMMENT '异常类型', artifact_type VARCHAR(32) NULL COMMENT '制品类型', artifact_id BIGINT NULL COMMENT '制品ID', legacy_storage_key VARCHAR(1024) NULL COMMENT '历史存储键', issue_message TEXT NOT NULL COMMENT '异常说明', resolution_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/IGNORED', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储卷迁移异常报告';

CREATE TABLE IF NOT EXISTS bk_order_package (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', backup_mode VARCHAR(32) NOT NULL DEFAULT 'REAL' COMMENT 'REAL/METADATA_SIMULATION', snapshot_id BIGINT NOT NULL COMMENT '快照ID', storage_volume_id BIGINT NOT NULL COMMENT '存储卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对键', package_format VARCHAR(32) NOT NULL COMMENT 'ORDER_ZIP64_V1', file_count INT NOT NULL COMMENT '文件数', source_total_size BIGINT NOT NULL COMMENT '源总字节数', package_size BIGINT NOT NULL COMMENT '包字节数', package_sha256 CHAR(64) NOT NULL COMMENT '包SHA-256', manifest_sha256 CHAR(64) NOT NULL COMMENT '清单SHA-256', status VARCHAR(32) NOT NULL COMMENT 'PENDING_RECEIPT/AVAILABLE/CORRUPT/ABSENT/UNKNOWN', verified_at DATETIME(3) NULL COMMENT '校验时间', cold_covered_at DATETIME(3) NULL COMMENT '冷备覆盖时间', hot_deleted_at DATETIME(3) NULL COMMENT '热备删除时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单ZIP64包';

CREATE TABLE IF NOT EXISTS bk_supplement_file (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', snapshot_id BIGINT NOT NULL COMMENT '快照ID', storage_volume_id BIGINT NOT NULL COMMENT '存储卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对键', original_filename VARCHAR(512) NOT NULL COMMENT '原文件名', internal_filename VARCHAR(512) NOT NULL COMMENT '内部文件名', size BIGINT NOT NULL COMMENT '字节数', sha256 CHAR(64) NOT NULL COMMENT 'SHA-256', content_type VARCHAR(128) NULL COMMENT '内容类型', extension VARCHAR(32) NULL COMMENT '扩展名', uploaded_by BIGINT NOT NULL COMMENT '上传人', uploaded_at DATETIME(3) NOT NULL COMMENT '上传时间', remark VARCHAR(512) NULL COMMENT '备注', hot_status VARCHAR(32) NOT NULL COMMENT '热备状态', cold_coverage_status VARCHAR(32) NOT NULL COMMENT '冷备覆盖状态', covered_batch_id BIGINT NULL COMMENT '覆盖批次ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期无删除接口', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单补传文件';

CREATE TABLE IF NOT EXISTS bk_artifact_copy (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', artifact_type VARCHAR(32) NOT NULL COMMENT 'ORDER_PACKAGE/SUPPLEMENT', artifact_id BIGINT NOT NULL COMMENT 'artifact ID', location_type VARCHAR(32) NOT NULL COMMENT 'HOT_NAS/COLD_BATCH/IMPORT_AREA', location_ref_id BIGINT NOT NULL COMMENT '位置引用ID', storage_volume_id BIGINT NOT NULL COMMENT '存储卷ID', storage_key VARCHAR(1024) NOT NULL COMMENT '卷内相对键', state VARCHAR(32) NOT NULL COMMENT '副本状态', lifecycle VARCHAR(32) NOT NULL COMMENT 'ACTIVE/REPLACING/RETIRED/DAMAGED', active_cold_artifact VARCHAR(160) GENERATED ALWAYS AS (CASE WHEN location_type='COLD_BATCH' AND lifecycle='ACTIVE' AND is_deleted=0 THEN CONCAT(artifact_type,':',artifact_id) ELSE NULL END) STORED COMMENT '活动冷副本唯一键', size BIGINT NOT NULL COMMENT '字节数', sha256 CHAR(64) NOT NULL COMMENT 'SHA-256', last_verified_at DATETIME(3) NULL COMMENT '最近校验时间', error_code VARCHAR(64) NULL COMMENT '错误码', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='artifact存储副本事实';

CREATE TABLE IF NOT EXISTS bk_cold_disk (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', disk_code VARCHAR(64) NOT NULL COMMENT '介质业务编号', volume_serial VARCHAR(255) NOT NULL COMMENT '卷序列号', marker_uuid VARCHAR(128) NOT NULL COMMENT '介质标记UUID', file_system VARCHAR(32) NULL COMMENT '文件系统', label VARCHAR(128) NULL COMMENT '卷标', physical_location VARCHAR(255) NULL COMMENT '物理位置', status VARCHAR(32) NOT NULL COMMENT 'REGISTERED/IN_USE/OFFLINE/DAMAGED/RETIRED', capacity_bytes BIGINT NOT NULL COMMENT '总容量', free_bytes BIGINT NOT NULL COMMENT '空闲容量', last_seen_at DATETIME(3) NULL COMMENT '最近发现时间', last_verified_at DATETIME(3) NULL COMMENT '最近校验时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷备物理介质';

CREATE TABLE IF NOT EXISTS bk_cold_batch (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', batch_no VARCHAR(64) NOT NULL COMMENT '批次号', order_time_from DATETIME(3) NULL COMMENT '订单时间起点', order_time_to DATETIME(3) NULL COMMENT '订单时间终点', disk_id BIGINT NOT NULL COMMENT '单一冷备磁盘ID', format VARCHAR(32) NOT NULL COMMENT 'DIRECTORY_V1/ZIP64_V1/SPLIT_ARCHIVE_V1', status VARCHAR(32) NOT NULL COMMENT '批次状态', order_count INT NOT NULL DEFAULT 0 COMMENT '订单数', artifact_count INT NOT NULL DEFAULT 0 COMMENT 'artifact数', total_size BIGINT NOT NULL DEFAULT 0 COMMENT '总字节数', batch_manifest_sha256 CHAR(64) NULL COMMENT '批次清单SHA-256', completed_at DATETIME(3) NULL COMMENT '完成时间', hot_cleanup_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '热备清理状态', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单盘冷备批次';

CREATE TABLE IF NOT EXISTS bk_cold_batch_artifact (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', batch_id BIGINT NOT NULL COMMENT '冷备批次ID', storage_volume_id BIGINT NULL COMMENT '实际所在存储卷ID', artifact_type VARCHAR(32) NOT NULL COMMENT 'artifact类型', artifact_id BIGINT NOT NULL COMMENT 'artifact ID', relative_path VARCHAR(1024) NOT NULL COMMENT '批次内相对路径', size BIGINT NOT NULL COMMENT '字节数', sha256 CHAR(64) NOT NULL COMMENT 'SHA-256', write_status VARCHAR(32) NOT NULL COMMENT 'PENDING/WRITING/VERIFIED/FAILED/HOT_DELETED', failure_message VARCHAR(1024) NULL COMMENT '失败证据',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷备批次固化artifact集合';

CREATE TABLE IF NOT EXISTS bk_cold_volume (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', batch_id BIGINT NOT NULL COMMENT '冷备批次ID', sequence_no INT NOT NULL COMMENT '物理项序号', name VARCHAR(512) NOT NULL COMMENT '物理项名称', type VARCHAR(32) NOT NULL COMMENT 'DIRECTORY/FILE/VOLUME', size BIGINT NOT NULL COMMENT '字节数', sha256 CHAR(64) NOT NULL COMMENT 'SHA-256', status VARCHAR(32) NOT NULL COMMENT '物理项状态',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷备物理项';

CREATE TABLE IF NOT EXISTS bk_job (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_no VARCHAR(64) NOT NULL COMMENT '任务号', job_type VARCHAR(32) NOT NULL COMMENT '任务类型', status VARCHAR(32) NOT NULL COMMENT '任务状态', current_step VARCHAR(64) NULL COMMENT '当前步骤', idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键', priority INT NOT NULL DEFAULT 0 COMMENT '优先级', progress_current BIGINT NOT NULL DEFAULT 0 COMMENT '当前进度', progress_total BIGINT NOT NULL DEFAULT 0 COMMENT '总进度', config_snapshot_json JSON NULL COMMENT '配置快照', target_storage_volume_id BIGINT NULL COMMENT '备份目标存储卷ID', result_json JSON NULL COMMENT '任务结果', requested_by BIGINT NULL COMMENT '请求人', requested_at DATETIME(3) NOT NULL COMMENT '请求时间', lease_owner VARCHAR(128) NULL COMMENT '租约持有者', lease_until DATETIME(3) NULL COMMENT '租约截止', heartbeat_at DATETIME(3) NULL COMMENT '心跳时间', fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '单调隔离令牌', retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数', next_retry_at DATETIME(3) NULL COMMENT '下次重试时间', cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '取消请求', failure_code VARCHAR(64) NULL COMMENT '失败码', failure_message VARCHAR(1024) NULL COMMENT '失败信息', started_at DATETIME(3) NULL COMMENT '开始时间', finished_at DATETIME(3) NULL COMMENT '结束时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用长任务';

CREATE TABLE IF NOT EXISTS bk_job_order (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '任务ID', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', snapshot_id BIGINT NULL COMMENT '本地快照ID', source_package_id BIGINT NULL COMMENT '恢复源订单包ID', source_storage_volume_id BIGINT NULL COMMENT '恢复源存储卷ID', source_storage_key VARCHAR(1024) NULL COMMENT '恢复源存储键快照', source_package_sha256 CHAR(64) NULL COMMENT '恢复源包哈希快照', storage_volume_id BIGINT NULL COMMENT '订单产物所在卷ID', storage_key VARCHAR(1024) NULL COMMENT '卷内相对位置', storage_volume_name VARCHAR(128) NULL COMMENT '卷名称快照', storage_tier VARCHAR(16) NULL COMMENT '卷层级快照', order_code VARCHAR(128) NULL COMMENT '订单编号', order_created_at VARCHAR(64) NULL COMMENT '订单创建时间', source_status VARCHAR(32) NULL COMMENT '线上业务状态', order_business_json JSON NULL COMMENT '完整订单业务快照', expected_file_count INT NULL COMMENT '模拟预计文件数', expected_bytes BIGINT NULL COMMENT '模拟预计文件容量', simulation_path VARCHAR(1024) NULL COMMENT '模拟制品隔离目录', status VARCHAR(32) NOT NULL COMMENT '订单级结果状态', failure_code VARCHAR(64) NULL COMMENT '失败码', failure_message VARCHAR(1024) NULL COMMENT '失败信息',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务订单级结果';

CREATE TABLE IF NOT EXISTS bk_job_file (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '任务ID', source_order_id BIGINT NULL COMMENT '线上订单ID', storage_volume_id BIGINT NULL COMMENT '文件所在或来源卷ID', storage_key VARCHAR(1024) NULL COMMENT '卷内相对位置', storage_volume_name VARCHAR(128) NULL COMMENT '卷名称快照', storage_tier VARCHAR(16) NULL COMMENT '卷层级快照', asset_identity VARCHAR(255) NOT NULL COMMENT '资产身份', step VARCHAR(64) NOT NULL COMMENT '文件步骤', status VARCHAR(32) NOT NULL COMMENT '步骤状态', bytes_done BIGINT NOT NULL DEFAULT 0 COMMENT '完成字节', bytes_total BIGINT NOT NULL DEFAULT 0 COMMENT '总字节', platform VARCHAR(64) NULL COMMENT 'COS平台', bucket VARCHAR(255) NULL COMMENT 'Bucket', object_key VARCHAR(1024) NULL COMMENT '对象键', version_identity VARCHAR(255) NULL COMMENT '版本身份', temp_path VARCHAR(1024) NULL COMMENT '临时路径', sha256 CHAR(64) NULL COMMENT 'SHA-256', failure_code VARCHAR(64) NULL COMMENT '失败码', failure_message VARCHAR(1024) NULL COMMENT '失败信息',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务文件级证据';

CREATE TABLE IF NOT EXISTS bk_job_execution_log (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '任务ID', source_order_id BIGINT NULL COMMENT '线上订单ID；空表示任务级日志', log_level VARCHAR(16) NOT NULL COMMENT 'INFO/WARN/ERROR', stage VARCHAR(64) NOT NULL COMMENT '可读执行阶段', event_code VARCHAR(64) NOT NULL COMMENT '稳定事件编码', message TEXT NOT NULL COMMENT '中文日志正文（完整保留）', detail_json JSON NULL COMMENT '已脱敏结构化明细', occurred_at DATETIME(3) NOT NULL COMMENT '事件实际发生时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务执行过程日志';

CREATE TABLE IF NOT EXISTS bk_lock (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', lock_key VARCHAR(255) NOT NULL COMMENT '锁键', holder_job_id BIGINT NULL COMMENT '持有任务ID', lease_owner VARCHAR(128) NOT NULL COMMENT '租约持有者', lease_until DATETIME(3) NOT NULL COMMENT '租约截止', fencing_token BIGINT NOT NULL COMMENT '单调隔离令牌', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据库租约锁';

CREATE TABLE IF NOT EXISTS bk_restore_session (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', local_job_id BIGINT NOT NULL COMMENT '本地任务ID', local_idempotency_key VARCHAR(128) NOT NULL COMMENT '本地幂等键', remote_session_id VARCHAR(128) NULL COMMENT '线上会话ID', source_order_id BIGINT NOT NULL COMMENT '线上订单ID', snapshot_id BIGINT NOT NULL COMMENT '快照ID', conflict_policy VARCHAR(32) NOT NULL COMMENT '冲突策略', prepared_revision VARCHAR(128) NULL COMMENT '准备修订号', signed_plan_digest CHAR(64) NULL COMMENT '线上签名计划摘要', relation_set_hash CHAR(64) NULL COMMENT '关系集合哈希', manifest_schema_version INT NOT NULL DEFAULT 1 COMMENT '备份清单协议版本', backup_completeness VARCHAR(32) NOT NULL DEFAULT 'COMPLETE' COMMENT 'COMPLETE/INCOMPLETE', relationship_state_hash CHAR(64) NULL COMMENT 'v2关系状态摘要', status VARCHAR(32) NOT NULL COMMENT '会话状态', protection_until DATETIME(3) NULL COMMENT '恢复后保护截止', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地恢复会话';

CREATE TABLE IF NOT EXISTS bk_restore_file (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', restore_session_id BIGINT NOT NULL COMMENT '恢复会话ID', asset_identity VARCHAR(255) NOT NULL COMMENT '资产身份', original_file_id BIGINT NULL COMMENT '原文件ID', target_object_key VARCHAR(1024) NOT NULL COMMENT '目标对象键', upload_status VARCHAR(32) NOT NULL COMMENT '上传状态', etag VARCHAR(255) NULL COMMENT 'ETag', version_id VARCHAR(255) NULL COMMENT 'VersionId', size BIGINT NOT NULL COMMENT '字节数', sha256 CHAR(64) NOT NULL COMMENT 'SHA-256', new_url TEXT NULL COMMENT '新URL',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='恢复文件证据';

CREATE TABLE IF NOT EXISTS bk_restore_id_mapping (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', restore_session_id BIGINT NOT NULL COMMENT '恢复会话ID', type VARCHAR(64) NOT NULL COMMENT 'FILE/BUSINESS_RECORD', original_id VARCHAR(128) NOT NULL COMMENT '原ID', new_id VARCHAR(128) NOT NULL COMMENT '新ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；永久保留', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='恢复原新ID映射';

CREATE TABLE IF NOT EXISTS bk_orphan_object (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', restore_session_id BIGINT NOT NULL COMMENT '失败恢复会话ID', platform VARCHAR(64) NOT NULL COMMENT '平台', bucket VARCHAR(255) NOT NULL COMMENT 'Bucket', object_key VARCHAR(1024) NOT NULL COMMENT '对象键', version_identity VARCHAR(255) NOT NULL COMMENT '精确版本身份', confirmation_status VARCHAR(32) NOT NULL COMMENT '确认状态', earliest_cleanup_at DATETIME(3) NOT NULL COMMENT '最早清理时间', cleanup_status VARCHAR(32) NOT NULL COMMENT '清理状态', cleanup_result VARCHAR(1024) NULL COMMENT '清理结果', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='恢复失败孤儿对象';

CREATE TABLE IF NOT EXISTS bk_storage_pool (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', capacity_identity VARCHAR(255) NOT NULL COMMENT '稳定容量域身份', storage_type VARCHAR(32) NOT NULL COMMENT 'LOCAL/NAS/COLD/IMPORT', root_path VARCHAR(1024) NOT NULL COMMENT '根路径', volume_identity VARCHAR(255) NULL COMMENT '卷GUID或UNC共享身份', quota_identity VARCHAR(255) NULL COMMENT '配额身份', probe_config_json JSON NULL COMMENT '探测配置', status VARCHAR(32) NOT NULL COMMENT '存储池状态', capacity_bytes BIGINT NULL COMMENT '总容量', available_bytes BIGINT NULL COMMENT '可用容量', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储容量域';

CREATE TABLE IF NOT EXISTS bk_storage_probe_history (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', storage_pool_id BIGINT NOT NULL COMMENT '存储池ID', probe_status VARCHAR(32) NOT NULL COMMENT '探测结果', capacity_bytes BIGINT NULL COMMENT '总容量', available_bytes BIGINT NULL COMMENT '可用容量', latency_ms BIGINT NULL COMMENT '探测延迟毫秒', error_code VARCHAR(64) NULL COMMENT '错误码', error_message VARCHAR(1024) NULL COMMENT '错误信息', probed_at DATETIME(3) NOT NULL COMMENT '探测时间',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储探测历史';

CREATE TABLE IF NOT EXISTS bk_space_reservation (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', storage_pool_id BIGINT NOT NULL COMMENT '容量域ID', job_id BIGINT NOT NULL COMMENT '任务ID', reserved_bytes BIGINT NOT NULL COMMENT '预留字节数', lease_until DATETIME(3) NOT NULL COMMENT '租约截止', fencing_token BIGINT NOT NULL COMMENT '单调隔离令牌', status VARCHAR(32) NOT NULL COMMENT 'ACTIVE/RELEASED/EXPIRED', status_active TINYINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' AND is_deleted=0 THEN 1 ELSE NULL END) STORED COMMENT '活动状态唯一键；非活动记录为NULL以保留多条历史', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='空间原子预留';

CREATE TABLE IF NOT EXISTS bk_config (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', config_key VARCHAR(128) NOT NULL COMMENT '配置键', config_name VARCHAR(128) NULL COMMENT '配置名称', category_code VARCHAR(32) NULL COMMENT '配置分类编码', category_name VARCHAR(64) NULL COMMENT '配置分类名称', scope VARCHAR(64) NOT NULL COMMENT '作用域', value_type VARCHAR(32) NOT NULL COMMENT '值类型', config_value TEXT NULL COMMENT '非敏感配置值', options_json JSON NULL COMMENT '配置可选项JSON', description VARCHAR(1024) NULL COMMENT '配置说明及影响', secret_ref VARCHAR(512) NULL COMMENT '外部密钥引用', config_version INT NOT NULL COMMENT '配置版本', changed_by BIGINT NULL COMMENT '修改人', changed_at DATETIME(3) NOT NULL COMMENT '修改时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化配置';

CREATE TABLE IF NOT EXISTS bk_sync_cursor (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', cursor_type VARCHAR(64) NOT NULL COMMENT 'CANDIDATE/USER/PERMIT_REQUEST', cursor_value VARCHAR(2048) NOT NULL COMMENT '不透明游标', high_watermark VARCHAR(2048) NULL COMMENT '分页高水位', lookback_seconds INT NOT NULL DEFAULT 0 COMMENT '回看窗口秒数', last_success_at DATETIME(3) NULL COMMENT '最近成功时间', last_attempt_at DATETIME(3) NULL COMMENT '最近尝试时间', sync_status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING/SUCCEEDED/FAILED', last_item_count INT NOT NULL DEFAULT 0 COMMENT '最近一轮已提交数量', last_error_message VARCHAR(1024) NULL COMMENT '最近失败原因', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='增量同步游标';

CREATE TABLE IF NOT EXISTS bk_safety_event_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', event_uuid CHAR(36) NOT NULL COMMENT '事件UUID', event_type VARCHAR(64) NOT NULL COMMENT '安全事件类型', artifact_type VARCHAR(32) NOT NULL COMMENT 'artifact类型', artifact_id BIGINT NOT NULL COMMENT 'artifact ID', receipt_id BIGINT NULL COMMENT '线上凭证ID', payload_digest CHAR(64) NOT NULL COMMENT '载荷摘要', payload_json JSON NOT NULL COMMENT '事件载荷', delivery_status VARCHAR(32) NOT NULL COMMENT 'PENDING/DELIVERING/DELIVERED/FAILED', retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数', next_retry_at DATETIME(3) NULL COMMENT '下次重试', delivered_at DATETIME(3) NULL COMMENT '送达时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全事件可靠出站箱';

CREATE TABLE IF NOT EXISTS bk_audit_log (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', chain_id VARCHAR(128) NOT NULL COMMENT '审计链ID', sequence_no BIGINT NOT NULL COMMENT '链内序号', actor_user_id BIGINT NULL COMMENT '用户ID', actor_role_code VARCHAR(32) NULL COMMENT '角色', client_address VARCHAR(128) NULL COMMENT '客户端地址', action VARCHAR(128) NOT NULL COMMENT '动作', object_type VARCHAR(64) NOT NULL COMMENT '对象类型', object_id VARCHAR(128) NOT NULL COMMENT '对象ID', job_id BIGINT NULL COMMENT '任务ID', input_digest CHAR(64) NOT NULL COMMENT '输入摘要', result_code VARCHAR(64) NOT NULL COMMENT '结果码', failure_reason VARCHAR(1024) NULL COMMENT '失败原因', previous_entry_hash CHAR(64) NULL COMMENT '前条哈希', entry_hash CHAR(64) NOT NULL COMMENT '本条哈希', occurred_at DATETIME(3) NOT NULL COMMENT '发生时间',
	 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；只插入', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='哈希链审计日志';

CREATE TABLE IF NOT EXISTS bk_audit_chain_head (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', chain_id VARCHAR(128) NOT NULL COMMENT '审计链ID', last_sequence_no BIGINT NOT NULL DEFAULT 0 COMMENT '最新序号', last_entry_hash CHAR(64) NULL COMMENT '链头哈希', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计链头';

CREATE TABLE IF NOT EXISTS cos_cleanup_task (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', task_no VARCHAR(64) NOT NULL COMMENT '清理任务号', idempotency_key VARCHAR(128) NOT NULL COMMENT '创建幂等键', policy_version INT NOT NULL COMMENT '策略版本', mode VARCHAR(16) NOT NULL COMMENT 'DRY_RUN/ACTUAL', status VARCHAR(32) NOT NULL COMMENT 'CREATED/PAUSED/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED', snapshot_hash CHAR(64) NULL COMMENT '候选快照摘要', object_set_hash CHAR(64) NULL COMMENT '对象集合摘要', estimated_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '预计释放字节', actual_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '实际释放字节', fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '隔离令牌', failure_message VARCHAR(1024) NULL COMMENT '失败说明', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='COS清理任务';

CREATE TABLE IF NOT EXISTS cos_cleanup_item (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', cleanup_task_id BIGINT NOT NULL COMMENT '清理任务ID', platform VARCHAR(64) NOT NULL COMMENT '平台', bucket VARCHAR(255) NOT NULL COMMENT 'Bucket', object_key VARCHAR(1024) NOT NULL COMMENT '对象键', version_identity VARCHAR(255) NOT NULL COMMENT '对象版本身份', source_file_id BIGINT NULL COMMENT '源文件ID', shared_reference_status VARCHAR(32) NULL COMMENT '共享引用校验结果', deletion_status VARCHAR(32) NOT NULL COMMENT 'PENDING/DELETING/DELETED/FAILED/SKIPPED', version_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '版本字节数', error_message VARCHAR(1024) NULL COMMENT '对象级错误', retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数', next_retry_at DATETIME(3) NULL COMMENT '下次重试时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='COS清理对象明细';

CREATE TABLE IF NOT EXISTS cos_delete_permit (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', permit_id VARCHAR(128) NOT NULL COMMENT '线上授权ID', cleanup_task_id BIGINT NOT NULL COMMENT '清理任务ID', attempt_no INT NOT NULL COMMENT '授权尝试号', status VARCHAR(32) NOT NULL COMMENT 'ISSUED/CLAIMED/CONSUMED/EXPIRED/REVOKED', snapshot_hash CHAR(64) NULL COMMENT '快照摘要', object_set_hash CHAR(64) NULL COMMENT '对象集合摘要', fencing_token VARCHAR(128) NULL COMMENT '线上隔离令牌', receipt_id VARCHAR(128) NULL COMMENT '线上凭证ID', receipt_version INT NULL COMMENT '凭证版本', package_hash CHAR(64) NULL COMMENT '包摘要', issued_at DATETIME(3) NULL COMMENT '签发时间', expires_at DATETIME(3) NULL COMMENT '过期时间', nonce VARCHAR(128) NULL COMMENT '防重放随机数', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='COS删除授权';

-- 执行后验证：应返回 30；所有表均应包含六个公共字段。
SELECT COUNT(*) AS local_domain_table_count FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'bk\_%';
SELECT t.table_name FROM information_schema.tables t WHERE t.table_schema=DATABASE() AND t.table_name LIKE 'bk\_%'
AND EXISTS (SELECT 1 FROM (SELECT 'id' c UNION ALL SELECT 'create_time' UNION ALL SELECT 'update_time' UNION ALL SELECT 'create_by' UNION ALL SELECT 'update_by' UNION ALL SELECT 'is_deleted') required_column
 WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema=t.table_schema AND c.table_name=t.table_name AND c.column_name=required_column.c));
-- 人工回滚：任何表含备份、任务、恢复、映射、安全或审计证据时禁止 DROP；关闭服务并保留库作只读证据。
-- ============================================================================
-- END SOURCE: sql/init/01-schema.sql
-- ============================================================================

-- ============================================================================
-- BEGIN SOURCE: sql/init/02-indexes.sql
-- ============================================================================
-- 本地备份库唯一约束、游标、任务领取、状态查询、活动副本和审计索引。
-- 执行前检查：先执行 01-schema.sql；确认不存在会违反以下唯一约束的重复数据。
-- 可重入策略：通过 information_schema.statistics 判断索引名，存在则跳过。

DELIMITER $$
DROP PROCEDURE IF EXISTS add_backup_index_if_missing$$
CREATE PROCEDURE add_backup_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=p_table AND index_name=p_index) THEN
    SET @backup_index_ddl=p_ddl;
    PREPARE backup_index_stmt FROM @backup_index_ddl;
    EXECUTE backup_index_stmt;
    DEALLOCATE PREPARE backup_index_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_backup_index_if_missing('bk_user','uk_bk_user_source','CREATE UNIQUE INDEX uk_bk_user_source ON bk_user(source_user_id)');
CALL add_backup_index_if_missing('bk_user','uk_bk_user_active_username','CREATE UNIQUE INDEX uk_bk_user_active_username ON bk_user(active_username)');
CALL add_backup_index_if_missing('bk_user','idx_bk_user_enabled','CREATE INDEX idx_bk_user_enabled ON bk_user(enabled_status,is_deleted)');
CALL add_backup_index_if_missing('bk_user_field_control','uk_bk_user_field','CREATE UNIQUE INDEX uk_bk_user_field ON bk_user_field_control(user_id,field_name)');

CALL add_backup_index_if_missing('bk_backup_candidate','uk_bk_candidate_order','CREATE UNIQUE INDEX uk_bk_candidate_order ON bk_backup_candidate(source_order_id)');
CALL add_backup_index_if_missing('bk_backup_candidate','uk_bk_candidate_event','CREATE UNIQUE INDEX uk_bk_candidate_event ON bk_backup_candidate(source_event_id)');
CALL add_backup_index_if_missing('bk_backup_candidate','idx_bk_candidate_claim','CREATE INDEX idx_bk_candidate_claim ON bk_backup_candidate(candidate_status,next_retry_at,version)');
CALL add_backup_index_if_missing('bk_backup_candidate','idx_bk_candidate_cursor','CREATE INDEX idx_bk_candidate_cursor ON bk_backup_candidate(backup_eligible_at,source_event_id)');
CALL add_backup_index_if_missing('bk_order_snapshot','uk_bk_snapshot_order','CREATE UNIQUE INDEX uk_bk_snapshot_order ON bk_order_snapshot(source_order_id)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_code','CREATE INDEX idx_bk_snapshot_code ON bk_order_snapshot(order_code)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_completed','CREATE INDEX idx_bk_snapshot_completed ON bk_order_snapshot(backup_completed_at,id)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_public_code','CREATE INDEX idx_bk_snapshot_public_code ON bk_order_snapshot(public_order_code)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_created','CREATE INDEX idx_bk_snapshot_created ON bk_order_snapshot(order_created_at,id)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_org','CREATE INDEX idx_bk_snapshot_org ON bk_order_snapshot(org_id,order_created_at)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_hospital','CREATE INDEX idx_bk_snapshot_hospital ON bk_order_snapshot(hospital_id,order_created_at)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_business','CREATE INDEX idx_bk_snapshot_business ON bk_order_snapshot(business_type,phase,status)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_patient','CREATE INDEX idx_bk_snapshot_patient ON bk_order_snapshot(patient_name)');
CALL add_backup_index_if_missing('bk_order_snapshot','idx_bk_snapshot_doctor','CREATE INDEX idx_bk_snapshot_doctor ON bk_order_snapshot(doctor_name)');

CALL add_backup_index_if_missing('bk_asset','uk_bk_asset_identity','CREATE UNIQUE INDEX uk_bk_asset_identity ON bk_asset(snapshot_id,asset_identity)');
CALL add_backup_index_if_missing('bk_asset','idx_bk_asset_file','CREATE INDEX idx_bk_asset_file ON bk_asset(source_file_id)');
CALL add_backup_index_if_missing('bk_asset','idx_bk_asset_type','CREATE INDEX idx_bk_asset_type ON bk_asset(asset_type,backup_status)');
CALL add_backup_index_if_missing('bk_asset_relation','uk_bk_asset_relation','CREATE UNIQUE INDEX uk_bk_asset_relation ON bk_asset_relation(asset_id,relation_kind,source_record_id)');
CALL add_backup_index_if_missing('bk_asset_relation','idx_bk_asset_relation_hash','CREATE INDEX idx_bk_asset_relation_hash ON bk_asset_relation(relation_hash)');
CALL add_backup_index_if_missing('bk_relation_gap','uk_bk_relation_gap_owner','CREATE UNIQUE INDEX uk_bk_relation_gap_owner ON bk_relation_gap(snapshot_id,relation_owner_key)');
CALL add_backup_index_if_missing('bk_relation_gap','idx_bk_relation_gap_reason','CREATE INDEX idx_bk_relation_gap_reason ON bk_relation_gap(reason_code)');
CALL add_backup_index_if_missing('bk_relation_gap','idx_bk_relation_gap_hash','CREATE INDEX idx_bk_relation_gap_hash ON bk_relation_gap(relation_hash)');
CALL add_backup_index_if_missing('bk_order_package','uk_bk_package_snapshot','CREATE UNIQUE INDEX uk_bk_package_snapshot ON bk_order_package(snapshot_id)');
CALL add_backup_index_if_missing('bk_order_package','uk_bk_package_volume_key','CREATE UNIQUE INDEX uk_bk_package_volume_key ON bk_order_package(storage_volume_id,storage_key(191))');
CALL add_backup_index_if_missing('bk_order_package','idx_bk_package_status','CREATE INDEX idx_bk_package_status ON bk_order_package(status,verified_at)');
CALL add_backup_index_if_missing('bk_supplement_file','uk_bk_supplement_volume_key','CREATE UNIQUE INDEX uk_bk_supplement_volume_key ON bk_supplement_file(storage_volume_id,storage_key(191))');
CALL add_backup_index_if_missing('bk_supplement_file','idx_bk_supplement_snapshot','CREATE INDEX idx_bk_supplement_snapshot ON bk_supplement_file(snapshot_id,uploaded_at)');
CALL add_backup_index_if_missing('bk_artifact_copy','uk_bk_artifact_location','CREATE UNIQUE INDEX uk_bk_artifact_location ON bk_artifact_copy(artifact_type,artifact_id,location_type,location_ref_id)');
CALL add_backup_index_if_missing('bk_artifact_copy','uk_bk_artifact_copy_volume_key','CREATE UNIQUE INDEX uk_bk_artifact_copy_volume_key ON bk_artifact_copy(storage_volume_id,storage_key(191))');
CALL add_backup_index_if_missing('bk_artifact_copy','uk_bk_artifact_active_cold','CREATE UNIQUE INDEX uk_bk_artifact_active_cold ON bk_artifact_copy(active_cold_artifact)');
CALL add_backup_index_if_missing('bk_artifact_copy','idx_bk_artifact_lifecycle','CREATE INDEX idx_bk_artifact_lifecycle ON bk_artifact_copy(artifact_type,artifact_id,lifecycle)');

CALL add_backup_index_if_missing('bk_cold_disk','uk_bk_cold_disk_code','CREATE UNIQUE INDEX uk_bk_cold_disk_code ON bk_cold_disk(disk_code)');
CALL add_backup_index_if_missing('bk_cold_disk','uk_bk_cold_marker','CREATE UNIQUE INDEX uk_bk_cold_marker ON bk_cold_disk(marker_uuid)');
CALL add_backup_index_if_missing('bk_cold_disk','idx_bk_cold_volume','CREATE INDEX idx_bk_cold_volume ON bk_cold_disk(volume_serial,status)');
CALL add_backup_index_if_missing('bk_cold_batch','uk_bk_cold_batch_no','CREATE UNIQUE INDEX uk_bk_cold_batch_no ON bk_cold_batch(batch_no)');
CALL add_backup_index_if_missing('bk_cold_batch','idx_bk_cold_batch_disk','CREATE INDEX idx_bk_cold_batch_disk ON bk_cold_batch(disk_id,status)');
CALL add_backup_index_if_missing('bk_cold_batch_artifact','uk_bk_cold_artifact','CREATE UNIQUE INDEX uk_bk_cold_artifact ON bk_cold_batch_artifact(batch_id,artifact_type,artifact_id)');
CALL add_backup_index_if_missing('bk_cold_batch_artifact','idx_bk_cold_artifact_id','CREATE INDEX idx_bk_cold_artifact_id ON bk_cold_batch_artifact(artifact_id)');
CALL add_backup_index_if_missing('bk_cold_volume','uk_bk_cold_volume_seq','CREATE UNIQUE INDEX uk_bk_cold_volume_seq ON bk_cold_volume(batch_id,sequence_no)');
CALL add_backup_index_if_missing('bk_cold_volume','uk_bk_cold_volume_name','CREATE UNIQUE INDEX uk_bk_cold_volume_name ON bk_cold_volume(batch_id,name)');

CALL add_backup_index_if_missing('bk_storage_volume','uk_bk_storage_volume_code','CREATE UNIQUE INDEX uk_bk_storage_volume_code ON bk_storage_volume(volume_code)');
CALL add_backup_index_if_missing('bk_storage_volume','uk_bk_storage_hot_active','CREATE UNIQUE INDEX uk_bk_storage_hot_active ON bk_storage_volume(hot_active_singleton)');
CALL add_backup_index_if_missing('bk_volume_archive','uk_bk_volume_archive_attempt','CREATE UNIQUE INDEX uk_bk_volume_archive_attempt ON bk_volume_archive(source_volume_id,attempt_no)');
CALL add_backup_index_if_missing('bk_volume_archive','uk_bk_volume_archive_idempotency','CREATE UNIQUE INDEX uk_bk_volume_archive_idempotency ON bk_volume_archive(idempotency_key)');
CALL add_backup_index_if_missing('bk_volume_archive_item','uk_bk_volume_archive_item_path','CREATE UNIQUE INDEX uk_bk_volume_archive_item_path ON bk_volume_archive_item(archive_id,storage_volume_id,storage_key(191))');
CALL add_backup_index_if_missing('bk_storage_volume_read_lease','idx_bk_volume_read_lease_active','CREATE INDEX idx_bk_volume_read_lease_active ON bk_storage_volume_read_lease(storage_volume_id,status,expires_at)');
CALL add_backup_index_if_missing('bk_storage_volume','idx_bk_storage_volume_status','CREATE INDEX idx_bk_storage_volume_status ON bk_storage_volume(tier,mount_status,write_status,priority)');
CALL add_backup_index_if_missing('bk_storage_volume','idx_bk_storage_volume_fingerprint','CREATE INDEX idx_bk_storage_volume_fingerprint ON bk_storage_volume(volume_fingerprint)');
CALL add_backup_index_if_missing('bk_storage_volume_migration_issue','idx_bk_storage_volume_migration_issue','CREATE INDEX idx_bk_storage_volume_migration_issue ON bk_storage_volume_migration_issue(resolution_status,issue_type)');

CALL add_backup_index_if_missing('bk_job','uk_bk_job_no','CREATE UNIQUE INDEX uk_bk_job_no ON bk_job(job_no)');
CALL add_backup_index_if_missing('bk_job','uk_bk_job_idempotency','CREATE UNIQUE INDEX uk_bk_job_idempotency ON bk_job(idempotency_key)');
CALL add_backup_index_if_missing('bk_job','idx_bk_job_claim','CREATE INDEX idx_bk_job_claim ON bk_job(status,next_retry_at,priority,lease_until)');
CALL add_backup_index_if_missing('bk_job','idx_bk_job_lease','CREATE INDEX idx_bk_job_lease ON bk_job(lease_until,heartbeat_at)');
CALL add_backup_index_if_missing('bk_job','idx_bk_job_target_volume','CREATE INDEX idx_bk_job_target_volume ON bk_job(target_storage_volume_id,status)');
CALL add_backup_index_if_missing('bk_job_order','uk_bk_job_order','CREATE UNIQUE INDEX uk_bk_job_order ON bk_job_order(job_id,source_order_id)');
CALL add_backup_index_if_missing('bk_job_order','idx_bk_job_order_snapshot','CREATE INDEX idx_bk_job_order_snapshot ON bk_job_order(snapshot_id)');
CALL add_backup_index_if_missing('bk_job_order','idx_bk_job_order_source_volume','CREATE INDEX idx_bk_job_order_source_volume ON bk_job_order(source_storage_volume_id,status)');
CALL add_backup_index_if_missing('bk_job_order','idx_bk_job_order_storage_volume','CREATE INDEX idx_bk_job_order_storage_volume ON bk_job_order(storage_volume_id,job_id)');
CALL add_backup_index_if_missing('bk_job_file','uk_bk_job_file_step','CREATE UNIQUE INDEX uk_bk_job_file_step ON bk_job_file(job_id,asset_identity,step)');
CALL add_backup_index_if_missing('bk_job_file','idx_bk_job_file_status','CREATE INDEX idx_bk_job_file_status ON bk_job_file(job_id,status,id)');
CALL add_backup_index_if_missing('bk_job_file','idx_bk_job_file_storage_volume','CREATE INDEX idx_bk_job_file_storage_volume ON bk_job_file(storage_volume_id,job_id)');
CALL add_backup_index_if_missing('bk_job_execution_log','idx_bk_job_log_task_time','CREATE INDEX idx_bk_job_log_task_time ON bk_job_execution_log(job_id,occurred_at,id)');
CALL add_backup_index_if_missing('bk_job_execution_log','idx_bk_job_log_order_time','CREATE INDEX idx_bk_job_log_order_time ON bk_job_execution_log(job_id,source_order_id,occurred_at,id)');
CALL add_backup_index_if_missing('bk_lock','uk_bk_lock_key','CREATE UNIQUE INDEX uk_bk_lock_key ON bk_lock(lock_key)');
CALL add_backup_index_if_missing('bk_lock','idx_bk_lock_lease','CREATE INDEX idx_bk_lock_lease ON bk_lock(lease_until)');

CALL add_backup_index_if_missing('bk_restore_session','uk_bk_restore_local_key','CREATE UNIQUE INDEX uk_bk_restore_local_key ON bk_restore_session(local_idempotency_key)');
CALL add_backup_index_if_missing('bk_restore_session','uk_bk_restore_remote','CREATE UNIQUE INDEX uk_bk_restore_remote ON bk_restore_session(remote_session_id)');
CALL add_backup_index_if_missing('bk_restore_session','idx_bk_restore_order','CREATE INDEX idx_bk_restore_order ON bk_restore_session(source_order_id,status)');
CALL add_backup_index_if_missing('bk_restore_file','uk_bk_restore_file','CREATE UNIQUE INDEX uk_bk_restore_file ON bk_restore_file(restore_session_id,asset_identity)');
CALL add_backup_index_if_missing('bk_restore_file','idx_bk_restore_upload','CREATE INDEX idx_bk_restore_upload ON bk_restore_file(upload_status)');
CALL add_backup_index_if_missing('bk_restore_id_mapping','uk_bk_restore_mapping','CREATE UNIQUE INDEX uk_bk_restore_mapping ON bk_restore_id_mapping(restore_session_id,original_id,type)');
CALL add_backup_index_if_missing('bk_restore_id_mapping','idx_bk_restore_new_id','CREATE INDEX idx_bk_restore_new_id ON bk_restore_id_mapping(new_id)');
CALL add_backup_index_if_missing('bk_orphan_object','uk_bk_orphan_object','CREATE UNIQUE INDEX uk_bk_orphan_object ON bk_orphan_object(restore_session_id,platform,bucket,object_key(191),version_identity)');
CALL add_backup_index_if_missing('bk_orphan_object','idx_bk_orphan_cleanup','CREATE INDEX idx_bk_orphan_cleanup ON bk_orphan_object(cleanup_status,earliest_cleanup_at)');

CALL add_backup_index_if_missing('bk_storage_pool','uk_bk_storage_capacity','CREATE UNIQUE INDEX uk_bk_storage_capacity ON bk_storage_pool(capacity_identity)');
CALL add_backup_index_if_missing('bk_storage_pool','idx_bk_storage_type','CREATE INDEX idx_bk_storage_type ON bk_storage_pool(storage_type,status)');
CALL add_backup_index_if_missing('bk_storage_probe_history','idx_bk_probe_pool_time','CREATE INDEX idx_bk_probe_pool_time ON bk_storage_probe_history(storage_pool_id,probed_at)');
CALL add_backup_index_if_missing('bk_space_reservation','uk_bk_space_active','CREATE UNIQUE INDEX uk_bk_space_active ON bk_space_reservation(storage_pool_id,job_id,status_active)');
CALL add_backup_index_if_missing('bk_space_reservation','idx_bk_space_lease','CREATE INDEX idx_bk_space_lease ON bk_space_reservation(status,lease_until)');
CALL add_backup_index_if_missing('bk_config','uk_bk_config_version','CREATE UNIQUE INDEX uk_bk_config_version ON bk_config(config_key,scope,config_version)');
CALL add_backup_index_if_missing('bk_sync_cursor','uk_bk_sync_cursor','CREATE UNIQUE INDEX uk_bk_sync_cursor ON bk_sync_cursor(cursor_type)');
CALL add_backup_index_if_missing('bk_safety_event_outbox','uk_bk_safety_event','CREATE UNIQUE INDEX uk_bk_safety_event ON bk_safety_event_outbox(event_uuid)');
CALL add_backup_index_if_missing('bk_safety_event_outbox','idx_bk_safety_delivery','CREATE INDEX idx_bk_safety_delivery ON bk_safety_event_outbox(delivery_status,next_retry_at,create_time)');
CALL add_backup_index_if_missing('bk_safety_event_outbox','idx_bk_safety_receipt','CREATE INDEX idx_bk_safety_receipt ON bk_safety_event_outbox(receipt_id,delivery_status)');
CALL add_backup_index_if_missing('bk_audit_log','uk_bk_audit_sequence','CREATE UNIQUE INDEX uk_bk_audit_sequence ON bk_audit_log(chain_id,sequence_no)');
CALL add_backup_index_if_missing('bk_audit_log','uk_bk_audit_hash','CREATE UNIQUE INDEX uk_bk_audit_hash ON bk_audit_log(entry_hash)');
CALL add_backup_index_if_missing('bk_audit_log','idx_bk_audit_object','CREATE INDEX idx_bk_audit_object ON bk_audit_log(object_type,object_id,occurred_at)');
CALL add_backup_index_if_missing('bk_audit_chain_head','uk_bk_audit_chain','CREATE UNIQUE INDEX uk_bk_audit_chain ON bk_audit_chain_head(chain_id)');

CALL add_backup_index_if_missing('cos_cleanup_task','uk_cos_cleanup_task_no','CREATE UNIQUE INDEX uk_cos_cleanup_task_no ON cos_cleanup_task(task_no)');
CALL add_backup_index_if_missing('cos_cleanup_task','uk_cos_cleanup_task_idempotency','CREATE UNIQUE INDEX uk_cos_cleanup_task_idempotency ON cos_cleanup_task(idempotency_key)');
CALL add_backup_index_if_missing('cos_cleanup_task','idx_cos_cleanup_task_status','CREATE INDEX idx_cos_cleanup_task_status ON cos_cleanup_task(status,update_time)');
CALL add_backup_index_if_missing('cos_cleanup_item','uk_cos_cleanup_item_identity','CREATE UNIQUE INDEX uk_cos_cleanup_item_identity ON cos_cleanup_item(cleanup_task_id,platform,bucket,object_key(191),version_identity)');
CALL add_backup_index_if_missing('cos_cleanup_item','idx_cos_cleanup_item_status','CREATE INDEX idx_cos_cleanup_item_status ON cos_cleanup_item(cleanup_task_id,deletion_status,next_retry_at)');
CALL add_backup_index_if_missing('cos_delete_permit','uk_cos_delete_permit_id','CREATE UNIQUE INDEX uk_cos_delete_permit_id ON cos_delete_permit(permit_id)');
CALL add_backup_index_if_missing('cos_delete_permit','uk_cos_delete_permit_attempt','CREATE UNIQUE INDEX uk_cos_delete_permit_attempt ON cos_delete_permit(cleanup_task_id,attempt_no)');
CALL add_backup_index_if_missing('cos_delete_permit','uk_cos_delete_permit_nonce','CREATE UNIQUE INDEX uk_cos_delete_permit_nonce ON cos_delete_permit(nonce)');
CALL add_backup_index_if_missing('cos_delete_permit','idx_cos_delete_permit_status','CREATE INDEX idx_cos_delete_permit_status ON cos_delete_permit(cleanup_task_id,status,expires_at)');

DROP PROCEDURE IF EXISTS add_backup_index_if_missing;

-- 执行后验证：missing_index_count 应为 0；重复执行不新增索引。
SELECT COUNT(*) AS backup_index_count FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name LIKE 'bk\_%' AND index_name<>'PRIMARY';
SELECT table_name,index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_order
FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name LIKE 'bk\_%'
GROUP BY table_name,index_name,non_unique ORDER BY table_name,index_name;
-- 人工回滚：只有在 EXPLAIN 证明索引未被使用且无唯一性依赖时才可逐项 DROP INDEX；禁止删除表或业务数据。
-- ============================================================================
-- END SOURCE: sql/init/02-indexes.sql
-- ============================================================================

-- ============================================================================
-- BEGIN SOURCE: sql/init/03-base-version.sql
-- ============================================================================
-- 补齐所有继承 BaseEntity 的旧表乐观锁字段。
-- 可重入：已有 version 字段时跳过，兼容已初始化的旧数据库。
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
CALL add_backup_version_if_missing('bk_relation_gap');
CALL add_backup_version_if_missing('bk_supplement_file');
CALL add_backup_version_if_missing('bk_cold_batch_artifact');
CALL add_backup_version_if_missing('bk_cold_volume');
CALL add_backup_version_if_missing('bk_job_order');
CALL add_backup_version_if_missing('bk_job_file');
CALL add_backup_version_if_missing('bk_restore_id_mapping');
CALL add_backup_version_if_missing('bk_storage_probe_history');
DROP PROCEDURE IF EXISTS add_backup_version_if_missing;
-- ============================================================================
-- END SOURCE: sql/init/03-base-version.sql
-- ============================================================================

-- ============================================================================
-- BEGIN SOURCE: sql/init/04-functional-config-defaults.sql
-- ============================================================================
-- 备份子系统业务配置最终基线。
-- 同一个业务配置只存在于 bk_config；仅在配置键不存在时插入，不覆盖现场值。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, options_json, description, secret_ref, config_version,
     changed_at, create_time, update_time)
SELECT v.config_key, v.config_name, v.category_code, v.category_name, 'GLOBAL', v.value_type,
       v.config_value, v.options_json, v.description, v.secret_ref, 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM (
    SELECT 'backup.schedule.enabled' config_key, '自动备份开关' config_name, 'BACKUP_COLLECTION' category_code, '备份采集策略' category_name, 'BOOLEAN' value_type, 'true' config_value, '[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]' options_json, '控制是否自动发现候选订单并创建热备任务。' description, NULL secret_ref
    UNION ALL SELECT 'backup.schedule.interval-days','自动备份周期（天）','BACKUP_COLLECTION','备份采集策略','INTEGER','1',NULL,'自动备份任务执行周期，最小1天。',NULL
    UNION ALL SELECT 'backup.schedule.start-time','自动备份开始时间','BACKUP_COLLECTION','备份采集策略','STRING','05:00',NULL,'按服务器本地时区执行，格式HH:mm；服务停机错过后当天不补跑。',NULL
    UNION ALL SELECT 'backup.collection.days-before','备份X天前的订单','BACKUP_COLLECTION','备份采集策略','INTEGER','7',NULL,'按订单创建日期和服务器本地自然日计算；配置7表示执行日往前第7天及更早创建的正式订单进入备份范围。',NULL
    UNION ALL SELECT 'backup.retry.max-retries','订单最大重试次数','BACKUP_COLLECTION','备份采集策略','INTEGER','3',NULL,'单个订单首次失败后在当前备份任务中的最大重试次数。',NULL
    UNION ALL SELECT 'backup.retry.delays-seconds','订单重试等待秒数','BACKUP_COLLECTION','备份采集策略','STRING','60,300,900',NULL,'订单失败后各次重试前的等待秒数，逗号分隔。',NULL
    UNION ALL SELECT 'backup.schedule.reconcile-enabled','候选对账开关','BACKUP_COLLECTION','备份采集策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制是否定期对账候选订单；自动备份总开关关闭时本开关也不执行。',NULL
    UNION ALL SELECT 'backup.schedule.reconcile-interval-hours','候选对账周期（小时）','BACKUP_COLLECTION','备份采集策略','INTEGER','24',NULL,'漏单、取消和删除事件的对账周期。',NULL
    UNION ALL SELECT 'storage.backup-reservation-percent','备份工作空间预留比例（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','300',NULL,'按源文件总量预留下载原文件、压缩包和原子发布临时副本的峰值空间，范围100至500。',NULL
    UNION ALL SELECT 'storage.threshold.hot.percent','热备容量告警阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','85',NULL,'热备或NAS使用率达到该值时告警。',NULL
    UNION ALL SELECT 'storage.threshold.cold.percent','冷备容量告警阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','90',NULL,'冷备目标盘使用率达到该值时告警。',NULL
    UNION ALL SELECT 'storage.volume-switch.enabled','存储卷自动切换开关','STORAGE_MANAGEMENT','存储与容量','BOOLEAN','false','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','仅从已纳管 HOT STANDBY 卷中自动切换。',NULL
    UNION ALL SELECT 'storage.volume-switch.threshold-percent','存储卷切换阈值（%）','STORAGE_MANAGEMENT','存储与容量','INTEGER','90',NULL,'当前 ACTIVE 卷使用率达到该值时建议切换。',NULL
    UNION ALL SELECT 'storage.volume-health.interval-minutes','存储卷健康检查周期（分钟）','STORAGE_MANAGEMENT','存储与容量','INTEGER','5',NULL,'定期探测已纳管卷的可访问性和容量。',NULL
    UNION ALL SELECT 'cold.max-import-bytes','最大导入解压量（字节）','COLD_ARCHIVE','冷备归档与导入','LONG','4398046511104',NULL,'单个冷备导入任务允许解压的总量，默认4TiB。',NULL
    UNION ALL SELECT 'restore.enabled','线上恢复开关','ONLINE_RESTORE','线上恢复策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制管理员能否创建线上恢复任务。',NULL
    UNION ALL SELECT 'restore.conflict-policy','恢复冲突策略','ONLINE_RESTORE','线上恢复策略','ENUM','REJECT','[{"label":"拒绝覆盖（推荐）","value":"REJECT"},{"label":"管理员确认后覆盖","value":"CONFIRM_OVERWRITE"},{"label":"直接强制覆盖","value":"FORCE_OVERWRITE"}]','线上订单文件或关联关系已变化时的处理方式。默认拒绝覆盖；强制覆盖可能回填历史文件记录，需由管理员审慎使用。',NULL
    UNION ALL SELECT 'restore.retry-count','恢复失败重试次数','ONLINE_RESTORE','线上恢复策略','INTEGER','3',NULL,'单订单恢复失败后的自动重试次数，范围0至10。',NULL
    UNION ALL SELECT 'cleanup.schedule.enabled','COS自动清理开关','CLOUD_CLEANUP','云端清理策略','BOOLEAN','false','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','启用后按周期自动创建并执行COS清理任务。',NULL
    UNION ALL SELECT 'cleanup.schedule.interval-hours','COS自动清理周期（小时）','CLOUD_CLEANUP','云端清理策略','INTEGER','24',NULL,'自动扫描及执行COS清理的周期。',NULL
    UNION ALL SELECT 'cleanup.retention-days','COS保留天数','CLOUD_CLEANUP','云端清理策略','INTEGER','180',NULL,'按订单创建时间计算允许清理范围。',NULL
    UNION ALL SELECT 'cleanup.batch-size','单批清理订单上限','CLOUD_CLEANUP','云端清理策略','INTEGER','100',NULL,'单个清理任务最多处理的订单数量，范围1至500。',NULL
    UNION ALL SELECT 'supplement.allowed-extensions','允许补传扩展名','SUPPLEMENT_FILE','补传文件规则','STRING_LIST','zip,step,stl,dcm',NULL,'允许补传到本地的工程文件扩展名。',NULL
    UNION ALL SELECT 'supplement.max-file-size-bytes','补传单文件大小上限（字节）','SUPPLEMENT_FILE','补传文件规则','LONG','1073741824',NULL,'业务层单个补传文件大小上限，不得超过HTTP硬上限。',NULL
    UNION ALL SELECT 'account.sync-enabled','账号同步开关','ACCOUNT_SYNC','账号同步策略','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制是否从线上定时同步账号资料。',NULL
    UNION ALL SELECT 'account.sync-delay-minutes','账号同步周期（分钟）','ACCOUNT_SYNC','账号同步策略','INTEGER','30',NULL,'线上账号同步间隔。',NULL
    UNION ALL SELECT 'account.sync-page-size','账号同步分页大小','ACCOUNT_SYNC','账号同步策略','INTEGER','200',NULL,'单次读取线上账号数量，范围1至500。',NULL
    UNION ALL SELECT 'online.enabled','线上通信总开关','ONLINE_INTEGRATION','线上系统连接','BOOLEAN','true','[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]','控制候选、账号、恢复和清理等所有线上通信。',NULL
    UNION ALL SELECT 'online.base-url','线上系统地址','ONLINE_INTEGRATION','线上系统连接','URL','http://localhost:8081/api',NULL,'线上医工宝内部接口HTTP基础地址，必须包含服务 context-path，例如 http://localhost:8081/api；启用时必填。',NULL
    UNION ALL SELECT 'online.connect-timeout-seconds','线上连接超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','10',NULL,'HTTP建连超时，范围1至300秒。',NULL
    UNION ALL SELECT 'online.request-timeout-seconds','线上请求超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','60',NULL,'普通线上接口请求超时，范围1至3600秒。',NULL
    UNION ALL SELECT 'online.file-transfer-timeout-seconds','线上文件传输超时（秒）','ONLINE_INTEGRATION','线上系统连接','INTEGER','7200',NULL,'备份下载和恢复上传等大文件流式传输超时，范围60至86400秒。',NULL
    UNION ALL SELECT 'storage.read-lease.ttl-seconds','存储读取租约时长（秒）','STORAGE','存储管理','INTEGER','600',NULL,'下载和恢复读取卷时的租约时长，必须大于两倍心跳间隔。',NULL
    UNION ALL SELECT 'storage.read-lease.heartbeat-seconds','存储读取租约心跳（秒）','STORAGE','存储管理','INTEGER','30',NULL,'长文件下载期间续租间隔。',NULL
    UNION ALL SELECT 'job.dispatch-interval-ms','任务派发间隔（毫秒）','JOB_EXECUTION','任务执行策略','INTEGER','1000',NULL,'扫描并领取待执行持久化任务的间隔，最小200毫秒。',NULL
    UNION ALL SELECT 'job.lease-seconds','任务租约时长（秒）','JOB_EXECUTION','任务执行策略','INTEGER','60',NULL,'执行器持有任务的租约时长，最小10秒。',NULL
    UNION ALL SELECT 'job.recovery-interval-ms','租约回收间隔（毫秒）','JOB_EXECUTION','任务执行策略','INTEGER','60000',NULL,'回收异常中断过期租约的间隔，最小1000毫秒。',NULL
    UNION ALL SELECT 'job.max-retry-count','任务最大重试次数','JOB_EXECUTION','任务执行策略','INTEGER','3',NULL,'非恢复专属任务的最大自动重试次数，范围0至10。',NULL
) v
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config c
    WHERE c.config_key = v.config_key AND c.scope = 'GLOBAL' AND c.is_deleted = 0
);
-- ============================================================================
-- END SOURCE: sql/init/04-functional-config-defaults.sql
-- ============================================================================


-- ============================================================================
-- FINAL VERIFICATION
-- ============================================================================
-- 存量环境重复执行全量初始化脚本时，CREATE TABLE IF NOT EXISTS 不会补列，因此在校验前补齐模拟备份模式字段。
DROP PROCEDURE IF EXISTS add_simulation_mode_column;
DELIMITER $$
CREATE PROCEDURE add_simulation_mode_column(IN p_table VARCHAR(64))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = 'backup_mode'
  ) THEN
    SET @simulation_mode_ddl = CONCAT('ALTER TABLE `', p_table,
      '` ADD COLUMN backup_mode VARCHAR(32) NOT NULL DEFAULT ''REAL'' COMMENT ''REAL/METADATA_SIMULATION'' AFTER id');
    PREPARE simulation_mode_stmt FROM @simulation_mode_ddl;
    EXECUTE simulation_mode_stmt;
    DEALLOCATE PREPARE simulation_mode_stmt;
  END IF;
END$$
DELIMITER ;
CALL add_simulation_mode_column('bk_order_snapshot');
CALL add_simulation_mode_column('bk_order_package');
DROP PROCEDURE IF EXISTS add_simulation_mode_column;

-- 存量环境重复执行全量初始化脚本时补齐降级备份快照字段；不补造历史关系摘要。
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
CALL add_degraded_snapshot_column('backup_completeness','backup_completeness VARCHAR(32) NULL AFTER backup_mode');
CALL add_degraded_snapshot_column('manifest_schema_version','manifest_schema_version INT NULL AFTER manifest_sha256');
CALL add_degraded_snapshot_column('relation_gap_count','relation_gap_count INT NULL AFTER manifest_schema_version');
CALL add_degraded_snapshot_column('relation_gap_hash','relation_gap_hash CHAR(64) NULL AFTER relation_gap_count');
CALL add_degraded_snapshot_column('relationship_state_hash','relationship_state_hash CHAR(64) NULL AFTER relation_gap_hash');
DROP PROCEDURE IF EXISTS add_degraded_snapshot_column;
UPDATE bk_order_snapshot
SET backup_completeness=CASE WHEN backup_mode='METADATA_SIMULATION' THEN 'NOT_APPLICABLE' ELSE 'COMPLETE' END,
    manifest_schema_version=COALESCE(manifest_schema_version,1),
    relation_gap_count=COALESCE(relation_gap_count,0)
WHERE backup_completeness IS NULL OR manifest_schema_version IS NULL OR relation_gap_count IS NULL;
ALTER TABLE bk_order_snapshot
  MODIFY COLUMN backup_completeness VARCHAR(32) NOT NULL DEFAULT 'COMPLETE',
  MODIFY COLUMN manifest_schema_version INT NOT NULL DEFAULT 1,
  MODIFY COLUMN relation_gap_count INT NOT NULL DEFAULT 0;

SELECT DATABASE() AS initialized_database;
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';
SELECT COUNT(*) AS active_config_count
FROM bk_config
WHERE scope = 'GLOBAL' AND is_deleted = 0;
SELECT username, role_code, enabled_status, emergency_admin
FROM bk_user
WHERE username = 'admin1' AND is_deleted = 0;
