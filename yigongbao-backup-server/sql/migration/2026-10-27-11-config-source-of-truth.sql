-- 将过渡期配置迁移到最终“唯一来源 + 能力域分类”模型。
-- 前置：已执行 2026-10-27-10-backup-functional-config.sql。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 先补充最终方案新增的配置；不覆盖任何已存在的现场值。
INSERT INTO bk_config
    (config_key, scope, value_type, config_value, config_version, changed_at, create_time, update_time)
SELECT v.config_key, 'GLOBAL', v.value_type, v.config_value, 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM (
    SELECT 'storage.hot-root' config_key, 'PATH' value_type, './data' config_value
    UNION ALL SELECT 'storage.managed-roots','PATH_LIST','./data,./cold-disks'
    UNION ALL SELECT 'cold.import-root','PATH','./cold-import'
    UNION ALL SELECT 'cleanup.schedule.enabled','BOOLEAN','false'
    UNION ALL SELECT 'cleanup.schedule.interval-hours','INTEGER','24'
    UNION ALL SELECT 'cleanup.batch-size','INTEGER','100'
    UNION ALL SELECT 'online.enabled','BOOLEAN','false'
    UNION ALL SELECT 'online.base-url','URL',''
    UNION ALL SELECT 'online.auth-mode','ENUM','HMAC'
    UNION ALL SELECT 'online.key-id','STRING',''
    UNION ALL SELECT 'online.hmac-secret-ref','SECRET_REF','BACKUP_HMAC_KEY_SECRET'
    UNION ALL SELECT 'online.bearer-token-ref','SECRET_REF','BACKUP_ONLINE_BEARER_TOKEN'
    UNION ALL SELECT 'online.connect-timeout-seconds','INTEGER','10'
    UNION ALL SELECT 'online.request-timeout-seconds','INTEGER','60'
    UNION ALL SELECT 'online.scope','STRING','BACKUP'
    UNION ALL SELECT 'job.dispatch-interval-ms','INTEGER','1000'
    UNION ALL SELECT 'job.recovery-interval-ms','INTEGER','60000'
) v
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config c
    WHERE c.config_key = v.config_key AND c.scope = 'GLOBAL' AND c.is_deleted = 0
);

-- 恢复冲突枚举从含混的“本地”命名迁移为明确的“线上”命名。
UPDATE bk_config SET config_value = CASE config_value
    WHEN 'KEEP_LOCAL' THEN 'KEEP_ONLINE'
    WHEN 'OVERWRITE_LOCAL' THEN 'OVERWRITE_ONLINE'
    ELSE config_value END
WHERE config_key = 'restore.conflict-policy' AND scope = 'GLOBAL' AND is_deleted = 0;

-- 已确认且不可切换的业务规则不再作为配置项；保留历史行并逻辑删除。
UPDATE bk_config SET is_deleted = 1, update_time = CURRENT_TIMESTAMP(3)
WHERE scope = 'GLOBAL' AND is_deleted = 0 AND config_key IN (
    'backup.order.exclude-statuses',
    'backup.order.skip-file-changes-after-backup',
    'backup.order.classic-case-protection',
    'restore.skip-online-deleted',
    'restore.require-file-complete',
    'cleanup.enabled',
    'cleanup.dry-run-required',
    'cleanup.require-admin-confirm',
    'cleanup.skip-classic-case',
    'cleanup.allow-online-deleted-order',
    'supplement.allow-delete',
    'backup.candidate.lookback-seconds',
    'storage.reserve-percent',
    'cold.require-manifest',
    'cold.require-sha256',
    'job.stale-timeout-minutes'
);

-- 新分类是元数据变更，可以覆盖旧分类，但不得改变现场配置值。
UPDATE bk_config
SET category_code = CASE
        WHEN config_key LIKE 'backup.%' THEN 'BACKUP_COLLECTION'
        WHEN config_key LIKE 'storage.%' THEN 'STORAGE_MANAGEMENT'
        WHEN config_key LIKE 'cold.%' THEN 'COLD_ARCHIVE'
        WHEN config_key LIKE 'restore.%' THEN 'ONLINE_RESTORE'
        WHEN config_key LIKE 'cleanup.%' THEN 'CLOUD_CLEANUP'
        WHEN config_key LIKE 'supplement.%' THEN 'SUPPLEMENT_FILE'
        WHEN config_key LIKE 'account.%' THEN 'ACCOUNT_SYNC'
        WHEN config_key LIKE 'online.%' THEN 'ONLINE_INTEGRATION'
        ELSE 'JOB_EXECUTION' END,
    category_name = CASE
        WHEN config_key LIKE 'backup.%' THEN '备份采集策略'
        WHEN config_key LIKE 'storage.%' THEN '存储与容量'
        WHEN config_key LIKE 'cold.%' THEN '冷备归档与导入'
        WHEN config_key LIKE 'restore.%' THEN '线上恢复策略'
        WHEN config_key LIKE 'cleanup.%' THEN '云端清理策略'
        WHEN config_key LIKE 'supplement.%' THEN '补传文件规则'
        WHEN config_key LIKE 'account.%' THEN '账号同步策略'
        WHEN config_key LIKE 'online.%' THEN '线上系统连接'
        ELSE '任务执行策略' END
WHERE scope = 'GLOBAL' AND is_deleted = 0;

UPDATE bk_config
SET options_json = '[{"label":"保留线上文件","value":"KEEP_ONLINE"},{"label":"备份覆盖线上","value":"OVERWRITE_ONLINE"},{"label":"保留较新版本","value":"KEEP_NEWEST"}]',
    config_name = '恢复冲突策略',
    description = '线上存在较新文件时的处理策略。'
WHERE config_key = 'restore.conflict-policy' AND scope = 'GLOBAL' AND is_deleted = 0;

UPDATE bk_config SET secret_ref = config_value
WHERE config_key IN ('online.hmac-secret-ref','online.bearer-token-ref')
  AND scope = 'GLOBAL' AND is_deleted = 0;

UPDATE bk_config
SET options_json = '[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]'
WHERE value_type = 'BOOLEAN' AND scope = 'GLOBAL' AND is_deleted = 0
  AND (options_json IS NULL OR JSON_LENGTH(options_json) = 0);

UPDATE bk_config
SET options_json = '[{"label":"无认证","value":"NONE"},{"label":"HMAC","value":"HMAC"},{"label":"Bearer","value":"BEARER"}]'
WHERE config_key = 'online.auth-mode' AND scope = 'GLOBAL' AND is_deleted = 0;

-- 若生产库不能重新执行初始化脚本，可用以下规则补齐可读名称；值不会被修改。
UPDATE bk_config SET config_name = CASE config_key
    WHEN 'storage.hot-root' THEN '热备存储根目录'
    WHEN 'storage.managed-roots' THEN '受控存储根目录'
    WHEN 'cold.import-root' THEN '冷备导入区目录'
    WHEN 'cleanup.schedule.enabled' THEN 'COS自动清理开关'
    WHEN 'cleanup.schedule.interval-hours' THEN 'COS自动清理周期（小时）'
    WHEN 'cleanup.batch-size' THEN '单批清理订单上限'
    WHEN 'online.enabled' THEN '线上通信总开关'
    WHEN 'online.base-url' THEN '线上系统地址'
    WHEN 'online.auth-mode' THEN '线上认证模式'
    WHEN 'online.key-id' THEN 'HMAC Key ID'
    WHEN 'online.hmac-secret-ref' THEN 'HMAC密钥引用'
    WHEN 'online.bearer-token-ref' THEN 'Bearer Token引用'
    WHEN 'online.connect-timeout-seconds' THEN '线上连接超时（秒）'
    WHEN 'online.request-timeout-seconds' THEN '线上请求超时（秒）'
    WHEN 'online.scope' THEN '线上接口作用域'
    WHEN 'job.dispatch-interval-ms' THEN '任务派发间隔（毫秒）'
    WHEN 'job.recovery-interval-ms' THEN '租约回收间隔（毫秒）'
    ELSE COALESCE(config_name, config_key) END,
    description = COALESCE(description, CONCAT('备份子系统运行配置：', config_key))
WHERE scope = 'GLOBAL' AND is_deleted = 0;
