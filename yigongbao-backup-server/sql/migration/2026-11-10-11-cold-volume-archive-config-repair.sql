-- 补偿 2026-11-09-09 在旧 bk_config 列名处失败后的部分迁移状态。
-- 本文件刻意不含 DDL，可在前序 ALTER/CREATE 已自动提交时独立安全执行。
INSERT INTO bk_config(config_key,config_name,category_code,category_name,scope,value_type,config_value,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'storage.read-lease.ttl-seconds','存储读取租约时长（秒）','STORAGE_MANAGEMENT','存储与容量','GLOBAL','INTEGER','600','读取卷的租约时长，必须大于两倍心跳间隔。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS(SELECT 1 FROM bk_config WHERE config_key='storage.read-lease.ttl-seconds' AND is_deleted=0);

INSERT INTO bk_config(config_key,config_name,category_code,category_name,scope,value_type,config_value,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'storage.read-lease.heartbeat-seconds','存储读取租约心跳（秒）','STORAGE_MANAGEMENT','存储与容量','GLOBAL','INTEGER','30','长下载读取租约的续租间隔。',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS(SELECT 1 FROM bk_config WHERE config_key='storage.read-lease.heartbeat-seconds' AND is_deleted=0);
