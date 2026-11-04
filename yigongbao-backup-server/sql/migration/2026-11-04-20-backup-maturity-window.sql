-- 自动备份改为按订单创建日期的自然日成熟窗口采集。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO bk_config
    (config_key, config_name, category_code, category_name, scope, value_type,
     config_value, options_json, description, secret_ref, config_version,
     changed_at, create_time, update_time)
SELECT 'backup.collection.days-before', '备份X天前的订单', 'BACKUP_COLLECTION', '备份采集策略',
       'GLOBAL', 'INTEGER', '7', NULL,
       '按订单创建日期和服务器本地自然日计算；配置7表示执行日往前第7天及更早创建的正式订单进入备份范围。',
       NULL, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM bk_config WHERE config_key = 'backup.collection.days-before' AND is_deleted = 0
);

-- 分页大小是内部技术参数，不再暴露为业务配置；旧回看窗口也不再参与成熟集合扫描。
UPDATE bk_config
SET is_deleted = 1, update_time = CURRENT_TIMESTAMP(3), changed_at = CURRENT_TIMESTAMP(3)
WHERE config_key IN ('backup.candidate.page-size', 'backup.candidate.lookback-seconds')
  AND is_deleted = 0;
