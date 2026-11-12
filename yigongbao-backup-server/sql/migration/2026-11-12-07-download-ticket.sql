-- 短时单次下载票据：原始令牌只返回浏览器，数据库仅保存 SHA-256 摘要。
CREATE TABLE IF NOT EXISTS bk_download_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  token_hash CHAR(64) NOT NULL COMMENT '下载令牌SHA-256摘要',
  actor_user_id BIGINT NOT NULL COMMENT '签发用户ID',
  actor_role_code VARCHAR(32) NOT NULL COMMENT '签发用户角色',
  artifact_type VARCHAR(32) NOT NULL COMMENT 'ORDER_PACKAGE/SUPPLEMENT_FILE',
  artifact_id BIGINT NOT NULL COMMENT '下载对象ID',
  download_name VARCHAR(255) NOT NULL COMMENT '下载文件名',
  status VARCHAR(16) NOT NULL COMMENT 'ISSUED/CONSUMED',
  expires_at DATETIME(3) NOT NULL COMMENT '过期时间',
  consumed_at DATETIME(3) NULL COMMENT '消费时间',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  create_by BIGINT NULL, update_by BIGINT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_download_ticket_hash (token_hash),
  KEY idx_download_ticket_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短时单次下载票据';
