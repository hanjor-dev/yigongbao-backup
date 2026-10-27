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

CALL add_backup_index_if_missing('bk_asset','uk_bk_asset_identity','CREATE UNIQUE INDEX uk_bk_asset_identity ON bk_asset(snapshot_id,asset_identity)');
CALL add_backup_index_if_missing('bk_asset','idx_bk_asset_file','CREATE INDEX idx_bk_asset_file ON bk_asset(source_file_id)');
CALL add_backup_index_if_missing('bk_asset','idx_bk_asset_type','CREATE INDEX idx_bk_asset_type ON bk_asset(asset_type,backup_status)');
CALL add_backup_index_if_missing('bk_asset_relation','uk_bk_asset_relation','CREATE UNIQUE INDEX uk_bk_asset_relation ON bk_asset_relation(asset_id,relation_kind,source_record_id)');
CALL add_backup_index_if_missing('bk_asset_relation','idx_bk_asset_relation_hash','CREATE INDEX idx_bk_asset_relation_hash ON bk_asset_relation(relation_hash)');
CALL add_backup_index_if_missing('bk_order_package','uk_bk_package_snapshot','CREATE UNIQUE INDEX uk_bk_package_snapshot ON bk_order_package(snapshot_id)');
CALL add_backup_index_if_missing('bk_order_package','uk_bk_package_storage','CREATE UNIQUE INDEX uk_bk_package_storage ON bk_order_package(storage_key(191))');
CALL add_backup_index_if_missing('bk_order_package','idx_bk_package_status','CREATE INDEX idx_bk_package_status ON bk_order_package(status,verified_at)');
CALL add_backup_index_if_missing('bk_supplement_file','uk_bk_supplement_storage','CREATE UNIQUE INDEX uk_bk_supplement_storage ON bk_supplement_file(storage_key(191))');
CALL add_backup_index_if_missing('bk_supplement_file','idx_bk_supplement_snapshot','CREATE INDEX idx_bk_supplement_snapshot ON bk_supplement_file(snapshot_id,uploaded_at)');
CALL add_backup_index_if_missing('bk_artifact_copy','uk_bk_artifact_location','CREATE UNIQUE INDEX uk_bk_artifact_location ON bk_artifact_copy(artifact_type,artifact_id,location_type,location_ref_id)');
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

CALL add_backup_index_if_missing('bk_job','uk_bk_job_no','CREATE UNIQUE INDEX uk_bk_job_no ON bk_job(job_no)');
CALL add_backup_index_if_missing('bk_job','uk_bk_job_idempotency','CREATE UNIQUE INDEX uk_bk_job_idempotency ON bk_job(idempotency_key)');
CALL add_backup_index_if_missing('bk_job','idx_bk_job_claim','CREATE INDEX idx_bk_job_claim ON bk_job(status,next_retry_at,priority,lease_until)');
CALL add_backup_index_if_missing('bk_job','idx_bk_job_lease','CREATE INDEX idx_bk_job_lease ON bk_job(lease_until,heartbeat_at)');
CALL add_backup_index_if_missing('bk_job_order','uk_bk_job_order','CREATE UNIQUE INDEX uk_bk_job_order ON bk_job_order(job_id,source_order_id)');
CALL add_backup_index_if_missing('bk_job_order','idx_bk_job_order_snapshot','CREATE INDEX idx_bk_job_order_snapshot ON bk_job_order(snapshot_id)');
CALL add_backup_index_if_missing('bk_job_file','uk_bk_job_file_step','CREATE UNIQUE INDEX uk_bk_job_file_step ON bk_job_file(job_id,asset_identity,step)');
CALL add_backup_index_if_missing('bk_job_file','idx_bk_job_file_status','CREATE INDEX idx_bk_job_file_status ON bk_job_file(job_id,status,id)');
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
