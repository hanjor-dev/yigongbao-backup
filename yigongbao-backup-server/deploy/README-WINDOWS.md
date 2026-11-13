# Windows 前后端部署检查

1. 安装 Java 21 和 MySQL 8，将 `.env.example` 复制为 `.env` 并配置 `BACKUP_DB_URL`。全新环境双击 `init-database.bat`，输入具备初始化权限的 MySQL 管理账号密码，即可一次完成建库、建表、索引和初始数据。脚本可重复执行；升级已有环境仍应执行对应 `sql/migration` 脚本。
2. 挂载热备、冷备和导入介质；在数据库初始化完成后，通过“存储卷管理”扫描并纳管介质。请勿再配置 `storage.hot-root`、`storage.managed-roots` 或 `cold.import-root`。
3. `.env` 配置数据库及 `BACKUP_SHARED_HMAC_SECRET`；该密钥必须与线上医工宝保持一致且不得写入仓库。线上地址和通信开关在系统配置页维护。日志策略统一由 `logback-spring.xml` 管理，日志写入 JAR 同级的 `log` 目录，按天或单文件达到 100MB 时切片压缩，最多保留 30 天且归档总量不超过 2GB。
4. 构建 jar 后以管理员 PowerShell 执行 `deploy/install-windows-service.ps1`。
5. 通过 `/actuator/health`、日志目录和数据库连接检查服务状态；先执行试运行，再开放真实清理授权。
6. 前端执行 `npm ci`、`npm test`、`npm run build` 后，将 `dist` 放入客户包的 `frontend\dist`。交付包内置 Nginx/Windows，`nginx\conf\nginx.conf` 只服务当前备份系统：默认监听 80，将 `/api/` 代理到本机 18080，并使用 SPA 回退规则提供页面。
7. 依次运行 `start-backup.bat`、`start-nginx.bat`，再运行 `check-status.bat`。修改 Nginx 配置或替换前端文件后使用 `restart-nginx.bat`，停止时依次运行 `stop-nginx.bat` 和 `stop-backup.bat`。

`start-backup.bat` 会为 Windows JDK 设置 `jdk.net.unixdomain.tmpdir=%SystemRoot%\Temp`，避免较长用户临时目录导致 Tomcat NIO Selector 初始化失败。

本项目不使用 Docker；冷备盘由管理员选择并手工接入，服务不会自动移动或格式化未知盘。

## Windows 客户交付结构

将以下内容放在同一交付目录中：

```text
backup-server/
├─ frontend\dist\
├─ nginx\
│  ├─ nginx.exe
│  └─ conf\nginx.conf
├─ init-database.bat
├─ start-backup.bat
├─ stop-backup.bat
├─ start-nginx.bat
├─ restart-nginx.bat
├─ stop-nginx.bat
├─ check-status.bat
├─ .env
├─ yigongbao-backup-server.jar
└─ sql/
   └─ init/
      ├─ yigongbao-backup-full-init.sql
      ├─ 00-create-database.sql
      ├─ 01-schema.sql
      ├─ 02-indexes.sql
      ├─ 03-base-version.sql
      └─ 04-functional-config-defaults.sql
```

`init-database.bat` 默认直接执行 `yigongbao-backup-full-init.sql`。该文件缺失或执行返回错误时，脚本会按 `00` 至 `04` 的顺序临时组合五个分段脚本并自动兜底重试；报错切换时需要再次输入 MySQL 管理账号密码。脚本默认从 `.env` 的 `BACKUP_DB_URL` 读取 MySQL 主机和端口，并使用 `root` 作为初始化账号。MySQL 未加入 `PATH` 时，修改脚本顶部的 `MYSQL_HOME_CUSTOM`；现场使用其他迁移账号时，修改 `MYSQL_ADMIN_USER`。管理员密码由 MySQL 客户端交互读取，不会写入脚本、`.env` 或日志。

前端目录默认使用 `nginx` 前缀下的相对路径 `../frontend/dist`。现场另行放置前端文件时，修改 `nginx\conf\nginx.conf` 的 `root` 为实际绝对目录（Windows 路径使用正斜杠），然后执行 `restart-nginx.bat`。
