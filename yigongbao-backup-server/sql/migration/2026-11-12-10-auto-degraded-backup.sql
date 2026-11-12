-- 新任务在严格备份遇到关系损坏时可自动形成不完整备份；幂等执行且不覆盖现场配置。
INSERT INTO bk_config
    (config_key,config_name,category_code,category_name,scope,value_type,
     config_value,options_json,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'backup.auto-degrade-on-relation-broken','文件关系不完整时自动降级备份',
       'BACKUP_COLLECTION','备份采集策略','GLOBAL','BOOLEAN','true',
       '[{"label":"启用","value":"true"},{"label":"停用","value":"false"}]',
       '严格备份因RELATION_BROKEN失败后，在同一任务补偿阶段自动执行不完整备份；即使没有可恢复文件也保存订单快照和关系缺口。',
       1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config
    WHERE config_key='backup.auto-degrade-on-relation-broken' AND scope='GLOBAL' AND is_deleted=0
);
