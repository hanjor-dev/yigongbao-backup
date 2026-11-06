-- 已部署环境的恢复冲突策略协议统一。
-- 新值与线上 /internal/restore/v1 协议保持一致，避免本地配置值与线上接口枚举不匹配。
UPDATE bk_config
SET config_value = CASE config_value
        WHEN 'KEEP_LOCAL' THEN 'REJECT'
        WHEN 'KEEP_ONLINE' THEN 'REJECT'
        WHEN 'OVERWRITE_LOCAL' THEN 'FORCE_OVERWRITE'
        WHEN 'OVERWRITE_ONLINE' THEN 'FORCE_OVERWRITE'
        WHEN 'KEEP_NEWEST' THEN 'CONFIRM_OVERWRITE'
        ELSE config_value
    END,
    options_json = '[{"label":"拒绝覆盖（推荐）","value":"REJECT"},{"label":"管理员确认后覆盖","value":"CONFIRM_OVERWRITE"},{"label":"直接强制覆盖","value":"FORCE_OVERWRITE"}]',
    config_name = '恢复冲突策略',
    category_code = 'ONLINE_RESTORE',
    category_name = '线上恢复策略',
    value_type = 'ENUM',
    description = '线上订单文件或关联关系已变化时的处理方式。默认拒绝覆盖；强制覆盖可能回填历史文件记录，需由管理员审慎使用。',
    update_time = CURRENT_TIMESTAMP(3)
WHERE config_key = 'restore.conflict-policy'
  AND scope = 'GLOBAL'
  AND is_deleted = 0;
