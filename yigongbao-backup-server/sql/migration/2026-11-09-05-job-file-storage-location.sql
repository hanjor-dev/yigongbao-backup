-- 任务文件级证据必须记录卷内位置，供任务详情在卷切换、离线后仍可追溯。
ALTER TABLE bk_job_file
    ADD COLUMN storage_volume_id BIGINT NULL COMMENT '文件所在或来源存储卷ID' AFTER source_order_id,
    ADD COLUMN storage_key VARCHAR(1024) NULL COMMENT '卷内相对路径' AFTER storage_volume_id,
    ADD COLUMN storage_volume_name VARCHAR(128) NULL COMMENT '任务发生时的卷名称快照' AFTER storage_key,
    ADD COLUMN storage_tier VARCHAR(16) NULL COMMENT '任务发生时的卷层级快照' AFTER storage_volume_name;

CREATE INDEX idx_bk_job_file_storage_volume ON bk_job_file(storage_volume_id, job_id);
