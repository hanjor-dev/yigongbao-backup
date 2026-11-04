-- 订单备份可靠性配置：候选对账独立开关和峰值工作空间预留比例。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type, config_value,
     options_json, description, config_version, changed_at, create_time, update_time)
SELECT 'backup.schedule.reconcile-enabled','候选对账开关','BACKUP_COLLECTION','备份采集策略','GLOBAL','BOOLEAN','true',
       '[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]',
       '控制是否定期对账候选订单；自动备份总开关关闭时本开关也不执行。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (SELECT 1 FROM bk_config WHERE config_key='backup.schedule.reconcile-enabled' AND scope='GLOBAL' AND is_deleted=0);

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type, config_value,
     description, config_version, changed_at, create_time, update_time)
SELECT 'storage.backup-reservation-percent','备份工作空间预留比例（%）','STORAGE_MANAGEMENT','存储与容量','GLOBAL','INTEGER','300',
       '按源文件总量预留下载原文件、压缩包和原子发布临时副本的峰值空间，范围100至500。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (SELECT 1 FROM bk_config WHERE config_key='storage.backup-reservation-percent' AND scope='GLOBAL' AND is_deleted=0);
