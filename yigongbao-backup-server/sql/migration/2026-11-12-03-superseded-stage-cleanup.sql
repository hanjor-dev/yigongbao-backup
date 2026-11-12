ALTER TABLE bk_backup_stage
    ADD COLUMN cleanup_status VARCHAR(32) NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED' AFTER superseded_at,
    ADD COLUMN cleanup_retry_count INT NOT NULL DEFAULT 0 AFTER cleanup_status,
    ADD COLUMN cleanup_next_at DATETIME(3) NULL AFTER cleanup_retry_count,
    ADD COLUMN cleanup_error VARCHAR(1024) NULL AFTER cleanup_next_at,
    ADD COLUMN cleaned_at DATETIME(3) NULL AFTER cleanup_error;

UPDATE bk_backup_stage
SET cleanup_status='PENDING', cleanup_next_at=CURRENT_TIMESTAMP(3)
WHERE stage_status='SUPERSEDED' AND cleanup_status IS NULL AND is_deleted=0;

CREATE INDEX idx_bk_backup_stage_cleanup
    ON bk_backup_stage(stage_status, cleanup_status, cleanup_next_at, id);
