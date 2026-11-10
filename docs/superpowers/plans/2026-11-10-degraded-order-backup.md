# Degraded Order Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为文件关系损坏的正式订单增加仅管理员显式触发的降级备份，并让降级快照通过现有恢复入口安全恢复真实存在的文件。

**Architecture:** 线上系统提供独立的 v2 降级 Manifest 与确认接口，严格 Manifest、自动备份和普通失败重试继续失败关闭；完整和降级凭证共享订单终态仲裁。备份系统以独立 `DEGRADED_BACKUP` 任务读取降级 Manifest，把订单包先固化为暂存制品，线上凭证成功后再发布本地快照；恢复协议按 Manifest 版本选择历史 v1 校验或覆盖关系缺口的 v2 状态校验。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Jackson、JUnit 5/Mockito、MySQL 8、Vue 3、Vite、Element Plus、Node test runner。

**Spec:** [2026-11-10-degraded-order-backup-design.md](../specs/2026-11-10-degraded-order-backup-design.md)

## Global Constraints

- 自动 `ORDER_BACKUP`、普通手工备份和 `/retry-failed` 永不自动切换为降级模式。
- 只有失败码为 `RELATION_BROKEN` 且订单尚无有效终态凭证时，管理员才能创建独立 `DEGRADED_BACKUP` 任务。
- 降级任务只能容忍设计文档列出的八种关系缺口；下载、哈希、认证、数据库和对象存储系统故障仍使订单失败。
- 降级成功写入 `REAL + INCOMPLETE`；普通真实备份写入 `REAL + COMPLETE`；模拟快照写入 `METADATA_SIMULATION + NOT_APPLICABLE`。
- 完整或降级凭证一旦成为当前 `ACTIVE` 终态，后续竞争提交返回 `STATE_CONFLICT`，订单不再进入候选扫描。
- 关系缺口只保存为结构化证据，不生成零字节占位资产，不进入恢复上传集合。
- 恢复继续使用现有入口；零资产订单结果为 `SKIPPED`，无其他失败订单时任务整体为 `SUCCEEDED`。
- 本轮不实现或改变 COS 清理资格。
- 当前 `yigongbao-backup` 工作区存在其他功能的未提交改动；执行时必须使用隔离 worktree 或逐文件确认，禁止覆盖或提交无关改动。
- 用户未要求 Git 提交；实施过程不自动提交。若后续明确要求提交，分别按两个仓库的规范检查并仅提交本功能文件，提交信息使用中文 Conventional Commits。

## Review Focus

- 关系在严格读取失败后、降级读取前已被修复：降级接口返回 `STATE_CONFLICT`，不得生成无缺口的降级快照；Task 2 的控制器测试覆盖。
- 完整备份与降级备份并发竞争：只有第一个 `ACTIVE` 凭证生效，失败者的暂存制品不得发布；Task 3、Task 6 的并发测试覆盖。
- 线上凭证成功后本地发布前崩溃：相同幂等键重跑必须命中原凭证并发布原摘要制品；Task 6 的恢复测试覆盖。
- 恢复时同一关系由文件 A 改指文件 B：所有策略都拒绝用 A 覆盖或重新绑定 B；Task 8 的矩阵测试覆盖。
- 历史 v1 快照没有 `relationshipStateHash`：继续使用原 `manifestRevision/relationSetHash` 校验，不补造 v2 摘要；Task 8 的兼容测试覆盖。

---

### Task 1: 冻结 v2 关系状态契约和跨端模型

**Files:**
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/model/BackupCompleteness.java`
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/model/RelationGap.java`
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/RelationshipStateHasher.java`
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/RelationshipStateHasherTest.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupManifest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/BackupManifestTest.java`

**Interfaces:**
- Produces: `BackupCompleteness { COMPLETE, INCOMPLETE, NOT_APPLICABLE }`。
- Produces: `RelationGap(relationKind, sourceRecordId, relationOwnerKey, relationValue, originalFileId, reasonCode, reason, relationHash)`。
- Produces: `RelationshipStateHasher.StateDigests summarize(List<ManifestAsset> assets, List<RelationGap> gaps)`，返回 `relationGapHash` 和 `relationshipStateHash`。
- Produces: 本地 `BackupManifest` 新字段 `manifestSchemaVersion/backupCompleteness/relationGaps/relationGapCount/relationGapHash/relationshipStateHash`，旧构造器默认映射为 v1 `COMPLETE`。

- [ ] **Step 1: 写线上冻结摘要失败测试**

断言设计文档中的两个 `relationOwnerKey`、两个 `relationHash`、`relationGapHash=9de5a02f...c8dac2` 和 `relationshipStateHash=ee835353...18d11f`；再覆盖 `null` 与空串、无前导零 ID、固定九位时间和稳定排序。

- [ ] **Step 2: 运行摘要测试并确认失败**

Run in `yigongbao-parent`: `mvn -pl yigongbao-module-backup -Dtest=RelationshipStateHasherTest test`

Expected: FAIL，类或方法尚不存在。

- [ ] **Step 3: 实现线上模型与 `RelationshipStateHasher`**

严格按设计文档 §5.4 的 ASCII 前缀、4 字节大端长度、UTF-8、JCS 属性排序和 SHA-256 小写十六进制实现；不得复用当前未转义的竖线拼接摘要。

- [ ] **Step 4: 写本地 Manifest 向后兼容测试**

断言 v2 JSON 能完整反序列化缺口，历史构造器仍得到不可变资产/订单集合且不伪造 `relationshipStateHash`。

- [ ] **Step 5: 扩展本地 `BackupManifest` 并运行双端定向测试**

Run in `yigongbao-backup`: `mvn -f yigongbao-backup-server/pom.xml -Dtest=BackupManifestTest test`

Expected: 两个仓库的契约测试全部通过。

### Task 2: 线上按关系解析降级 Manifest

**Files:**
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/model/OrderAssetResolution.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/ManifestAssetResolver.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/FormalOrderAssetManifestResolver.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/FormalOrderAssetManifestResolverTest.java`

**Interfaces:**
- Produces: `ManifestAssetResolver.resolveWithGaps(OrderMainEntity) -> OrderAssetResolution`；默认实现把严格资产包装为无缺口结果。
- Produces: `OrderAssetResolution(List<ManifestAsset> assets, List<RelationGap> relationGaps)`。
- Consumes: Task 1 的 `RelationGap` 和 `RelationshipStateHasher`。

- [ ] **Step 1: 为八种稳定缺口原因写参数化失败测试**

覆盖 `FILE_ID_MISSING/FILE_RECORD_NOT_FOUND/FILE_RECORD_DELETED/FILE_SIZE_MISSING/OBJECT_KEY_MISSING/ORIGINAL_FILENAME_MISSING/URL_TARGET_NOT_FOUND/URL_TARGET_AMBIGUOUS`；断言其他有效关系仍生成真实资产。

- [ ] **Step 2: 写整体失败边界测试**

覆盖来源记录无稳定主键、未知关系类型、重复冲突关系、数据库异常、预签名/权限异常；这些情况必须抛出 `ManifestResolutionException`，不能写成缺口。

- [ ] **Step 3: 运行 resolver 测试并确认失败**

Run: `mvn -pl yigongbao-module-backup -Dtest=FormalOrderAssetManifestResolverTest test`

- [ ] **Step 4: 抽取关系枚举并实现 `resolveWithGaps`**

严格 `resolve` 调用同一枚举结果并在存在任一缺口时继续失败关闭；降级调用返回可用资产和缺口。`relationValue` 映射必须逐项对应设计文档，不归一化数据库原值。

- [ ] **Step 5: 重跑 resolver 测试**

Expected: 严格路径原测试保持通过，新增缺口矩阵全部通过。

### Task 3: 发布 v2 Manifest、确认接口和终态凭证仲裁

**Files:**
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/model/OrderManifest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/OrderManifestService.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/OrderManifestServiceImpl.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/controller/OrderManifestController.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/receipt/model/ReceiptCommand.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/receipt/entity/OrderBackupReceiptEntity.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/receipt/controller/ReceiptController.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/receipt/ReceiptServiceImpl.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/resources/openapi/backup-internal-v1.yaml`
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/sql/migration/2026-11-10-10-degraded-order-backup.sql`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/OrderManifestServiceImplTest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/controller/OrderManifestControllerTest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/receipt/ReceiptServiceImplTest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/receipt/ReceiptControllerConflictTest.java`

**Interfaces:**
- Produces: `createDegradedManifest(Long)`、`confirmDegradedManifest(Long,String,String)`。
- Produces: `GET /internal/backup/v1/orders/{orderId}/manifest/degraded` 和 `POST /internal/backup/v1/orders/{orderId}/manifest/degraded/{revision}/confirm`。
- Produces: 凭证字段 `backupCompleteness/relationGapCount/relationGapHash/relationshipStateHash`。
- Consumes: Task 2 的 `resolveWithGaps`，Task 1 的摘要。

- [ ] **Step 1: 写严格/降级接口行为测试**

严格接口遇到缺口仍返回 409 `RELATION_BROKEN`；降级接口返回 v2 `INCOMPLETE`、真实资产和缺口；零资产仍返回有效 Manifest；关系已修复且没有缺口时返回 409 `STATE_CONFLICT`。

- [ ] **Step 2: 写降级确认漂移测试**

缺口被修复、新增缺口、文件身份变化或关系转移时确认返回 `MANIFEST_CHANGED`；完全相同状态确认成功。

- [ ] **Step 3: 实现 v2 Manifest 和独立接口**

普通完整 Manifest 也输出 v2 `COMPLETE` 和空缺口集合；旧字段保留。降级接口在读取时检查当前有效凭证，已有终态时直接返回 `STATE_CONFLICT`。

- [ ] **Step 4: 写凭证并发仲裁测试**

相同幂等键和相同摘要幂等返回；不同幂等键在已有当前 `ACTIVE` 完整或降级凭证时一律 `STATE_CONFLICT`；非 `ACTIVE` 历史凭证不阻止新终态。

- [ ] **Step 5: 扩展凭证模型、迁移和候选回归测试**

迁移为历史真实凭证回填 `COMPLETE`，新增字段允许历史行为空；`OrderBackupCandidateMapper` 继续以任一当前 `ACTIVE` 凭证排除候选，不按完整性区分。

- [ ] **Step 6: 运行线上模块定向测试**

Run: `mvn -pl yigongbao-module-backup -Dtest=OrderManifestServiceImplTest,OrderManifestControllerTest,ReceiptServiceImplTest,ReceiptControllerConflictTest,CandidateEventServiceImplTest test`

Expected: 全部通过。

### Task 4: 本地保留结构化远端错误并创建显式降级任务

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/remote/RemoteCallException.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/remote/RemoteHttpSupport.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobEvidenceService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobOrderEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminBackupController.java`
- Modify: `yigongbao-backup-server/src/main/resources/openapi/local-backup-v1.yaml`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/remote/RemoteHttpSupportBusinessErrorTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobServiceTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminBackupControllerTest.java`

**Interfaces:**
- Produces: `RemoteCallException.code()/requestId()/reason()`，日志消息继续脱敏。
- Produces: `JobOrderEntity.degradedBackupEligible` 非持久化展示字段。
- Produces: `POST /api/v1/admin/backup/jobs/{jobId}/orders/{sourceOrderId}/degraded-backup`。

- [ ] **Step 1: 写结构化错误保留测试**

给定 409 信封，断言 `code=RELATION_BROKEN`、`retryable=false`、请求 ID 和首条原因可单独读取；`JobEvidenceService` 将业务码而非 Java 类名写入 `failure_code`。

- [ ] **Step 2: 写降级入口授权测试**

只允许管理员、真实模式、原任务订单状态 `FAILED`、`failureCode=RELATION_BROKEN` 且无 `AVAILABLE` 快照；其他错误、模拟任务、成功订单、已有完整/降级快照全部拒绝。

- [ ] **Step 3: 实现结构化错误与入口资格投影**

任务详情只根据结构化码展示资格，不解析中文错误消息。服务端 POST 必须再次校验，不能信任前端字段。

- [ ] **Step 4: 创建独立 `DEGRADED_BACKUP` 任务**

任务只含一个订单，幂等键包含原任务 ID、订单 ID 和当前操作者；不得修改原任务。普通 `/retry-failed` 不接受 `DEGRADED_BACKUP` 任务，避免降级任务混入严格重试。

- [ ] **Step 5: 运行本地定向测试**

Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=RemoteHttpSupportBusinessErrorTest,PersistentJobServiceTest,AdminBackupControllerTest test`

### Task 5: 本地读取降级 Manifest 并持久化缺口证据

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/RelationGapEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/RelationGapMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderSnapshotEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/OnlineManifestClient.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/HttpOnlineManifestClient.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/OrderBackupPipeline.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupEvidenceService.java`
- Create: `yigongbao-backup-server/sql/migration/2026-11-10-10-degraded-order-backup.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/HttpOnlineManifestClientTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/OrderBackupPipelineTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedBackupEvidenceTest.java`

**Interfaces:**
- Produces: `OnlineManifestClient.fetchDegraded(String)`；`confirm` 根据 Manifest 完整性选择严格或降级确认接口。
- Produces: `bk_order_snapshot.backup_completeness/relation_gap_count/relation_gap_hash/relationship_state_hash/manifest_schema_version`。
- Produces: `bk_relation_gap`，以快照 ID 保存 Task 1 定义的缺口字段。

- [ ] **Step 1: 写 HTTP v2 解析和凭证请求测试**

断言 v2 字段、零资产、缺口列表全部保留；降级确认只调用 `/manifest/degraded/.../confirm`；凭证请求带完整性和三个缺口摘要字段。

- [ ] **Step 2: 写订单包测试**

部分缺失时 ZIP 只含真实资产和 `manifest.json`；全部缺失时 ZIP 只含 Manifest；任何下载/哈希失败仍整单失败，不能追加关系缺口。

- [ ] **Step 3: 写持久化测试**

断言降级快照为 `INCOMPLETE`、资产与缺口分表保存、文件数量只统计资产；历史真实和模拟快照迁移分别回填 `COMPLETE/NOT_APPLICABLE`。

- [ ] **Step 4: 实现客户端、流水线和证据持久化**

`relationGaps` 只进入 Manifest 和 `bk_relation_gap`；禁止生成文件进度项或 ZIP 占位条目。

- [ ] **Step 5: 执行 SQL 静态检查与定向测试**

Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=HttpOnlineManifestClientTest,OrderBackupPipelineTest,DegradedBackupEvidenceTest test`

### Task 6: 实现共享暂存发布状态机和降级执行器

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupStageEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupStageMapper.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupStageService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/JobRuntimeConfiguration.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobDispatcher.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/sql/migration/2026-11-10-10-degraded-order-backup.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/BackupStageServiceTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedBackupJobHandlerTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/JobDispatcherTest.java`

**Interfaces:**
- Produces: `stage(jobId, orderId, manifest, result, storageVolumeId, idempotencyKey)`。
- Produces: `markReceiptAccepted(stageId)`、`publish(stageId)`、`supersede(stageId)`，状态固定为 `STAGED/RECEIPT_ACCEPTED/AVAILABLE/SUPERSEDED`。
- Consumes: Task 4 的 `DEGRADED_BACKUP` 任务和 Task 5 的 v2 Manifest。

- [ ] **Step 1: 写状态机与崩溃恢复测试**

本地包成功后只产生不可见 `STAGED`；线上凭证成功后先落 `RECEIPT_ACCEPTED` 再发布；在两步之间模拟崩溃，相同幂等键重跑后只得到一个 `AVAILABLE` 快照。

- [ ] **Step 2: 写完整/降级并发竞争测试**

首个凭证获胜；后到任务收到 `STATE_CONFLICT` 并把自己的阶段标记 `SUPERSEDED`；竞争失败者不得覆盖已有快照、资产、缺口或订单包。

- [ ] **Step 3: 实现共享暂存服务**

暂存表保存 Manifest JSON、包定位、包摘要、下载摘要、完整性和幂等键；`publish` 在单个本地事务中物化快照、资产、缺口、订单包和副本记录。

- [ ] **Step 4: 让正常与降级任务共用发布顺序**

`ORDER_BACKUP/MANUAL_BACKUP` 只调用严格 `fetch`；`DEGRADED_BACKUP` 只调用 `fetchDegraded`。两者都按“生成包→暂存→提交凭证→标记接受→发布”执行，任何分支都不得自动互相回退。

- [ ] **Step 5: 固化重试语义**

降级任务可重试网络、下载和存储临时故障；`RELATION_BROKEN/STATE_CONFLICT` 等不可重试业务错误直接终结。普通失败重试仍创建严格任务。

- [ ] **Step 6: 运行任务与状态机测试**

Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=BackupStageServiceTest,DegradedBackupJobHandlerTest,ManualBackupJobHandlerLoggingTest,JobDispatcherTest test`

### Task 7: 展示降级入口和“文件可能不完整”

**Files:**
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobTable.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderTable.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderDetail.vue`
- Modify: `yigongbao-backup-web/src/components/StatusBadge.js`
- Modify: `yigongbao-backup-web/src/taskCenter.js`
- Modify: `yigongbao-backup-web/test/backup-flow.test.js`
- Modify: `yigongbao-backup-web/test/taskCenter.test.js`

**Interfaces:**
- Produces: `degradedBackup(row)` 调用 Task 4 的单订单 POST 接口并打开新任务。
- Consumes: `degradedBackupEligible`、`backupCompleteness`、`relationGapCount`。

- [ ] **Step 1: 写静态 UI 行为测试**

只有符合资格的失败订单行出现“降级备份”；任务级“重试失败订单”不能代替该按钮；`DEGRADED_BACKUP` 任务不显示普通重试按钮。

- [ ] **Step 2: 写订单标签测试**

`INCOMPLETE` 快照在订单列表和详情显示“文件可能不完整”，并展示真实文件数、容量和缺失关系数；`COMPLETE` 不显示，`NOT_APPLICABLE` 保持模拟标签。

- [ ] **Step 3: 实现动作和展示**

点击后直接创建并打开独立任务，不修改原任务、不添加恢复确认弹窗、不创建待办按钮。

- [ ] **Step 4: 运行前端测试与构建**

Run in `yigongbao-backup-web`: `npm test`

Run in `yigongbao-backup-web`: `npm run build`

Expected: 全部通过。

### Task 8: 让正常恢复支持 v2 缺口状态

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/OnlineRestoreClient.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/HttpOnlineRestoreClient.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/LocalOrderRestoreService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/RestoreSessionEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/PersistentRestoreEvidenceStore.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/restore/LocalOrderRestoreServiceTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/restore/LocalOrderRestorePrepareTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/restore/HttpOnlineRestoreClientTest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/restore/RestoreSessionService.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/restore/PersistentRestoreSessionService.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/restore/entity/RestoreSessionEntity.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/restore/entity/RestorePlanEntity.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/restore/RestoreSessionContractTest.java`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/sql/migration/2026-11-10-10-degraded-order-backup.sql`
- Modify: `yigongbao-backup-server/sql/migration/2026-11-10-10-degraded-order-backup.sql`
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/resources/openapi/backup-internal-v1.yaml`
- Modify: `yigongbao-backup-server/src/main/resources/openapi/local-backup-v1.yaml`

**Interfaces:**
- Produces: 恢复 `PrepareCommand/RestoreRequest` 新字段 `manifestSchemaVersion/backupCompleteness/relationshipStateHash/relationGaps`。
- Consumes: Task 3 的当前 v2 关系状态和 Task 5 的包内缺口证据。

- [ ] **Step 1: 写本地恢复清单测试**

`assets` 是唯一上传集合，`relationGaps` 不要求 ZIP 条目；零资产继续在 `RestoreJobHandler` 中落订单 `SKIPPED` 且不创建线上会话。

- [ ] **Step 2: 写 v1/v2 协议兼容测试**

v1 使用现有 `manifestRevision/relationSetHash`；v2 必须验证 `relationshipStateHash` 和缺口投影；旧包不补造新摘要。`REJECT`、`CONFIRM_OVERWRITE` 和 `FORCE_OVERWRITE` 都必须保持现有入口名称。

- [ ] **Step 3: 写恢复状态转换矩阵测试**

覆盖 resolved A→A、resolved A→B、resolved A→缺失 A、gap→resolved B、gap→gap、关系删除/转移/改类型和新增关系；结果严格对应设计文档 §10.3。

- [ ] **Step 4: 实现线上关系状态预检和提交前复检**

默认 `REJECT` 不再因当前关系存在缺口而直接调用失败关闭的严格 Manifest；覆盖策略只能恢复包中真实存在且当前关系仍明确引用同一文件 ID 的资产。缺口已修复文件和新增关系保持不变。

- [ ] **Step 5: 持久化恢复协议版本和状态摘要**

本地与线上会话/计划表保存 Manifest 版本、完整性和 `relationshipStateHash`，保证服务重启后的提交复检使用原计划。

- [ ] **Step 6: 运行双端恢复测试**

Run in `yigongbao-parent`: `mvn -pl yigongbao-module-backup -Dtest=RestoreSessionContractTest,RestorePlanHashTest test`

Run in `yigongbao-backup`: `mvn -f yigongbao-backup-server/pom.xml -Dtest=LocalOrderRestoreServiceTest,LocalOrderRestorePrepareTest,HttpOnlineRestoreClientTest test`

### Task 9: 契约、迁移和端到端回归

**Files:**
- Modify: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/main/resources/openapi/backup-internal-v1.yaml`
- Modify: `yigongbao-backup-server/src/main/resources/openapi/local-backup-v1.yaml`
- Create: `docs/testing/degraded-order-backup.md`
- Create: `D:/01_Project/02_Personal/医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/DegradedOrderBackupContractTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedOrderBackupWorkflowTest.java`

**Interfaces:**
- Consumes: Tasks 1–8 的最终接口、SQL 和 UI。
- Produces: 可重复执行的联调步骤和完整验收证据。

- [ ] **Step 1: 对照实现更新两份 OpenAPI**

冻结降级 Manifest/确认、凭证 v2 字段、本地创建任务以及恢复 v2 字段；确认旧字段仍保持兼容。

- [ ] **Step 2: 验证数据库迁移**

在临时 MySQL Schema 上分别执行线上和本地历史初始化脚本，再执行新迁移；重复执行迁移不得破坏已有数据。检查历史 `REAL/METADATA_SIMULATION` 回填值和所有新索引。

- [ ] **Step 3: 运行线上模块全量测试**

Run in `yigongbao-parent`: `mvn -pl yigongbao-module-backup -am test`

- [ ] **Step 4: 运行本地后端全量测试**

Run in `yigongbao-backup`: `mvn -f yigongbao-backup-server/pom.xml test`

- [ ] **Step 5: 运行前端全量测试和构建**

Run in `yigongbao-backup-web`: `npm test`

Run in `yigongbao-backup-web`: `npm run build`

- [ ] **Step 6: 执行端到端场景**

至少验证：部分文件缺失、全部文件缺失、普通重试仍严格失败、管理员降级成功、成功后候选消失、旧失败任务入口消失、零资产恢复跳过、v2 默认冲突、覆盖策略矩阵和完整/降级并发竞争。

- [ ] **Step 7: 记录人工联调项**

在 `docs/testing/degraded-order-backup.md` 记录测试数据准备、接口请求、数据库证据、ZIP 内容和页面检查；COS 清理明确标记为本轮未实现，不以待办形式延伸功能范围。

## Final Verification Checklist

- [ ] 逐条对照设计文档 §3–§15，确认每项要求能映射到 Task 1–9 的代码和测试。
- [ ] 检查所有正常自动/重试入口，确认没有调用 `fetchDegraded`。
- [ ] 检查所有缺口分支，确认只有八种稳定业务原因可以降级。
- [ ] 检查所有快照查询和恢复入口，确认 `INCOMPLETE` 是可用快照但不会被描述为文件完整。
- [ ] 检查工作区差异，排除实施前已存在的管理看板、存储卷等无关改动。
- [ ] 未经用户明确要求不创建 Git 提交；如要求提交，先执行项目 AGENTS.md 规定的状态、差异、分支和日志检查。
