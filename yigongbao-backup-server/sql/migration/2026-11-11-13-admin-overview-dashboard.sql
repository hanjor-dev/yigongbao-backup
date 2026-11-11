-- 管理概览：首页容量成功观测时间与聚合索引。
ALTER TABLE bk_storage_volume
    ADD COLUMN capacity_observed_at DATETIME(3) NULL COMMENT '最近一次合法成功容量快照时间' AFTER last_probed_at;

UPDATE bk_storage_volume
SET capacity_observed_at = last_probed_at
WHERE capacity_observed_at IS NULL
  AND last_probed_at IS NOT NULL
  AND total_bytes > 0
  AND usable_bytes >= 0
  AND usable_bytes <= total_bytes;

CREATE INDEX idx_bk_job_overview
    ON bk_job(job_type, finished_at, id, is_deleted);
CREATE INDEX idx_bk_job_order_overview
    ON bk_job_order(source_order_id, status, job_id, is_deleted);
CREATE INDEX idx_bk_snapshot_overview
    ON bk_order_snapshot(backup_mode, snapshot_status, backup_completed_at, source_order_id, is_deleted);
CREATE INDEX idx_bk_package_overview
    ON bk_order_package(backup_mode, status, snapshot_id, is_deleted);
CREATE INDEX idx_bk_artifact_overview
    ON bk_artifact_copy(artifact_type, lifecycle, state, artifact_id, storage_volume_id, is_deleted);
CREATE INDEX idx_bk_volume_archive_overview
    ON bk_volume_archive(phase, update_time, source_volume_id, is_deleted);
