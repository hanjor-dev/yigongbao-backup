# 订单备份模块线上环境配置检查说明

本文用于本地备份子系统切换到真实备份模式前的上线检查。真实模式会从线上医工宝读取订单清单，并使用线上返回的 COS 预签名地址下载文件、生成订单 ZIP、保存到本地热备存储并提交线上备份回执。

本文不包含 COS 删除、线上恢复或冷备迁移的上线授权。首次联调应只验证订单备份链路，并保持清理和恢复功能关闭。

## 1. 配置来源和生效规则

| 配置类别 | 配置位置 | 说明 |
| --- | --- | --- |
| 服务启动、数据库连接、日志、执行模式、HMAC 密钥 | 本地备份服务 `.env` 或操作系统环境变量 | 进程启动时读取；修改后必须重启服务 |
| 备份周期、订单范围、热备目录、线上地址、重试策略、清理开关 | 本地备份系统数据库 `bk_config` | 管理页面修改后按运行时配置读取，部分基础设施配置在启动时校验 |
| 线上接口、Manifest、预签名 URL、回执处理 | 线上医工宝备份模块配置和数据库 | 线上服务必须正常运行并可访问 COS |

`.env` 应放在本地备份服务启动工作目录，或通过部署脚本指定外部环境文件。操作系统环境变量优先级高于 `.env`；不要把真实密钥提交到 Git。

## 2. 本地备份服务 `.env` 检查项

### 2.1 必填或必须确认的配置

```env
BACKUP_EXECUTION_MODE=REAL
BACKUP_SHARED_HMAC_SECRET=<与线上医工宝完全一致的随机密钥，至少32个字符>
BACKUP_DB_URL=<本地备份系统数据库 JDBC 地址>
BACKUP_DB_USERNAME=<数据库账号>
BACKUP_DB_PASSWORD=<数据库密码>
```

说明：

- `BACKUP_EXECUTION_MODE=REAL` 才会执行真实文件下载和 ZIP 生成。
- `METADATA_SIMULATION` 只适用于开发测试，生产 profile 下会被禁止。
- `BACKUP_SHARED_HMAC_SECRET` 不存数据库，必须与线上医工宝的 `BACKUP_SHARED_HMAC_SECRET` 完全一致。
- HMAC 密钥只用于本地备份服务与线上医工宝内部接口认证，不用于 COS 预签名 URL。
- 修改执行模式或 HMAC 密钥后必须重启本地备份服务；已经创建的任务会保留创建时的执行模式。

### 2.2 测试限制和服务基础配置

```env
BACKUP_DEV_CANDIDATE_LIMIT=0
BACKUP_SERVER_PORT=18080
BACKUP_MAX_UPLOAD_SIZE=2GB
```

- `BACKUP_DEV_CANDIDATE_LIMIT=0` 表示取消开发期候选订单数量限制。测试阶段可临时设置为 5 或 10，但生产不能保留该限制。
- `BACKUP_MAX_UPLOAD_SIZE` 主要影响补传文件等上传接口，不限制 COS 流式下载；应根据补传文件策略设置。
- 数据库、端口、日志路径等基础配置可以按现场环境调整，但不能与数据库中的业务配置重复维护。

## 3. 数据库业务配置检查

### 3.1 线上连接

| Key | 上线建议值 | 检查要求 |
| --- | --- | --- |
| `online.enabled` | `true` | 必须开启，否则候选扫描、Manifest、回执都会被拒绝 |
| `online.base-url` | `http://线上地址:端口/api` | 必须包含线上医工宝的 context-path；当前本地联调示例为 `http://localhost:8081/api` |
| `online.connect-timeout-seconds` | 按网络情况设置 | 连接超时不能过短 |
| `online.request-timeout-seconds` | 按接口响应情况设置 | Manifest 和回执接口应预留足够时间；大文件内容通过预签名流式下载 |

地址末尾可以有或没有 `/`，系统会规范化；不要配置到具体接口路径，例如不要填写 `/api/internal/backup/v1`。

### 3.2 自动备份策略

| Key | 作用 | 上线前确认 |
| --- | --- | --- |
| `backup.schedule.enabled` | 自动发现候选订单并创建备份任务 | 确认开启时间 |
| `backup.schedule.interval-days` | 自动任务自然日执行周期 | 通常为 `1` |
| `backup.schedule.start-time` | 每轮备份开始时间，格式 `HH:mm` | 配置为业务允许的低峰时段 |
| `backup.collection.days-before` | 备份执行日前多少天及更早创建的正式订单 | 确认订单成熟窗口，例如 `7` |
| `backup.schedule.reconcile-enabled` | 漏单、取消和删除事件对账 | 建议开启 |
| `backup.schedule.reconcile-interval-hours` | 对账周期 | 按业务量设置，通常为 `24` |
| `backup.retry.max-retries` | 单订单在当前任务中的最大重试次数 | 结合网络和 COS 稳定性设置 |
| `backup.retry.delays-seconds` | 失败订单各重试轮次的等待秒数 | 例如 `60,300,900` |

自动备份按自然日和订单创建日期增量查询，不按固定订单数量分页截断。若配置了 `BACKUP_DEV_CANDIDATE_LIMIT`，它仍会形成开发期数量上限。

### 3.3 本地热备存储

| Key | 作用 | 检查要求 |
| --- | --- | --- |
| `storage.hot-root` | 订单 ZIP、暂存目录和热备证据使用的根目录 | 服务账号必须可读写；Windows 环境建议使用绝对路径 |
| `storage.managed-roots` | 受控路径白名单 | 必须包含 `storage.hot-root`，并包含后续冷备/导入目录（如启用） |
| `storage.backup-reservation-percent` | 下载、压缩和发布过程的容量预留比例 | 应根据文件大小和并发量预留，默认较保守 |

服务启动时会创建并检查热备根目录。真实备份过程还会创建 `.staging` 临时目录；临时文件完成后会自动删除。

订单 ZIP 存储结构为：

```text
{storage.hot-root}/orders/{yyyyMMdd}/{订单号}-{订单ID}.zip
```

### 3.4 清理和恢复安全开关

首次真实备份联调建议保持以下配置关闭：

```text
cleanup.schedule.enabled=false
cleanup.enabled=false
restore.enabled=false
```

订单备份本身不会删除 COS，也不会调用线上恢复接口。COS 自动清理是独立任务，只有 `cleanup.schedule.enabled=true` 才会自动创建清理任务；恢复功能也必须单独开启。

## 4. 线上医工宝检查项

### 4.1 内部接口和 HMAC

线上医工宝必须部署备份模块，并确认以下接口可访问：

- 候选订单查询：`POST /api/internal/backup/v1/candidates/search`
- 订单 Manifest：`GET /api/internal/backup/v1/orders/{orderId}/manifest`
- Manifest 确认：`POST /api/internal/backup/v1/orders/{orderId}/manifest/{revision}/confirm`
- 备份回执：`PUT /api/internal/backup/v1/receipts/{orderId}`

线上应用的 `BACKUP_SHARED_HMAC_SECRET` 必须与本地备份服务一致。接口需要接受 `X-Timestamp`、`X-Nonce`、`X-Signature`、`X-Content-SHA256` 和幂等键等请求头。

### 4.2 订单候选数据

线上候选接口应只返回：

- 正式订单；
- 未删除、未取消的订单；
- 尚未被本地备份回执确认的订单；
- 满足订单创建日期成熟窗口的订单。

草稿订单不应进入候选清单。经典案例等禁止清理的业务规则不影响备份，但必须保证候选和 Manifest 查询仍能正常返回。

### 4.3 Manifest 完整性

Manifest 必须包含：

- 完整订单业务快照字段；
- 订单创建时间、订单状态和订单编号；
- 当前存在的全部关联文件；
- 文件 ID、文件类型、原始文件名、文件大小、可选 SHA-256；
- 文件关系和关联记录信息；
- `sourceObject.url` 形式的有效 COS 预签名下载地址；
- Manifest 版本、摘要和关系摘要。

并不是每种文件类型都必须存在；但如果存在关联记录却找不到对应文件、文件对象缺少大小或对象地址，线上接口会返回 `RELATION_BROKEN`，本地会按整单失败处理。

### 4.4 COS 和预签名 URL

- 线上医工宝必须能够访问真实 COS。
- COS 凭据配置在能够生成预签名 URL 的线上系统中，不配置在本地备份服务中。
- 预签名 URL 必须在本地服务网络范围内可访问，且有效期足以完成文件下载。
- 预签名地址应允许本地服务执行只读 GET，不需要附加本地 HMAC 请求头。
- 应使用一个小容量、文件关系完整的订单先验证 URL，再扩大测试范围。

## 5. 上线前验证顺序

1. 保持 `cleanup.schedule.enabled=false`、`cleanup.enabled=false`、`restore.enabled=false`。
2. 配置本地 `.env`，确认 `BACKUP_EXECUTION_MODE=REAL`、数据库连接和 HMAC 密钥。
3. 在数据库配置页确认 `online.enabled=true` 和 `online.base-url`。
4. 确认 `storage.hot-root` 已创建或服务账号可创建，并检查磁盘剩余容量。
5. 使用一个小订单调用候选查询接口，确认订单进入候选范围。
6. 调用订单 Manifest 接口，确认完整业务字段、文件关系和预签名 URL。
7. 手动创建单订单备份任务，观察日志中的清单读取、文件下载、校验、压缩、回执和状态发布过程。
8. 检查本地 ZIP、`bk_order_snapshot`、`bk_asset`、`bk_order_package` 和任务明细数据。
9. 确认订单文件页面显示订单业务字段、源文件规模、ZIP 实际容量和热备状态。
10. 再使用 5～10 个订单验证失败订单重试、任务汇总和容量预留。
11. 完成备份链路验证后，再单独评估恢复和 COS 清理功能，不要与首次备份测试同时开启。

## 6. 关键日志检查点

正常成功链路应能看到以下日志阶段：

```text
定时备份候选扫描开始
远程接口调用完成（候选查询）
定时备份任务已创建
任务已领取
读取线上订单清单开始/完成
文件下载开始/进度/完成
订单包压缩开始/完成
订单清单一致性确认开始/完成
订单备份制品提交完成
订单备份证据持久化完成
订单线上备份回执提交完成
订单热备快照已发布
订单备份任务汇总
任务执行成功
```

异常时重点关注：

- `RELATION_BROKEN`：线上关联关系不完整；
- `HTTP 401/403`：HMAC 密钥、时间偏差或接口权限错误；
- `HTTP 404`：线上地址或 context-path 配置错误；
- `HTTP 409`：Manifest 版本、关系或回执幂等冲突；
- `热备存储容量无法探测`：目录不存在、无权限或磁盘容量不可读；
- `LEASE_EXPIRED`：执行器租约续期失败或服务被中断；
- `预签名 URL 下载失败`：线上 COS 地址不可达、已过期或线上 COS 权限不足。

日志中不会输出 HMAC 密钥、完整预签名 URL 或其他认证凭据。

## 7. 真实模式切换结论

仅修改 `.env` 中的 `BACKUP_EXECUTION_MODE=REAL` 不能保证真实业务立即可用。必须同时确认：

- 本地和线上 HMAC 密钥一致；
- 数据库中的线上通信开关和地址正确；
- 线上 Manifest 接口能够生成有效 COS 预签名 URL；
- 本地热备目录可写且容量足够；
- 开发期候选数量限制已关闭；
- 清理和恢复开关仍处于安全状态；
- 修改 `.env` 后已重启服务；
- 使用新建的真实模式任务进行验证。

