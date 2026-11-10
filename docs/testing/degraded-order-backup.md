# 降级订单备份联调与验收

## 结论和边界

降级备份只用于真实严格备份因 `RELATION_BROKEN` 失败、且订单尚无可用本地快照/有效线上回执的单个订单。自动备份、首次手工备份和普通失败重试仍使用严格 Manifest；管理员必须在原失败订单行显式点击“降级备份”。成功后该订单按“已备份”处理，不再进入候选或旧任务的降级入口。

降级包只保存实际存在的文件，并把坏关系写入 `relationGaps` 证据，不生成占位文件。订单显示“文件可能不完整”，恢复仍使用普通恢复入口。全部文件缺失的包在恢复任务中记为 `SKIPPED`，不创建线上恢复会话。COS 清理不在本轮范围内。

## 测试数据准备

1. 准备一张满足正常备份资格且没有活动回执的订单。
2. 保留至少一条有效文件关系；另选一条关系，使其引用的 `file_id` 在文件表中不存在。用于全部缺失场景时，将该订单的所有文件关系都处理为同类缺口。
3. 记录订单 ID、关系来源表主键和原始文件 ID。不要删除关系来源行，因为“来源记录无稳定主键、数据库异常、未知关系类型、冲突重复关系”属于整体失败，不能降级。
4. 执行一次正常自动/手工备份，确认订单失败且结构化错误为 `RELATION_BROKEN`，页面出现单独的“降级备份”按钮。

允许转成缺口的稳定原因仅为：`FILE_ID_MISSING`、`FILE_RECORD_NOT_FOUND`、`FILE_RECORD_DELETED`、`FILE_SIZE_MISSING`、`OBJECT_KEY_MISSING`、`ORIGINAL_FILENAME_MISSING`、`URL_TARGET_NOT_FOUND`、`URL_TARGET_AMBIGUOUS`。

## 接口与任务检查

使用管理员会话调用：

```http
POST /api/v1/admin/backup/jobs/{原失败任务ID}/orders/{订单ID}/degraded-backup
```

响应任务的 `jobType` 应为 `DEGRADED_BACKUP`。重复点击应命中同一幂等任务；该任务不显示普通“失败重试”入口。原任务执行：

```http
POST /api/v1/admin/backup/jobs/{原失败任务ID}/retry-failed
```

时仍走严格 Manifest，关系未修复时继续以 `RELATION_BROKEN` 失败，不得自动转为降级。

线上调用链应只出现：

```text
GET  /internal/backup/v1/orders/{orderId}/manifest/degraded
POST /internal/backup/v1/orders/{orderId}/manifest/degraded/{revision}/confirm
PUT  /internal/backup/v1/receipts/{orderId}
```

正常自动/手工/失败重试任务不得调用前两个降级接口。

## 数据库证据

本地库：

```sql
SELECT id, source_order_id, snapshot_status, backup_mode, backup_completeness,
       manifest_schema_version, relation_gap_count, relation_gap_hash,
       relationship_state_hash
FROM bk_order_snapshot WHERE source_order_id = ?;

SELECT relation_kind, source_record_id, relation_owner_key, original_file_id,
       reason_code, relation_hash
FROM bk_relation_gap WHERE snapshot_id = ? ORDER BY relation_owner_key;

SELECT job_id, source_order_id, stage_status, backup_completeness,
       receipt_accepted_at, published_snapshot_id
FROM bk_backup_stage WHERE source_order_id = ? ORDER BY id;
```

期望快照为 `AVAILABLE / REAL / INCOMPLETE / schema=2`，缺口数和 `bk_relation_gap` 行数一致；发布阶段最终为 `AVAILABLE`。关系缺口不得出现在 `bk_asset` 中。

线上库：

```sql
SELECT order_id, receipt_status, is_current, backup_completeness,
       relation_gap_count, relation_gap_hash, relationship_state_hash
FROM order_backup_receipt WHERE order_id = ? ORDER BY receipt_version;
```

期望只有一个当前 `ACTIVE` 回执，完整性为 `INCOMPLETE`。它与完整备份共享同一终态仲裁：并发竞争的失败方应收到 `STATE_CONFLICT`，不得产生两个权威回执。

## ZIP 内容检查

打开 `bk_order_package.storage_key` 对应 ZIP：

- 必须有 `manifest.json`；`manifestSchemaVersion=2`、`backupCompleteness=INCOMPLETE`。
- `relationGaps`、`relationGapCount`、`relationGapHash`、`relationshipStateHash` 必须存在且相互匹配。
- `files/` 下只包含 `assets` 中的真实文件；缺口没有 ZIP 条目或零字节占位文件。
- 部分缺失场景：至少一个真实文件且至少一个缺口。
- 全部缺失场景：`assets=[]`、ZIP 无业务文件、缺口仍完整保留。

## 页面检查

- 原失败订单行显示单独的“降级备份”，普通重试按钮语义不变。
- 降级成功后，订单列表和详情显示“文件可能不完整”。
- 降级任务不显示普通失败重试操作。
- 降级成功后刷新原失败任务，旧订单不再提供降级入口；候选扫描也不再选中该订单。

## 正常恢复与关系安全矩阵

从现有恢复页面选择该快照，沿正常 `REJECT / CONFIRM_OVERWRITE / FORCE_OVERWRITE` 流程执行，无独立入口或额外提示。上传集合只取 Manifest 的 `assets`；`relationGaps` 只作为状态证据。

| 备份时关系 | 恢复时关系 | 结果 |
| --- | --- | --- |
| 文件 A | 仍为 A | 允许覆盖恢复 A |
| 文件 A | 明确缺失且仍引用 A | 允许恢复 A |
| 文件 A | 已改指 B | 所有策略拒绝 |
| 文件 A | 已删除、转移或改类型 | 所有策略拒绝 |
| 缺口 | 已修复为 B | 允许，保留 B，不用缺口覆盖 |
| 缺口 | 仍为同一缺口 | 允许，保持缺口 |
| 缺口 | 已删除、转移或改类型 | 所有策略拒绝 |
| 备份后新增关系 | 任意有效当前状态 | 允许并保留新增关系 |

准备后、提交前会再次解析当前关系状态。上传期间若发生不安全变化，提交应拒绝，重新创建恢复任务即可。全部缺失包在本地订单层标记 `SKIPPED`，整个任务在没有其他失败订单时仍成功。

## 自动化验收命令

```powershell
# 线上模块
mvn -pl yigongbao-module-backup test

# 本地后端
mvn -f yigongbao-backup-server/pom.xml test

# 本地前端
Set-Location yigongbao-backup-web
npm test
npm run build
```

重点自动化证据包括 `DegradedOrderBackupContractTest`、`DegradedOrderBackupWorkflowTest`、`RestoreRelationshipTransitionPolicyTest`、`DegradedBackupJobHandlerTest`、`BackupStageServiceTest` 和前端 `backup-flow.test.js`。

数据库迁移验收应在临时 MySQL 8 Schema 中先建历史结构/数据，再连续执行新迁移两次。历史真实快照/回执回填 `COMPLETE/0`，模拟快照回填 `NOT_APPLICABLE/0`，历史 `relationship_state_hash` 保持 `NULL`，不得补造摘要。
