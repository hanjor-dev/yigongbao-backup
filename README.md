# 医工宝备份子系统

本仓库包含医工宝备份与恢复系统的完整交付内容：

- `yigongbao-backup-server`：本地备份服务后端（Java/Spring Boot/MyBatis-Plus）
- `yigongbao-backup-web`：本地备份管理前端（Vue/Vite）
- 根目录 Markdown 文档：需求分析、技术设计、开发实施计划、技术预研及实施审查记录

## 目录说明

后端数据库初始化与迁移脚本位于：

```text
yigongbao-backup-server/sql/init
yigongbao-backup-server/sql/migration
```

前端开发命令：

```text
cd yigongbao-backup-web
npm install
npm test -- --run
npm run build
```

Windows 客户交付包由后端模块统一生成：

```powershell
cd yigongbao-backup-server
.\deploy\build-customer-package.ps1 -SkipBuild
```

固定输出目录为 `yigongbao-backup-server\dist\yigongbao-backup-server-windows`，其中包含后端 JAR、`frontend\dist`、Nginx/Windows、`nginx\conf\nginx.conf` 以及前后端启停和状态检查脚本。构建前应停止正在从该交付目录运行的后端和 Nginx，避免 Windows 文件句柄阻止目录原子替换；打包器会保留该固定目录中已经配置的 `.env`。

部署时先运行 `start-backup.bat`，后运行 `start-nginx.bat`，再用 `check-status.bat` 同时检查前端首页和后端健康状态。前端目录需要移到其他位置时，修改客户包 `nginx\conf\nginx.conf` 中的 `root`；修改配置后运行 `restart-nginx.bat`。

后端验证命令：

```text
cd yigongbao-backup-server
mvn test
```

运行前请按部署环境配置 MySQL、热备目录、NAS/冷备目录以及线上医工宝接口参数；生产密钥不得提交到仓库。
