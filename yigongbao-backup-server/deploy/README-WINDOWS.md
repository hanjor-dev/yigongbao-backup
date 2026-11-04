# Windows 部署检查

1. 安装 Java 21 和 MySQL 8，按顺序执行 `sql/init/00-create-database.sql`、`01-schema.sql`、`02-indexes.sql`、`03-base-version.sql`、`04-functional-config-defaults.sql`。升级已有环境执行对应 `sql/migration` 脚本。
2. 创建热备 NAS 映射目录和冷备导入区；在数据库初始化完成后，通过系统配置页维护 `storage.hot-root`、`storage.managed-roots` 和 `cold.import-root`。
3. `.env` 配置数据库、日志及 `BACKUP_SHARED_HMAC_SECRET`；该密钥必须与线上医工宝保持一致且不得写入仓库。线上地址和通信开关在系统配置页维护。
4. 构建 jar 后以管理员 PowerShell 执行 `deploy/install-windows-service.ps1`。
5. 通过 `/actuator/health`、日志目录和数据库连接检查服务状态；先执行试运行，再开放真实清理授权。

本项目不使用 Docker；冷备盘由管理员选择并手工接入，服务不会自动移动或格式化未知盘。
