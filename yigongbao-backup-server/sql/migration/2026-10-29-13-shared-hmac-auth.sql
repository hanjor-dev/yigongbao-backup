-- 简化本地备份系统与线上医工宝的认证配置。
-- 真实密钥固定从双方环境变量 BACKUP_SHARED_HMAC_SECRET 读取，不进入数据库。

UPDATE bk_config
SET is_deleted = 1,
    update_time = CURRENT_TIMESTAMP
WHERE scope = 'GLOBAL'
  AND is_deleted = 0
  AND config_key IN (
      'online.auth-mode',
      'online.key-id',
      'online.hmac-secret-ref',
      'online.bearer-token-ref',
      'online.scope'
  );

-- 迁移后应返回 0。
SELECT COUNT(*) AS obsolete_online_auth_config_count
FROM bk_config
WHERE scope = 'GLOBAL'
  AND is_deleted = 0
  AND config_key IN (
      'online.auth-mode',
      'online.key-id',
      'online.hmac-secret-ref',
      'online.bearer-token-ref',
      'online.scope'
  );
