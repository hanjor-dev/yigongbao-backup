-- 本地备份库初始化（MySQL 8）。脚本可重复执行，不覆盖现有数据。
-- 执行前检查：SELECT VERSION(); SHOW CHARACTER SET LIKE 'utf8mb4';

CREATE DATABASE IF NOT EXISTS yigongbao_backup
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE yigongbao_backup;

-- 应用账号建议（由 DBA 在部署环境替换主机和强随机密码后单独执行）：
-- CREATE USER IF NOT EXISTS 'yigongbao_backup_app'@'10.%' IDENTIFIED BY '<vault-generated-password>';
-- GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON yigongbao_backup.* TO 'yigongbao_backup_app'@'10.%';
-- 禁止授予 DROP、ALTER、FILE、SUPER；迁移账号与运行账号分离。

-- 执行后验证：字符集和排序规则应分别为 utf8mb4、utf8mb4_0900_ai_ci。
SELECT schema_name,default_character_set_name,default_collation_name
FROM information_schema.schemata WHERE schema_name='yigongbao_backup';
-- 人工回滚：数据库含任何备份、审计或恢复证据时禁止 DROP DATABASE；仅撤销未启用的应用账号授权。
