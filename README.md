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

后端验证命令：

```text
cd yigongbao-backup-server
mvn test
```

运行前请按部署环境配置 MySQL、热备目录、NAS/冷备目录以及线上医工宝接口参数；生产密钥不得提交到仓库。
