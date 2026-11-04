-- 修复空间预留活动唯一键：非活动历史必须为 NULL，允许同一任务反复预留和释放。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE bk_space_reservation
  DROP INDEX uk_bk_space_active,
  DROP COLUMN status_active;

ALTER TABLE bk_space_reservation
  ADD COLUMN status_active TINYINT
    GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' AND is_deleted=0 THEN 1 ELSE NULL END) STORED
    COMMENT '活动状态唯一键；非活动记录为NULL以保留多条历史'
    AFTER status,
  ADD UNIQUE INDEX uk_bk_space_active(storage_pool_id, job_id, status_active);
