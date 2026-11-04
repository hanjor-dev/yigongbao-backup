-- 自动备份改为天级周期，并在开发联调阶段限制单轮订单数。
INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, description, config_version, changed_at, create_time, update_time)
SELECT 'backup.schedule.interval-days', '自动备份周期（天）',
       'BACKUP_COLLECTION', '备份采集策略', 'GLOBAL', 'INTEGER', '1',
       '自动候选扫描间隔，范围1至365天。', 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config
    WHERE config_key = 'backup.schedule.interval-days'
      AND scope = 'GLOBAL' AND is_deleted = 0
);

UPDATE bk_config SET is_deleted = 1, update_time = CURRENT_TIMESTAMP(3)
WHERE config_key = 'backup.schedule.interval-minutes'
  AND scope = 'GLOBAL' AND is_deleted = 0;

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, description, config_version, changed_at, create_time, update_time)
SELECT 'backup.schedule.max-orders-per-run', '单轮最大备份订单数',
       'BACKUP_COLLECTION', '备份采集策略', 'GLOBAL', 'INTEGER', '5',
       '开发联调阶段限制每轮处理量，范围1至10。', 1,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config
    WHERE config_key = 'backup.schedule.max-orders-per-run'
      AND scope = 'GLOBAL' AND is_deleted = 0
);

UPDATE bk_config SET config_value = 'D:/yigongbao-backup/data', update_time = CURRENT_TIMESTAMP(3)
WHERE config_key = 'storage.hot-root' AND config_value = './data' AND is_deleted = 0;
UPDATE bk_config SET config_value = 'D:/yigongbao-backup/data,D:/yigongbao-backup/cold-disks,D:/yigongbao-backup/cold-import', update_time = CURRENT_TIMESTAMP(3)
WHERE config_key = 'storage.managed-roots' AND config_value = './data,./cold-disks' AND is_deleted = 0;
UPDATE bk_config SET config_value = 'D:/yigongbao-backup/cold-import', update_time = CURRENT_TIMESTAMP(3)
WHERE config_key = 'cold.import-root' AND config_value = './cold-import' AND is_deleted = 0;
