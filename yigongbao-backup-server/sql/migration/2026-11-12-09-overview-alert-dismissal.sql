-- 管理概览活动告警手动关闭记录；异常恢复后由服务物理清理，使未来复发重新提醒。
CREATE TABLE IF NOT EXISTS bk_overview_alert_dismissal (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
 alert_key VARCHAR(191) NOT NULL COMMENT '稳定告警键',
 alert_code VARCHAR(64) NOT NULL COMMENT '告警代码',
 severity VARCHAR(16) NOT NULL COMMENT 'CRITICAL/WARNING/INFO',
 title VARCHAR(255) NOT NULL COMMENT '关闭时告警标题快照',
 dismissed_by BIGINT NOT NULL COMMENT '关闭管理员ID',
 dismissed_at DATETIME(3) NOT NULL COMMENT '关闭时间',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uk_bk_overview_alert_dismissal_key (alert_key),
 KEY idx_bk_overview_alert_dismissal_time (dismissed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理概览当前告警关闭记录';

