# 整盘冷备封存与原盘恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有“复制订单包到冷备批次”的冷备模式替换为可审计、可恢复的整盘热备封存流程，并实现冷盘离线检索、手动重新挂载、原盘直接下载和线上恢复。

**Architecture:** 以 `bk_storage_volume` 作为卷当前状态唯一事实来源，新建卷封存 attempt/manifest 明细和持久化读取租约。当前 ACTIVE 卷封存时先在短事务中原子切换接替卷并冻结源卷，再由通用长任务逐文件校验；读取入口统一通过租约服务固化健康副本，准备离线通过卷操作状态阻止新租约后再检查旧租约。

**Tech Stack:** Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.8、MySQL 8、JUnit 5、Mockito、Vue 3、Element Plus、Node test runner。

**Spec:** `docs/superpowers/specs/2026-11-09-cold-storage-volume-lifecycle-design.md`

## Global Constraints

- 冷备是整盘状态转换，不复制、不移动、不重新打包订单文件。
- 系统正常运行阶段必须且只能存在一个 `HOT/ONLINE/ACTIVE`；首次纳管的健康卷自动成为 ACTIVE。
- 当前 ACTIVE 封存必须选择已经是 `HOT/ONLINE/STANDBY` 的接替卷。
- 有非终态任务、读取租约、空间预留或卷操作占用时立即拒绝，不排队、不自动续办。
- 封存业务失败不自动创建新 attempt；进程失联只允许在旧 runner lease 过期后恢复同一 attempt。
- COLD 卷始终 `READ_ONLY`；退役使用独立 `admin_status=RETIRED`，不能写成 `COLD/DISABLED`。
- 订单与文件只持久化 `storage_volume_id + storage_key`；当前卷状态通过关联查询获得。
- 冷盘重新插入后必须由管理员确认挂载，不能自动上线。
- 临时导入区和选择性回迁不在本计划范围内。
- 旧 `COLD_WRITE`、`HOT_CLEANUP`、`COLD_IMPORT` 新建与重试入口必须停用，历史记录只读保留。
- 当前工作区已有大量用户改动；不得重置、清理或覆盖无关文件。根据项目提交规范不做中间提交；仅在用户明确要求时，完成规定检查后创建一个中文 Conventional Commit。

## Review Focus

1. **切换与新任务竞态：** 新备份任务与 ACTIVE A→B 切换并发时只能绑定 A 或 B 的完整提交状态；由 Task 2 的并发测试固定。
2. **读取与离线竞态：** 下载/恢复取租约与准备离线并发时只能一方成功；由 Task 4 和 Task 7 的事务竞争测试固定。
3. **长时封存中断：** runner 崩溃后不得产生双执行或半完成 COLD；由 Task 5 的 fencing/checkpoint 恢复测试固定。
4. **身份与路径变化：** 冷盘盘符变化应按 marker 匹配，复制 marker 或同 UUID 多路径必须拒绝；由 Task 6 的挂载测试固定。
5. **旧删除能力残留：** 升级后任何旧 `HOT_CLEANUP` 都不得删除当前有效文件；由 Task 6 和 Task 10 的旧任务切断测试固定。

---

## 文件结构与职责

### 新增后端领域文件

- `storage/archive/VolumeArchiveEntity.java`：封存 attempt、phase、统计、备注和失败摘要。
- `storage/archive/VolumeArchiveItemEntity.java`：冻结清单内每个物理路径的预期大小、摘要和校验结果。
- `storage/archive/VolumeArchiveMapper.java`、`VolumeArchiveItemMapper.java`：封存 attempt 和条目持久化。
- `storage/archive/ManagedVolumeArtifactCatalog.java`：从订单包、补传文件和副本台账生成规范、去重、可校验的冻结清单。
- `storage/archive/VolumeArchiveVerifier.java`：路径安全、文件类型、大小、SHA-256、ZIP manifest 和孤立文件验证。
- `storage/archive/VolumeArchiveService.java`：预览、前置检查、原子切换、创建 attempt、成功/失败收尾。
- `storage/archive/VolumeArchiveJobHandler.java`：按 checkpoint 执行 `VOLUME_ARCHIVE` 长任务。
- `storage/lease/StorageVolumeReadLeaseEntity.java`、`StorageVolumeReadLeaseMapper.java`：持久化读取租约。
- `storage/lease/StorageVolumeReadLeaseService.java`：原子解析副本、锁卷、取得/续租/释放/回收租约。
- `admin/AdminStorageVolumeArchiveController.java`：封存预览、启动、详情、准备离线和故障切换接口。

### 主要修改文件

- SQL 初始化、索引、full-init 与新迁移：新增卷状态字段、封存表和租约表。
- `StorageVolumeEntity/Status/Mapper/LifecycleService`：首卷自动 ACTIVE、完整状态机、路径重绑定、故障切换。
- `ArtifactReplicaResolver`：只返回业务状态和操作状态均允许读取的确定性副本。
- `OrderController`、`AdminRestoreController`、`RestoreJobHandler`：统一取得并释放读取租约，读取前从稳定句柄校验。
- `AdminStorageVolumeController`：移除普通 `to-cold` 语义，保留基础卷查询和受控挂载。
- `JobRuntimeConfiguration`、`JobDispatcher`：注册 `VOLUME_ARCHIVE`，停用旧冷备任务认领。
- `StorageView.vue`、`useBackupConsole.js`：封存预览、接替卷、备注、进度、离线和挂载交互。
- 订单/任务组件：展示封存备注、订单周期、实时卷状态和离线操作提示。
- `ColdView.vue`、旧冷备组件和导航：退出主界面，只保留必要的历史查询入口。

---

### Task 1: 数据库模型、状态枚举与迁移契约

**Files:**
- Create: `yigongbao-backup-server/sql/migration/2026-11-09-09-cold-volume-archive.sql`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveItemEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveMapper.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveItemMapper.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/lease/StorageVolumeReadLeaseEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/lease/StorageVolumeReadLeaseMapper.java`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/02-indexes.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeStatus.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeArchiveMigrationContractTest.java`

**Interfaces:**
- Produces: `StorageVolumeStatus.Admin { ENABLED, RETIRED }`、`Operation { IDLE, SEALING, ARCHIVE_GUARD, GOING_OFFLINE, MOUNTING, REPAIR_REQUIRED }`。
- Produces: `VolumeArchiveEntity` phase 为 `REQUESTED/SWITCHED/MANIFEST_FROZEN/VERIFYING/COMMITTING/SUCCEEDED/VERIFY_FAILED/COMMIT_FAILED/CANCELLED/INTERRUPTED/ABANDONED`。
- Produces: `bk_volume_archive`、`bk_volume_archive_item`、`bk_storage_volume_read_lease`。

- [ ] **Step 1: 写迁移契约失败测试**

  在 `StorageVolumeArchiveMigrationContractTest` 断言迁移和 init SQL 同时包含：`admin_status`、`operation_status`、`operation_ref_id`、查找备注/存放位置/封存统计字段、三张新表、ACTIVE 唯一索引、attempt 幂等索引、有效租约索引；断言没有把 COLD 退役编码成 `write_status='DISABLED'`。

- [ ] **Step 2: 运行失败测试**

  Run: `mvn -Dtest=StorageVolumeArchiveMigrationContractTest test`
  Expected: FAIL，提示新字段或表不存在。

- [ ] **Step 3: 实现 SQL 与实体**

  `bk_storage_volume` 增加 `admin_status`、`operation_status`、`operation_ref_id`、`archive_note`、`offline_label_note`、`physical_location`、`archived_at`、`order_created_from/to`、`archived_order_count/file_count/bytes`、`last_verified_at`。封存表包含 `job_id`、`attempt_no`、`phase`、manifest/checkpoint/failure/统计字段；条目表以 `(archive_id, storage_volume_id, storage_key)` 唯一；租约表包含 owner、purpose、artifact、heartbeat、expires、status、fencing token。

- [ ] **Step 4: 同步 init/full-init 与 Java 字段**

  保持 SQL 列、实体属性和状态枚举同名；为现存卷迁移默认 `admin_status=ENABLED`、`operation_status=IDLE`。

- [ ] **Step 5: 运行契约测试和编译**

  Run: `mvn -Dtest=StorageVolumeArchiveMigrationContractTest test`
  Expected: PASS。

  Run: `mvn -DskipTests compile`
  Expected: BUILD SUCCESS。

---

### Task 2: 首卷自动激活和可并发验证的卷状态机

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeStateConcurrencyTest.java`

**Interfaces:**
- Produces: `register(RegisterCommand,long)` 自动将无 ACTIVE 时的首个健康卷设为 `HOT/ONLINE/ACTIVE`，已有 ACTIVE 时默认 `HOT/ONLINE/STANDBY`。
- Produces: `switchActive(long sourceId,long successorId,long actorId)`，事务内锁 A/B，先降 A 再升 B。
- Produces: `confirmMounted(long volumeId, Path candidateMountPath, long actorId)`，按 marker 验证并原子重绑定路径。
- Produces: `failover(long unavailableActiveId,long standbyId,long actorId)`。

- [ ] **Step 1: 添加首卷和后续卷测试**

  测试首卷健康时自动 ACTIVE、首卷不可写时不伪造 ACTIVE、已有 ACTIVE 时新卷为 STANDBY、两个并发注册最终只有一个 ACTIVE。

- [ ] **Step 2: 添加切换和故障状态测试**

  覆盖 A→B 原子切换、B 更新失败整体回滚、A 有非终态任务/租约/预留时拒绝、`HOT/UNAVAILABLE/ACTIVE` 原盘恢复、故障切换到 B、故障切换后的 A 只恢复 `HOT/ONLINE/READ_ONLY`。

- [ ] **Step 3: 运行失败测试**

  Run: `mvn -Dtest=StorageVolumeLifecycleServiceTest,StorageVolumeStateConcurrencyTest test`
  Expected: 新行为相关测试 FAIL。

- [ ] **Step 4: 实现锁序和状态转换**

  Mapper 增加 `FOR UPDATE` 的 ACTIVE、指定卷和操作状态条件更新。移除普通 `changeTier(... COLD ...)` 和无接替卷 `to-cold` 路径；所有 ACTIVE 切换使用同一服务和锁序。

- [ ] **Step 5: 运行目标测试**

  Run: `mvn -Dtest=StorageVolumeLifecycleServiceTest,StorageVolumeStateConcurrencyTest test`
  Expected: PASS，且任何测试结束最多一个 ACTIVE。

---

### Task 3: 封存预览、受管文件目录与阻塞检查

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/ManagedVolumeArtifactCatalog.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveBlockerService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchivePreviewService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderPackageMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/BkSupplementFileMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/location/ArtifactCopyMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobMapper.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobOrderMapper.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/ManagedVolumeArtifactCatalogTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchivePreviewServiceTest.java`

**Interfaces:**
- Produces: `ManagedVolumeArtifactCatalog.freeze(long archiveId,long volumeId): FrozenManifest`。
- Produces: `VolumeArchiveBlockerService.inspect(long sourceId, Long successorId): List<Blocker>`。
- Produces: `VolumeArchivePreviewService.preview(long sourceId, Long successorId): ArchivePreview`，包含订单/文件/字节数、`orderCreatedFrom/To`、`periodLabel` 和 blockers。

- [ ] **Step 1: 写目录规范化与阻断矩阵测试**

  覆盖同路径重复一致记录去重、大小/摘要冲突阻断、`DAMAGED/ABSENT/REPLACING` 阻断、RETIRED 排除、业务目录孤立文件阻断、marker/系统目录排除、缺失 `order_created_at` 阻断。

- [ ] **Step 2: 写周期统计和 blocker 测试**

  三个订单跨 `2026-01-01` 至 `2026-03-15`，同订单多个文件只计一次，断言周期 `20260101-20260315`；覆盖 CREATED/RUNNING/RETRYING、取消处理中、恢复源引用、空间预留和操作占用。

- [ ] **Step 3: 运行失败测试**

  Run: `mvn -Dtest=ManagedVolumeArtifactCatalogTest,VolumeArchivePreviewServiceTest test`
  Expected: FAIL，新服务不存在。

- [ ] **Step 4: 实现冻结目录和预览**

  对 `(volumeId, storageKey)` 排序去重，清单条目写 `bk_volume_archive_item`，整体摘要使用规范字段拼接后 SHA-256；所有 SQL 查询限定 `is_deleted=0` 和明确生命周期。

- [ ] **Step 5: 运行目标测试**

  Run: `mvn -Dtest=ManagedVolumeArtifactCatalogTest,VolumeArchivePreviewServiceTest test`
  Expected: PASS。

---

### Task 4: 持久化读取租约与确定性副本路由

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/lease/StorageVolumeReadLeaseService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/location/ArtifactReplicaResolver.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeMapper.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/lease/StorageVolumeReadLeaseServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/location/ArtifactReplicaResolverTest.java`

**Interfaces:**
- Produces: `acquire(String artifactType,long artifactId,String purpose,String owner,Duration ttl): LeasedArtifact`。
- `LeasedArtifact` 实现 `AutoCloseable`，公开 `replica()`、`leaseId()`、`heartbeat()`、`close()`。
- Produces: `beginOffline(long volumeId,long actorId)` 使用同一卷锁先置 `GOING_OFFLINE` 再检查有效租约。

- [ ] **Step 1: 写确定性路由失败测试**

  断言优先 HOT/ONLINE，再 COLD/ONLINE/READ_ONLY，同层按 `lastVerifiedAt DESC,id ASC`；排除 RETIRED、OFFLINE、UNAVAILABLE、SEALING、ARCHIVE_GUARD、GOING_OFFLINE、MOUNTING、REPAIR_REQUIRED。

- [ ] **Step 2: 写租约竞态和失联回收测试**

  覆盖 acquire 与 beginOffline 并发只能一方成功、owner 关闭立即释放、TTL 至少两个心跳周期、旧 fencing token 不能续租、过期且 owner 失联后才能回收。

- [ ] **Step 3: 运行失败测试**

  Run: `mvn -Dtest=StorageVolumeReadLeaseServiceTest,ArtifactReplicaResolverTest test`
  Expected: FAIL。

- [ ] **Step 4: 实现事务取源和租约生命周期**

  在同一事务内锁候选卷、复核状态、选择副本并插入租约；不允许“先 resolve 后 insert”。准备离线失败时把操作状态恢复 `IDLE`。

- [ ] **Step 5: 运行目标测试**

  Run: `mvn -Dtest=StorageVolumeReadLeaseServiceTest,ArtifactReplicaResolverTest test`
  Expected: PASS。

---

### Task 5: 卷封存编排、全量校验与长任务恢复

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveVerifier.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/JobRuntimeConfiguration.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobDispatcher.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobExecutionContext.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveVerifierTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveJobHandlerTest.java`

**Interfaces:**
- Produces: `VolumeArchiveService.start(StartArchiveCommand,long actorId): JobEntity`。
- Produces: `VolumeArchiveService.complete(long archiveId,long fencingToken)` 和 `fail(long archiveId,ArchiveFailure,long fencingToken)`，均原子收尾 attempt、卷操作状态、ACTIVE guard 和 runner lease。
- Produces: `VolumeArchiveVerifier.verify(VolumeArchiveItemEntity): VerificationResult`。
- Consumes: Task 3 的 catalog/blockers，Task 4 的租约与卷操作状态。

- [ ] **Step 1: 写文件校验失败测试**

  覆盖路径逃逸、symlink/junction/reparse point、文件缺失、大小不符、SHA 不符、ZIP 损坏、缺失/错误 `manifest.json`、普通补传文件不套 ZIP 校验、成功后更新 `last_verified_at`。

- [ ] **Step 2: 写 ACTIVE 与 READ_ONLY 两条启动流程测试**

  ACTIVE A + STANDBY B：短事务后 A=READ_ONLY/SEALING、B=ACTIVE/ARCHIVE_GUARD、job=VOLUME_ARCHIVE；失败整体回滚。READ_ONLY/REPAIR_REQUIRED A：不重复切换 B，创建新 attempt 并保留旧报告。

- [ ] **Step 3: 写 phase、checkpoint、取消和恢复测试**

  断言 `REQUESTED→SWITCHED→MANIFEST_FROZEN→VERIFYING→COMMITTING→SUCCEEDED`；runner 失联后旧 lease 未过期不能接管，过期后从下一个安全条目继续；当前条目重验；取消和 VERIFY_FAILED 均让 B 保持 ACTIVE、A=REPAIR_REQUIRED，不自动创建 attempt。

- [ ] **Step 4: 运行失败测试**

  Run: `mvn -Dtest=VolumeArchiveVerifierTest,VolumeArchiveServiceTest,VolumeArchiveJobHandlerTest test`
  Expected: FAIL。

- [ ] **Step 5: 实现最小封存编排**

  文件哈希使用流式 `MessageDigest`；ZIP 校验复用安全 entry name 规则并核对数据库 `manifest_sha256`。每完成一条 item 后持久化结果并调用 `safePoint`；最终事务同时提交卷 COLD、attempt SUCCEEDED、源 IDLE、目标 guard 释放。

- [ ] **Step 6: 运行目标测试**

  Run: `mvn -Dtest=VolumeArchiveVerifierTest,VolumeArchiveServiceTest,VolumeArchiveJobHandlerTest test`
  Expected: PASS。

---

### Task 6: 管理 API、离线/挂载/故障切换与旧功能切断

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminColdBatchController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminColdImportController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/JobRuntimeConfiguration.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobDispatcher.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveControllerTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/LegacyColdFeatureShutdownTest.java`

**Interfaces:**
- Produces endpoints:
  - `GET /api/v1/admin/storage-volumes/{id}/archive-preview?successorId=`
  - `POST /api/v1/admin/storage-volumes/{id}/archive`
  - `GET /api/v1/admin/storage-volumes/{id}/archives`
  - `GET /api/v1/admin/volume-archives/{archiveId}`
  - `POST /api/v1/admin/storage-volumes/{id}/prepare-offline`
  - `POST /api/v1/admin/storage-volumes/{id}/confirm-mounted` body `{mountPath}`
  - `POST /api/v1/admin/storage-volumes/{id}/failover` body `{successorId}`
- Produces old create/retry APIs response code `LEGACY_COLD_FEATURE_DISABLED` and no destructive execution.

- [ ] **Step 1: 写接口契约失败测试**

  覆盖 ADMIN 权限、必填 `archiveNote`、ACTIVE 必填 successor、READ_ONLY 不要求 successor、blocker 明细、幂等双击、准备离线、盘符变化挂载、相同 UUID 双路径拒绝、故障切换。

- [ ] **Step 2: 写旧能力切断测试**

  断言 COLD_WRITE/HOT_CLEANUP/COLD_IMPORT 创建和重试全部拒绝，调度器不注册旧 handler，历史 GET 查询仍可用，遗留 HOT_CLEANUP 不会执行删除。

- [ ] **Step 3: 运行失败测试**

  Run: `mvn -Dtest=AdminStorageVolumeArchiveControllerTest,LegacyColdFeatureShutdownTest test`
  Expected: FAIL。

- [ ] **Step 4: 实现控制器和兼容错误**

  DTO 不接受任意绝对 `coldRoot`；挂载路径仅能来自服务器扫描候选并再次发现验证。所有错误返回可处理的卷、任务和文件上下文。

- [ ] **Step 5: 运行目标测试**

  Run: `mvn -Dtest=AdminStorageVolumeArchiveControllerTest,LegacyColdFeatureShutdownTest test`
  Expected: PASS。

---

### Task 7: 本地下载和线上恢复接入稳定句柄与读取租约

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/lease/VerifiedArtifactReader.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminRestoreController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/RestoreJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/LocalOrderRestoreService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/SupplementController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderDownloadReadLeaseTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/restore/RestoreReadLeaseIntegrationTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/SupplementDownloadReadLeaseTest.java`

**Interfaces:**
- Produces: `VerifiedArtifactReader.open(LeasedArtifact): VerifiedReadHandle`，校验大小/SHA 后从同一稳定句柄复位读取。
- Consumes: Task 4 的 `StorageVolumeReadLeaseService.acquire(...)`。

- [ ] **Step 1: 写本地下载租约测试**

  覆盖 HOT 和 COLD 在线下载、离线冷备返回介质提示、不创建必失败任务、摘要错误不向响应写损坏字节、客户端中断释放租约、下载与准备离线并发只能一方成功。

- [ ] **Step 2: 写线上恢复租约测试**

  断言创建 RESTORE job 时原子固化 `source_storage_volume_id/key` 并建立租约；取消处理中租约仍阻塞离线；成功/失败/取消/进程异常都释放或安全过期。

- [ ] **Step 3: 写补传下载测试**

  断言替代副本在线时展示和读取实际副本卷，而不是主表旧卷；COLD 在线直接读，离线明确拒绝。

- [ ] **Step 4: 运行失败测试**

  Run: `mvn -Dtest=OrderDownloadReadLeaseTest,RestoreReadLeaseIntegrationTest,SupplementDownloadReadLeaseTest test`
  Expected: FAIL。

- [ ] **Step 5: 接入统一 VerifiedArtifactReader**

  同步下载用 try-with-resources 覆盖响应正常/异常；后台恢复 lease owner 使用 job ID + fencing token 并在安全点 heartbeat。删除所有直接 `resolveReadable` 后无租约打开文件的路径。

- [ ] **Step 6: 运行目标测试**

  Run: `mvn -Dtest=OrderDownloadReadLeaseTest,RestoreReadLeaseIntegrationTest,SupplementDownloadReadLeaseTest test`
  Expected: PASS。

---

### Task 8: 查询聚合、实时卷状态与封存检索信息

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderStorageLocationService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderStorageLocationController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminJobController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderStorageLocationServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveViewTest.java`

**Interfaces:**
- Produces location DTO fields: current tier/mount/write/operation/admin status、`archiveNote/offlineLabelNote/physicalLocation/periodLabel`、聚合可用性 `ONLINE_READABLE/COLD_OFFLINE/PARTIALLY_DAMAGED/UNAVAILABLE`。
- Produces volume detail archive summary、attempt history、blockers and counts。

- [ ] **Step 1: 写查询聚合失败测试**

  覆盖 HOT→COLD 后不改订单行即可实时显示 COLD；只有离线冷备时显示查找信息；有 HOT 健康副本和 COLD 异常副本时整体仍可读但显示部分异常；历史 job 保留执行时 tier 快照并显示当前卷状态。

- [ ] **Step 2: 运行失败测试**

  Run: `mvn -Dtest=OrderStorageLocationServiceTest,AdminStorageVolumeArchiveViewTest test`
  Expected: FAIL。

- [ ] **Step 3: 实现关联查询和 DTO**

  当前状态始终来自 `bk_storage_volume`，任务快照字段只标为“执行时”；不批量更新订单、任务或文件状态。

- [ ] **Step 4: 运行目标测试**

  Run: `mvn -Dtest=OrderStorageLocationServiceTest,AdminStorageVolumeArchiveViewTest test`
  Expected: PASS。

---

### Task 9: 存储卷封存与挂载前端流程

**Files:**
- Create: `yigongbao-backup-web/src/components/storage/VolumeArchiveDialog.vue`
- Create: `yigongbao-backup-web/src/components/storage/VolumeArchiveProgress.vue`
- Create: `yigongbao-backup-web/src/components/storage/VolumeMountDialog.vue`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/composables/useBackupContext.js`
- Test: `yigongbao-backup-web/test/storage-volume.test.js`

**Interfaces:**
- Consumes: Task 6 API。
- Produces: ACTIVE 封存选择 successor + archiveNote；READ_ONLY 重试不重复选择；attempt 进度/失败明细；准备离线；确认挂载候选；故障切换。

- [ ] **Step 1: 扩展前端契约测试**

  断言不再直接调用 `to-cold`，存在 archive-preview/archive/prepare-offline/failover；备注必填；展示订单周期、订单/文件/容量、blockers、成功/失败最终状态；COLD/READ_ONLY 不出现设为 ACTIVE。

- [ ] **Step 2: 运行失败测试**

  Run: `npm test -- --test-name-pattern="storage volume"`
  Expected: FAIL。

- [ ] **Step 3: 实现组件和状态管理**

  使用 Element Plus dialog/table/progress；长任务沿用现有 job polling，不创建第二套轮询器。所有风险操作二次确认，后端错误明细原样可读展示。

- [ ] **Step 4: 运行前端测试和构建**

  Run: `npm test`
  Expected: PASS。

  Run: `npm run build`
  Expected: build success。

---

### Task 10: 订单/任务展示与旧冷备页面退役

**Files:**
- Modify: `yigongbao-backup-web/src/components/orders/OrderTable.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderDetail.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobTable.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Modify: `yigongbao-backup-web/src/layouts/BackupLayout.vue`
- Modify: `yigongbao-backup-web/src/views/ColdView.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Test: `yigongbao-backup-web/test/backup-flow.test.js`
- Test: `yigongbao-backup-web/test/cold-volume-lifecycle.test.js`

**Interfaces:**
- Consumes: Task 8 查询 DTO。
- Produces: 离线冷备定位提示、封存备注/存放位置/周期、实时状态与任务历史快照区分。

- [ ] **Step 1: 写展示失败测试**

  断言离线订单显示“请插入对应介质并确认挂载”、卷名/备注/位置/周期；下载与恢复禁用；在线后恢复。任务详情同时出现“执行时卷层级”和“当前卷状态”。

- [ ] **Step 2: 写旧页面退役测试**

  断言主导航不再提供旧“冷备批次”入口，前端无 `coldRoot`、COLD_WRITE、HOT_CLEANUP、COLD_IMPORT 创建调用；需要历史入口时只读展示且明确“旧功能已停用”。

- [ ] **Step 3: 运行失败测试**

  Run: `npm test`
  Expected: 新断言 FAIL。

- [ ] **Step 4: 修改订单/任务组件和导航**

  不复制实时卷状态到前端缓存的历史行；刷新卷后订单详情使用服务端最新 DTO。

- [ ] **Step 5: 运行前端测试和构建**

  Run: `npm test && npm run build`
  Expected: 全部 PASS。

---

### Task 11: 旧数据映射、端到端闭环与全量回归

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/LegacyColdLocationMigrationService.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/ColdVolumeLifecycleIntegrationTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/LegacyColdLocationMigrationServiceTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeMigrationContractTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces: 旧 COLD_BATCH 能证明卷身份和路径时回填 volume/copy；无法映射时记录 `LEGACY_COLD_LOCATION_UNAVAILABLE`，只读查询、不参与路由。
- Produces: A 备份→B 接替→A 封存→准备离线→换盘符确认挂载→本地下载→线上恢复的集成闭环。

- [ ] **Step 1: 写旧数据迁移测试**

  覆盖可映射、marker 冲突、缺失卷、路径不存在；断言无法映射记录不会被 resolver 选中，迁移报告数量准确。

- [ ] **Step 2: 写端到端集成测试**

  使用两个独立临时目录模拟 A/B：首卷自动 ACTIVE、订单写 A、B STANDBY、封存 A、B 接收新备份、A 离线仍可检索、挂载到新路径后直接下载和恢复；再注入 SHA 损坏验证 A 留在 REPAIR_REQUIRED。

- [ ] **Step 3: 运行失败测试**

  Run: `mvn -Dtest=ColdVolumeLifecycleIntegrationTest,LegacyColdLocationMigrationServiceTest test`
  Expected: FAIL，直到全部前序任务接通。

- [ ] **Step 4: 实现迁移服务和补齐闭环接线**

  迁移只补台账，不移动文件。README 更新管理员操作顺序、升级前旧任务终结要求和本期不支持临时导入区。

- [ ] **Step 5: 运行后端全量测试**

  Run: `mvn test`
  Expected: BUILD SUCCESS，无失败测试。

- [ ] **Step 6: 运行前端全量测试和构建**

  Run: `npm test`
  Expected: 全部 PASS。

  Run: `npm run build`
  Expected: build success。

- [ ] **Step 7: 执行最终静态核对**

  Run: `rg -n "coldRoot|COLD_WRITE|HOT_CLEANUP|COLD_IMPORT" yigongbao-backup-web/src yigongbao-backup-server/src/main/java`
  Expected: 仅允许历史只读兼容、错误常量或迁移代码；不存在新建/重试/删除执行入口。

  Run: `git diff --check`
  Expected: 无空白错误。

---

## 最终审查与交付门槛

- [ ] 对照需求文档 22 个章节建立“需求章节 → Task/测试”映射，确认无遗漏。
- [ ] 审查所有状态转换是否只经过受控服务，搜索并移除直接写 `tier/mount_status/write_status/operation_status` 的旁路。
- [ ] 审查所有订单包和补传文件读取路径是否先取得读取租约并在 finally/close 中释放。
- [ ] 审查所有文件系统路径是否规范化并限制在卷根内，拒绝 symlink/junction/reparse point 越界。
- [ ] 审查 SQL migration、init、full-init 和实体字段完全一致。
- [ ] 审查旧冷备创建/重试/删除能力在前后端均已切断，历史查询仍可用。
- [ ] 运行 `mvn test`、`npm test`、`npm run build`、`git diff --check` 并保存真实结果。
- [ ] 对当前工作树完整差异进行一次独立代码审查，按 P0/P1/P2 修复所有与本需求相关的问题后重复全量验证。
- [ ] 不声称 Windows 系统级安全弹出已由应用完成；界面只提示管理员执行操作系统安全弹出。
