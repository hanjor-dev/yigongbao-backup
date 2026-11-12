ALTER TABLE bk_job_order
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '订单级已消耗重试次数' AFTER status;
