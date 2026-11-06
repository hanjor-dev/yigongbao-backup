-- 已部署环境：为恢复任务增加管理员可查询的任务级、订单级执行日志。
-- 仅新增表和索引，不修改既有任务、订单、文件证据数据。

CREATE TABLE IF NOT EXISTS bk_job_execution_log (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID', job_id BIGINT NOT NULL COMMENT '任务ID', source_order_id BIGINT NULL COMMENT '线上订单ID；空表示任务级日志', log_level VARCHAR(16) NOT NULL COMMENT 'INFO/WARN/ERROR', stage VARCHAR(64) NOT NULL COMMENT '可读执行阶段', event_code VARCHAR(64) NOT NULL COMMENT '稳定事件编码', message TEXT NOT NULL COMMENT '中文日志正文（完整保留）', detail_json JSON NULL COMMENT '已脱敏结构化明细', occurred_at DATETIME(3) NOT NULL COMMENT '事件实际发生时间', version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间', update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间', create_by BIGINT NULL COMMENT '创建人ID', update_by BIGINT NULL COMMENT '更新人ID', is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；一期不删除', PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务执行过程日志';

DELIMITER $$
DROP PROCEDURE IF EXISTS add_job_execution_log_index_if_missing$$
CREATE PROCEDURE add_job_execution_log_index_if_missing(IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='bk_job_execution_log' AND index_name=p_index) THEN
    SET @backup_index_ddl=p_ddl;
    PREPARE backup_index_stmt FROM @backup_index_ddl;
    EXECUTE backup_index_stmt;
    DEALLOCATE PREPARE backup_index_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_job_execution_log_index_if_missing('idx_bk_job_log_task_time','CREATE INDEX idx_bk_job_log_task_time ON bk_job_execution_log(job_id,occurred_at,id)');
CALL add_job_execution_log_index_if_missing('idx_bk_job_log_order_time','CREATE INDEX idx_bk_job_log_order_time ON bk_job_execution_log(job_id,source_order_id,occurred_at,id)');
DROP PROCEDURE IF EXISTS add_job_execution_log_index_if_missing;

-- 兼容已先执行过旧版建表脚本的环境：将正文由 VARCHAR 升级为 TEXT，审计内容不截断。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_job_execution_log_message_to_text$$
CREATE PROCEDURE upgrade_job_execution_log_message_to_text()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='bk_job_execution_log' AND column_name='message' AND data_type <> 'text') THEN
    ALTER TABLE bk_job_execution_log MODIFY COLUMN message TEXT NOT NULL COMMENT '中文日志正文（完整保留）';
  END IF;
END$$
DELIMITER ;
CALL upgrade_job_execution_log_message_to_text();
DROP PROCEDURE IF EXISTS upgrade_job_execution_log_message_to_text;
