-- 生产环境补充备份业务配置默认值和配置元数据；可重复执行，不覆盖已有配置。
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS add_backup_config_metadata_if_missing$$
CREATE PROCEDURE add_backup_config_metadata_if_missing()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_config' AND column_name='config_name') THEN
    ALTER TABLE bk_config ADD COLUMN config_name VARCHAR(128) NULL COMMENT '配置名称' AFTER config_key;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_config' AND column_name='options_json') THEN
    ALTER TABLE bk_config ADD COLUMN options_json JSON NULL COMMENT '配置可选项JSON' AFTER config_value;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_config' AND column_name='description') THEN
    ALTER TABLE bk_config ADD COLUMN description VARCHAR(1024) NULL COMMENT '配置说明及影响' AFTER options_json;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_config' AND column_name='category_code') THEN
    ALTER TABLE bk_config ADD COLUMN category_code VARCHAR(32) NULL COMMENT '配置分类编码' AFTER config_name;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bk_config' AND column_name='category_name') THEN
    ALTER TABLE bk_config ADD COLUMN category_name VARCHAR(64) NULL COMMENT '配置分类名称' AFTER category_code;
  END IF;
END$$
DELIMITER ;
CALL add_backup_config_metadata_if_missing();
DROP PROCEDURE IF EXISTS add_backup_config_metadata_if_missing;

INSERT INTO bk_config (config_key, scope, value_type, config_value, config_version, changed_at, create_time, update_time)
SELECT v.config_key, 'GLOBAL', v.value_type, v.config_value, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM (
 SELECT 'backup.schedule.enabled' config_key,'BOOLEAN' value_type,'true' config_value UNION ALL
 SELECT 'backup.schedule.interval-minutes','INTEGER','30' UNION ALL SELECT 'backup.schedule.reconcile-interval-hours','INTEGER','24' UNION ALL
 SELECT 'backup.candidate.page-size','INTEGER','100' UNION ALL SELECT 'backup.candidate.lookback-seconds','INTEGER','300' UNION ALL
 SELECT 'backup.order.exclude-statuses','STRING','DRAFT,DELETED,CANCELLED' UNION ALL SELECT 'backup.order.skip-file-changes-after-backup','BOOLEAN','true' UNION ALL
 SELECT 'backup.order.classic-case-protection','BOOLEAN','true' UNION ALL SELECT 'storage.threshold.hot.percent','INTEGER','85' UNION ALL
 SELECT 'storage.threshold.cold.percent','INTEGER','90' UNION ALL SELECT 'storage.reserve-percent','INTEGER','10' UNION ALL
 SELECT 'cold.max-import-bytes','LONG','4398046511104' UNION ALL SELECT 'cold.require-manifest','BOOLEAN','true' UNION ALL
 SELECT 'cold.require-sha256','BOOLEAN','true' UNION ALL SELECT 'cleanup.enabled','BOOLEAN','true' UNION ALL
 SELECT 'cleanup.retention-days','INTEGER','180' UNION ALL SELECT 'cleanup.dry-run-required','BOOLEAN','true' UNION ALL
 SELECT 'cleanup.require-admin-confirm','BOOLEAN','true' UNION ALL SELECT 'cleanup.skip-classic-case','BOOLEAN','true' UNION ALL
 SELECT 'cleanup.allow-online-deleted-order','BOOLEAN','true' UNION ALL SELECT 'restore.enabled','BOOLEAN','true' UNION ALL
 SELECT 'restore.conflict-policy','STRING','REJECT' UNION ALL SELECT 'restore.skip-online-deleted','BOOLEAN','true' UNION ALL
 SELECT 'restore.require-file-complete','BOOLEAN','true' UNION ALL SELECT 'restore.retry-count','INTEGER','3' UNION ALL
 SELECT 'supplement.allowed-extensions','STRING','zip,step,stl,dcm' UNION ALL SELECT 'supplement.max-file-size-bytes','LONG','1073741824' UNION ALL
 SELECT 'supplement.allow-delete','BOOLEAN','false' UNION ALL SELECT 'account.sync-enabled','BOOLEAN','true' UNION ALL
 SELECT 'account.sync-delay-minutes','INTEGER','30' UNION ALL SELECT 'account.sync-page-size','INTEGER','200' UNION ALL SELECT 'job.lease-seconds','INTEGER','60' UNION ALL
 SELECT 'job.max-retry-count','INTEGER','3' UNION ALL SELECT 'job.stale-timeout-minutes','INTEGER','30'
) v
WHERE NOT EXISTS (SELECT 1 FROM bk_config c WHERE c.config_key=v.config_key AND c.scope='GLOBAL' AND c.is_deleted=0);

UPDATE bk_config
SET config_name=CASE config_key
 WHEN 'backup.schedule.enabled' THEN '自动备份开关' WHEN 'backup.schedule.interval-minutes' THEN '自动备份周期（分钟）'
 WHEN 'backup.schedule.reconcile-interval-hours' THEN '候选订单对账周期（小时）' WHEN 'backup.candidate.page-size' THEN '候选订单分页大小'
 WHEN 'backup.candidate.lookback-seconds' THEN '候选订单回看窗口（秒）' WHEN 'backup.order.exclude-statuses' THEN '排除的订单状态'
 WHEN 'backup.order.skip-file-changes-after-backup' THEN '忽略备份后的文件变化' WHEN 'backup.order.classic-case-protection' THEN '经典案例保护'
 WHEN 'storage.threshold.hot.percent' THEN '热备容量告警阈值（%）' WHEN 'storage.threshold.cold.percent' THEN '冷备容量告警阈值（%）'
 WHEN 'storage.reserve-percent' THEN '存储预留比例（%）' WHEN 'cold.max-import-bytes' THEN '冷备导入最大解压字节数'
 WHEN 'cold.require-manifest' THEN '冷备必须包含清单' WHEN 'cold.require-sha256' THEN '冷备必须校验 SHA-256'
 WHEN 'cleanup.enabled' THEN 'COS 清理开关' WHEN 'cleanup.retention-days' THEN 'COS 清理保留天数'
 WHEN 'cleanup.dry-run-required' THEN '清理必须先试运行' WHEN 'cleanup.require-admin-confirm' THEN '清理必须管理员确认'
 WHEN 'cleanup.skip-classic-case' THEN '清理跳过经典案例' WHEN 'cleanup.allow-online-deleted-order' THEN '允许清理线上已删除订单'
 WHEN 'restore.enabled' THEN '线上恢复开关' WHEN 'restore.conflict-policy' THEN '恢复冲突策略'
 WHEN 'restore.skip-online-deleted' THEN '恢复跳过线上已删除订单' WHEN 'restore.require-file-complete' THEN '恢复要求订单文件完整'
 WHEN 'restore.retry-count' THEN '恢复失败重试次数' WHEN 'supplement.allowed-extensions' THEN '补传文件允许格式'
 WHEN 'supplement.max-file-size-bytes' THEN '补传文件大小上限' WHEN 'supplement.allow-delete' THEN '允许删除补传文件'
 WHEN 'account.sync-enabled' THEN '账号同步开关' WHEN 'account.sync-delay-minutes' THEN '账号同步周期（分钟）'
 WHEN 'account.sync-page-size' THEN '账号同步分页大小' WHEN 'job.lease-seconds' THEN '任务租约时长（秒）'
 WHEN 'job.max-retry-count' THEN '任务最大重试次数' WHEN 'job.stale-timeout-minutes' THEN '任务过期判定时间（分钟）'
 ELSE CONCAT('备份配置：',config_key) END,
 description=CASE WHEN config_key LIKE 'backup.%' THEN '控制订单候选发现和本地热备调度行为。'
 WHEN config_key LIKE 'storage.%' THEN '控制本地热备/NAS容量监测和安全预留策略。'
 WHEN config_key LIKE 'cold.%' THEN '控制冷备导入校验和解压安全限制。'
 WHEN config_key LIKE 'cleanup.%' THEN '控制 COS 清理范围、保护条件和管理员确认要求。'
 WHEN config_key LIKE 'restore.%' THEN '控制订单文件恢复到线上 COS 时的保护和冲突处理策略。'
 WHEN config_key LIKE 'supplement.%' THEN '控制订单补传工程文件的格式、大小和删除能力。'
 WHEN config_key LIKE 'account.%' THEN '控制线上账号同步到本地系统的周期和批量大小。'
 WHEN config_key LIKE 'job.%' THEN '控制长任务租约、重试和过期恢复行为。' ELSE '备份子系统运行配置。' END
WHERE scope='GLOBAL' AND is_deleted=0 AND (config_name IS NULL OR config_name='');
UPDATE bk_config SET options_json='[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]'
WHERE scope='GLOBAL' AND is_deleted=0 AND value_type='BOOLEAN' AND (options_json IS NULL OR JSON_LENGTH(options_json)=0);
UPDATE bk_config SET options_json='[{"label":"拒绝覆盖（推荐）","value":"REJECT"},{"label":"管理员确认后覆盖","value":"CONFIRM_OVERWRITE"},{"label":"直接强制覆盖","value":"FORCE_OVERWRITE"}]'
WHERE config_key='restore.conflict-policy' AND scope='GLOBAL' AND is_deleted=0 AND options_json IS NULL;
UPDATE bk_config SET category_code=CASE WHEN config_key LIKE 'backup.order.%' THEN 'ORDER' WHEN config_key LIKE 'backup.%' THEN 'BACKUP' WHEN config_key LIKE 'storage.%' THEN 'CAPACITY' WHEN config_key LIKE 'account.%' THEN 'ACCOUNT' WHEN config_key LIKE 'cold.%' OR config_key LIKE 'restore.%' OR config_key LIKE 'supplement.%' THEN 'FILE' ELSE 'OTHER' END,
 category_name=CASE WHEN config_key LIKE 'backup.order.%' THEN '订单' WHEN config_key LIKE 'backup.%' THEN '备份' WHEN config_key LIKE 'storage.%' THEN '容量' WHEN config_key LIKE 'account.%' THEN '账号' WHEN config_key LIKE 'cold.%' OR config_key LIKE 'restore.%' OR config_key LIKE 'supplement.%' THEN '文件' ELSE '其他' END
WHERE scope='GLOBAL' AND is_deleted=0 AND (category_code IS NULL OR category_code='');
