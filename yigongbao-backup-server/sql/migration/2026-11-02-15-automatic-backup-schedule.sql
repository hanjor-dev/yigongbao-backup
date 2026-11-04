-- 自动备份改为固定本地时间触发，并移除正式业务中的单轮订单数量限制。
DELETE FROM bk_config WHERE config_key = 'backup.schedule.max-orders-per-run';

INSERT INTO bk_config
(config_key, config_name, category_code, category_name, value_type, config_value,
 options_json, description, secret_ref, version, create_time, update_time, is_deleted)
SELECT source.config_key, source.config_name, 'BACKUP_COLLECTION', '备份采集策略', source.value_type,
       source.config_value, NULL, source.description, NULL, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0
FROM (
    SELECT 'backup.schedule.start-time' config_key, '自动备份开始时间' config_name, 'STRING' value_type,
           '05:00' config_value, '按服务器本地时区执行，格式HH:mm；服务停机错过后当天不补跑。' description
    UNION ALL SELECT 'backup.retry.max-retries','订单最大重试次数','INTEGER','3',
                     '单个订单首次失败后在当前备份任务中的最大重试次数。'
    UNION ALL SELECT 'backup.retry.delays-seconds','订单重试等待秒数','STRING','60,300,900',
                     '订单失败后各次重试前的等待秒数，逗号分隔。'
) source
WHERE NOT EXISTS (SELECT 1 FROM bk_config target WHERE target.config_key = source.config_key);
