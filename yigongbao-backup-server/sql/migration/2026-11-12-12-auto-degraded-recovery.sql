ALTER TABLE bk_job_order
    ADD COLUMN degrade_phase VARCHAR(32) NULL COMMENT '自动降级有界状态机阶段' AFTER retry_count;

DROP PROCEDURE IF EXISTS migrate_backup_stage_job_order_index;
DELIMITER $$
CREATE PROCEDURE migrate_backup_stage_job_order_index()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'bk_backup_stage'
                 AND index_name = 'uk_bk_backup_stage_job_order') THEN
        ALTER TABLE bk_backup_stage DROP INDEX uk_bk_backup_stage_job_order;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'bk_backup_stage'
                     AND index_name = 'idx_bk_backup_stage_job_order') THEN
        CREATE INDEX idx_bk_backup_stage_job_order ON bk_backup_stage(job_id, source_order_id, id);
    END IF;
END$$
DELIMITER ;
CALL migrate_backup_stage_job_order_index();
DROP PROCEDURE migrate_backup_stage_job_order_index;
