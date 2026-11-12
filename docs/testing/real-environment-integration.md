# 真实环境集成与负载演练

这些入口默认不执行，只有显式提供独立测试环境和变量时才运行。不要指向生产库、生产可写 COS 地址或包含唯一副本的存储卷。

## 1. MySQL 8 多实例

1. 新建独立 MySQL 8 schema，先执行 `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`，再按文件名顺序执行迁移；迁移重复执行前先在快照副本中验证幂等性。
2. 使用 `application-integration.yml` 启动两个服务进程，端口不同但连接同一个测试 schema。至少并发演练任务领取、配置修改、登录限流、空间预留、下载票据消费和 permit 领取。
3. 设置 `BACKUP_IT_BASE_URL_A`、`BACKUP_IT_BASE_URL_B` 后运行：

```powershell
powershell -File .\tools\integration-readiness.ps1
```

两个实例都必须健康；数据库中同一任务、票据和 permit 只能被一个实例成功领取或消费。

## 2. Windows、本地可移动盘与 NAS

设置 `BACKUP_IT_LOCAL_PATH`。如需验证 UNC/NAS，再设置 `BACKUP_IT_NAS_PATH`，并确保运行服务的 Windows 账号拥有实际权限。只读检查不改文件；在专用空目录上可增加 `-WriteProbe` 验证创建、写入、原子移动和清理：

```powershell
powershell -File .\tools\integration-readiness.ps1 -WriteProbe
```

另行通过管理页面演练卷标记、活动/备用切换、离线、重新接入以及 junction/reparse point 拒绝。拔盘和断网仅在测试介质上执行。

## 3. COS 与线上接口

`BACKUP_IT_COS_PROBE_URL` 只接受短时只读探测地址。真实删除演练必须使用隔离 bucket，并确认 permit v2 同时返回 `objectSetHash` 和 `objects[].versionIdentity`；删除 URL 必须绑定相同 `versionId`。缺少任一字段时预期结果是失败关闭。

外部 URL 主机必须加入 `BACKUP_IT_EXTERNAL_ALLOWED_HOSTS`；集成环境仍拒绝回环、内网、链路本地、CGNAT 和 IPv6 ULA 地址。

## 4. 概览负载入口

先登录独立测试实例取得短时会话 token，再运行：

```powershell
$env:BACKUP_LOAD_BASE_URL='http://127.0.0.1:18081'
$env:BACKUP_LOAD_TOKEN='仅测试环境token'
$env:BACKUP_LOAD_CONCURRENCY='10'
$env:BACKUP_LOAD_REQUESTS='500'
node .\tools\overview-load-smoke.mjs
```

记录错误数和 P50/P95/P99。验收目标是在等效生产数据量、4 vCPU/8 GB、10 并发预热后 P95 不高于 1 秒，并结合 MySQL 慢查询与 `EXPLAIN ANALYZE` 排除随数据量增长的相关子查询。
