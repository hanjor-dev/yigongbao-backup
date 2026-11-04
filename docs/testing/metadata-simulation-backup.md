# 订单备份元数据模拟模式测试说明

## 用途

本模式用于在本地 Windows 环境按真实日期范围拉取全部候选订单并验证任务流程，但不下载 COS 文件、不提交线上备份回执，也不形成可恢复的正式热备快照。

## 环境配置

在 `yigongbao-backup-server/.env` 中配置：

```properties
BACKUP_EXECUTION_MODE=METADATA_SIMULATION
BACKUP_SIMULATION_ROOT=D:/yigongbao-backup/simulation
BACKUP_DEV_CANDIDATE_LIMIT=0
```

- `BACKUP_DEV_CANDIDATE_LIMIT=0`：不限制候选订单数量，按日期规则完整翻页。
- 正整数：仅在需要再次缩小测试范围时使用。
- 模拟目录必须与正式热备目录分开。
- `prod` Profile 禁止启动模拟模式；本地测试应使用非 `prod` Profile。

## 预期流程

1. 调度器按数据库配置的“备份 X 天前的订单”和每日开始时间查询真实候选订单。
2. 任务列表出现带“元数据模拟”标签的任务。
3. 系统逐单读取线上 Manifest，生成预计文件数和容量。
4. 本地生成 `{日期}/{任务编号}/{订单号}-{订单ID}/`，其中包含 `manifest.json`、`_simulation.json` 和零字节占位文件。
5. 成功任务显示“模拟完成”，不会出现在“订单文件”页面。

## 安全核对

- 日志中只能看到候选查询和 Manifest GET，不应出现 COS 下载请求。
- 不应出现 Manifest confirm、备份 receipt、清理或恢复请求。
- 线上候选订单不会因模拟成功被标记为已备份，切回真实模式后仍可正常进入正式备份。

## 恢复真实模式

```properties
BACKUP_EXECUTION_MODE=REAL
BACKUP_DEV_CANDIDATE_LIMIT=0
```

重启服务后生效。任务已经创建后使用其配置快照中的模式，不会因重启或环境变量变化而中途切换。
