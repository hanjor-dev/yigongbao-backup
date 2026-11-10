-- 备份与恢复的订单级明细固化产物所在卷，不再从当前 ACTIVE 卷反推。
ALTER TABLE bk_job_order
    ADD COLUMN storage_volume_id BIGINT NULL COMMENT '订单产物所在卷ID' AFTER source_package_sha256,
    ADD COLUMN storage_key VARCHAR(1024) NULL COMMENT '卷内相对位置' AFTER storage_volume_id,
    ADD COLUMN storage_volume_name VARCHAR(128) NULL COMMENT '卷名称快照' AFTER storage_key,
    ADD COLUMN storage_tier VARCHAR(16) NULL COMMENT '卷层级快照' AFTER storage_volume_name;

-- 恢复任务已经固化源卷的，直接复用不可变源位置。
UPDATE bk_job_order jo
JOIN bk_storage_volume v ON v.id = jo.source_storage_volume_id AND v.is_deleted = 0
SET jo.storage_volume_id = jo.source_storage_volume_id,
    jo.storage_key = jo.source_storage_key,
    jo.storage_volume_name = v.display_name,
    jo.storage_tier = v.tier
WHERE jo.storage_volume_id IS NULL AND jo.source_storage_volume_id IS NOT NULL;

-- 已有关联快照的备份任务，从该快照最新可用订单包回填真实位置。
UPDATE bk_job_order jo
JOIN bk_order_package p ON p.snapshot_id = jo.snapshot_id AND p.is_deleted = 0
    AND p.id = (SELECT MAX(p2.id) FROM bk_order_package p2
                WHERE p2.snapshot_id = jo.snapshot_id AND p2.status = 'AVAILABLE' AND p2.is_deleted = 0)
JOIN bk_storage_volume v ON v.id = p.storage_volume_id AND v.is_deleted = 0
SET jo.storage_volume_id = p.storage_volume_id,
    jo.storage_key = p.storage_key,
    jo.storage_volume_name = v.display_name,
    jo.storage_tier = v.tier
WHERE jo.storage_volume_id IS NULL AND p.status = 'AVAILABLE';

-- 尚无快照位置但任务已锁定目标卷的历史明细，至少回填卷身份。
UPDATE bk_job_order jo
JOIN bk_job j ON j.id = jo.job_id AND j.is_deleted = 0
JOIN bk_storage_volume v ON v.id = j.target_storage_volume_id AND v.is_deleted = 0
SET jo.storage_volume_id = j.target_storage_volume_id,
    jo.storage_volume_name = v.display_name,
    jo.storage_tier = v.tier
WHERE jo.storage_volume_id IS NULL AND j.target_storage_volume_id IS NOT NULL;

CREATE INDEX idx_bk_job_order_storage_volume ON bk_job_order(storage_volume_id, job_id);
