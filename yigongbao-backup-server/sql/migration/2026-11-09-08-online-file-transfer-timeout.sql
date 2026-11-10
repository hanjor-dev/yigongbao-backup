-- 为备份下载和恢复上传配置独立的大文件传输超时，避免复用普通接口短超时。
INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, description, config_version, changed_at, create_time, update_time)
SELECT 'online.file-transfer-timeout-seconds', '线上文件传输超时（秒）',
       'ONLINE_INTEGRATION', '线上系统连接', 'GLOBAL', 'INTEGER', '7200',
       '备份下载和恢复上传等大文件流式传输超时，范围60至86400秒。',
       1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config
    WHERE config_key = 'online.file-transfer-timeout-seconds'
      AND scope = 'GLOBAL' AND is_deleted = 0
);
