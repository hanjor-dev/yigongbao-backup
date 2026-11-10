-- 持久化最近一次健康探测容量，避免管理列表同步访问慢速或离线卷。
ALTER TABLE bk_storage_volume
    ADD COLUMN total_bytes BIGINT NULL COMMENT '最近探测总容量' AFTER last_error_message,
    ADD COLUMN usable_bytes BIGINT NULL COMMENT '最近探测可用容量' AFTER total_bytes;
