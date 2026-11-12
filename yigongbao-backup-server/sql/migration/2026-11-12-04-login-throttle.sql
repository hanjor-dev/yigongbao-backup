CREATE TABLE IF NOT EXISTS bk_login_throttle (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
 bucket_key CHAR(64) NOT NULL COMMENT '用户名或来源地址不可逆摘要',
 scope_type VARCHAR(16) NOT NULL COMMENT 'IDENTITY/IP',
 failure_count INT NOT NULL DEFAULT 0,
 window_started_at DATETIME(3) NOT NULL,
 blocked_until DATETIME(3) NULL,
 version INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 create_by BIGINT NULL, update_by BIGINT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_bk_login_throttle_bucket (bucket_key),
 KEY idx_bk_login_throttle_expiry (blocked_until,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨实例登录失败限流桶';
