-- 任务订单明细补充业务展示字段，使任务历史无需依赖线上系统即可查看订单信息。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE bk_job_order
    ADD COLUMN order_code VARCHAR(128) NULL COMMENT '订单编号' AFTER snapshot_id,
    ADD COLUMN order_created_at VARCHAR(64) NULL COMMENT '订单创建时间' AFTER order_code,
    ADD COLUMN source_status VARCHAR(32) NULL COMMENT '线上业务状态' AFTER order_created_at,
    ADD COLUMN order_business_json JSON NULL COMMENT '完整订单业务快照' AFTER source_status;

-- 回填已经成功生成本地订单快照的历史任务明细。
UPDATE bk_job_order jo
JOIN bk_order_snapshot os ON os.source_order_id = jo.source_order_id AND os.is_deleted = 0
SET jo.snapshot_id = os.id,
    jo.order_code = os.order_code,
    jo.order_created_at = CAST(os.order_created_at AS CHAR),
    jo.source_status = os.source_status,
    jo.order_business_json = os.order_raw_json
WHERE jo.is_deleted = 0;
