CREATE INDEX idx_bk_job_order_failed_retry
    ON bk_job_order(status,is_deleted,id,source_order_id);

CREATE INDEX idx_bk_audit_cursor
    ON bk_audit_log(is_deleted,occurred_at,id);
