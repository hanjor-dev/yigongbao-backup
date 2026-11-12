-- 补齐下载票据表上线时遗漏的运行时配置；可在存量环境幂等执行。
INSERT INTO bk_config
    (config_key,config_name,category_code,category_name,scope,value_type,
     config_value,description,config_version,changed_at,create_time,update_time,is_deleted)
SELECT 'download.ticket.ttl-seconds','下载票据有效期（秒）','DOWNLOAD_SECURITY','下载安全',
       'GLOBAL','INTEGER','120','单次下载票据有效期，范围30至600秒。',1,
       CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config
    WHERE config_key='download.ticket.ttl-seconds' AND scope='GLOBAL' AND is_deleted=0
);
