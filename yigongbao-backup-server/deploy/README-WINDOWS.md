# Windows 部署检查

1. 安装 Java 21 和 MySQL 8，执行 `sql/init/00-create-database.sql`、`01-schema.sql`、`02-indexes.sql`。
2. 创建热备 NAS 映射目录和冷备导入区；将 `BACKUP_HOT_ROOT`、`BACKUP_COLD_IMPORT_ROOT` 设置为服务账户可读写的绝对路径。
3. 设置 `BACKUP_DB_URL`、`BACKUP_DB_USERNAME`、`BACKUP_DB_PASSWORD`、`YIGONGBAO_ONLINE_BASE_URL` 及 HMAC 密钥引用，不把密钥写入仓库。
4. 构建 jar 后以管理员 PowerShell 执行 `deploy/install-windows-service.ps1`。
5. 通过 `/actuator/health`、日志目录和数据库连接检查服务状态；先执行试运行，再开放真实清理授权。

本项目不使用 Docker；冷备盘由管理员选择并手工接入，服务不会自动移动或格式化未知盘。
