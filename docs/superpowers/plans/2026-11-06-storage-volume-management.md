# 动态存储卷管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将本地备份系统从单一热备根目录升级为多存储卷路由，使 A/B/C 热备扩容、冷备介质离线/重新接入与直接线上恢复可安全运行。

**Architecture:** 新增逻辑存储卷台账 `bk_storage_volume`，订单包、补传文件和副本位置都保存 `storage_volume_id + storage_key`。文件 I/O 通过按卷路由的存储访问服务完成；备份任务创建时锁定目标热备卷，恢复和下载按历史文件所属卷读取。`bk_storage_pool` 继续代表容量/配额域，不能再作为文件位置的唯一标识。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus（禁止 XML Mapper）、MySQL 8、Vue 3、Vite、Element Plus、Windows 文件系统 / UNC 共享目录。

**Spec:** `docs/superpowers/specs/2026-11-06-storage-volume-management-design.md`

## Global Constraints

- Windows 本地路径、可移动盘与服务账号可访问的 UNC/NAS 路径必须走同一套存储卷接口；第一期不管理 NAS 凭据。
- 同一时刻全局只能有一个 `HOT + ACTIVE` 卷；自动切换只使用已纳管的 `HOT + STANDBY` 卷。
- 任务创建时必须固化源/目标卷；运行中不得因默认卷切换而改变既有任务的读写位置。
- 冷备卷重新接入后为 `COLD + ONLINE + READ_ONLY`，可直接下载及线上恢复，无需导入回热备区。
- 所有新表保留项目通用字段：`id`、`version`、`create_time`、`update_time`、`create_by`、`update_by`、`is_deleted`。
- 所有数据库读写使用 MyBatis-Plus；实体、服务、控制器、关键分支须有中文类/方法/核心行级注释；关键状态转换记录结构化业务日志与审计。
- 切换、停用、转冷备、删除卷必须拒绝影响运行中任务、活动空间预留或仍被活跃文件副本引用的卷；只读恢复任务不阻塞无关卷的切换，但会阻塞其源卷停用或删除。
- 线上医工宝没有存储卷职责，本计划不修改 `yigongbao-parent`；它仍只处理清单、预签名上传和恢复提交。
- 第一期开启“自动发现”仅扫描 Windows 服务账号可见的本地根目录、可移动盘和已映射网络驱动器；不提供人工输入任意物理路径的页面。未映射 UNC 共享须先由运维挂载到 Windows，未来 NAS 连接器另行设计。
- 每个卷使用系统受控子目录 `yigongbao-backup` 作为 `root_path`；卷标记文件只能写在该目录内，不能直接把整块未知磁盘或共享根目录作为业务写入根。

## Review Focus

- 盘符变化但卷标记匹配时，系统必须识别为原存储卷而不是新卷；由任务 2 的重接入测试覆盖。
- 插入未知 USB 盘或发现未知 UNC 路径时，系统必须只展示为候选卷，不能自动写入；由任务 3 的授权边界测试覆盖。
- ACTIVE 卷切换与正在运行的订单备份并发时，切换必须失败且备份持续使用已锁定卷；由任务 6 的并发/互斥测试覆盖。
- 历史 `storage_key` 迁移后，A/B 两卷中同名相对路径必须可同时存在；由任务 1 和任务 5 的跨卷定位测试覆盖。
- 已离线冷备卷的订单必须仍可检索，但下载/恢复须返回“卷未挂载”而非“文件不存在”；由任务 7 的订单查询与恢复测试覆盖。

---

## 文件结构与职责

| 区域 | 主要新增/修改内容 |
|---|---|
| SQL | 新增一次性迁移脚本、初始化表结构、索引与默认配置清理 |
| `storage.volume` | 存储卷实体、状态枚举、Mapper、发现服务、标记服务、生命周期服务、路由器 |
| `storage.api` | 将当前单根 `ArtifactStore` 替换为显式携带卷 ID 的访问接口 |
| `storage.location` | 订单包、补传和冷备副本的多副本位置台账与可用副本选择器 |
| `backup` | 创建/执行任务时锁定目标卷，按卷预留容量和写 ZIP |
| `order` / `restore` / `cold` | 包下载、补传、冷备写入/导入、热备清理、线上恢复按文件所属卷读取 |
| `admin` | 存储卷扫描、纳管、探测、挂载确认、状态转换、ACTIVE 切换接口 |
| `web` | 新的存储卷管理页替换当前路径探测页，并在订单/冷备界面展示卷可用性 |

### Task 1: 存储卷数据模型与历史数据迁移

**Files:**
- Create: `yigongbao-backup-server/sql/migration/2026-11-06-04-storage-volume-management.sql`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/02-indexes.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderPackageEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/BkSupplementFileEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/ColdBatchArtifactEntity.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeMigrationContractTest.java`

**Interfaces:**
- Produces `bk_storage_volume(id, volume_code, display_name, access_type, mount_path, root_path, volume_fingerprint, marker_json, capacity_pool_id, tier, mount_status, write_status, priority, last_probed_at, last_error_code, last_error_message, ...)`.
- Produces nullable-then-required `storage_volume_id` on order packages and supplement files; produces `storage_volume_id` on the existing `bk_artifact_copy`; produces `target_storage_volume_id` on `bk_job`; and captures restore task source package / source volume / storage key on `bk_job_order`.
- Produces `ArtifactCopyEntity` / `ArtifactCopyMapper` for the existing `bk_artifact_copy` table, making it the authoritative multi-replica location ledger. `bk_order_package` remains package metadata, not the sole physical-location record.

- [ ] **Step 1: 写入迁移契约测试**

覆盖 legacy 单根目录迁移为 `legacy-hot` 卷、`(storage_volume_id, storage_key)` 可重复于不同卷、一个订单包拥有热/冷多个副本、以及仅一个 `HOT + ACTIVE` 约束。

- [ ] **Step 2: 执行测试确认迁移契约当前失败**

Run: `mvn -q -Dtest=StorageVolumeMigrationContractTest test`

- [ ] **Step 3: 编写幂等迁移与初始化 DDL**

迁移读取旧 `storage.hot-root` 创建 `legacy-hot` 卷，把历史 `bk_order_package`、`bk_supplement_file` 的相对键关联到该卷，并为每个仍存在的文件创建 HOT 位置台账。只有该目录可访问、可写、容量可用且不存在其他 ACTIVE 卷时，legacy-hot 才可迁移为 ACTIVE；否则迁移为 READ_ONLY 或 OFFLINE，管理员必须在存储卷页显式选择新的 ACTIVE 卷。历史 `bk_cold_disk`、`bk_cold_batch` 和 `bk_cold_batch_artifact` 映射为 COLD 卷及冷副本位置；无法可靠判定的历史行必须写迁移异常报告，不能猜测其路径。项目现有表不使用数据库外键，沿用该模式：使用非空、唯一、普通索引与服务层逻辑校验，不新增外键约束。不能删除旧列或旧索引，直到后续兼容期结束。

- [ ] **Step 4: 为实体添加卷外键字段及中文注释**

实体字段命名统一为 `storageVolumeId`；为 `bk_artifact_copy` 增加实体、Mapper 和逻辑唯一约束；冷备副本必须额外保存实际所在卷而非仅批次号。`bk_job.target_storage_volume_id` 供备份写入任务与容量预留查询，`bk_job_order` 的包位置快照保证恢复任务重试仍使用首次选定的源副本。

- [ ] **Step 5: 运行迁移契约测试与 SQL 静态检查**

Run: `mvn -q -Dtest=StorageVolumeMigrationContractTest test`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-server/sql yigongbao-backup-server/src/main/java/com/yigongbao/backup/order yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold
git commit -m "feat: 增加存储卷数据模型"
```

### Task 2: 存储卷发现、标记与健康探测

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeMapper.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeDiscoveryService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeMarkerService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeStatus.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeDiscoveryServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeMarkerServiceTest.java`

**Interfaces:**
- `StorageVolumeDiscoveryService.scan() -> List<DiscoveredVolume>` 仅返回 Windows 服务账号可见的候选挂载点，不写入业务状态。
- `StorageVolumeMarkerService.initialize(Path root, UUID volumeId) -> Marker` 与 `verify(Path root, StorageVolumeEntity volume) -> VerificationResult`。
- `StorageVolumeHealthService.probe(StorageVolumeEntity volume) -> HealthSnapshot`。

- [ ] **Step 1: 写入 Windows 本地目录、可移动盘模拟及盘符变化的失败测试**

测试通过卷标记识别同一目录内容在不同盘符/挂载路径下仍归属原卷；未知盘和映射网络驱动器只返回候选项；容量未知的网络卷仅在存在有效配额容量域时才允许成为可写热备卷。

- [ ] **Step 2: 实现发现服务与访问类型识别**

使用 Java `FileStore` / `FileSystem` 能力扫描 Windows 服务账号可见的本地、可移动和已映射网络驱动器根目录；候选卷根由系统固定派生为 `<挂载点>/yigongbao-backup`。禁止人工输入任意物理路径、保存 NAS 密码或枚举无凭据网络共享。未映射 UNC 共享由客户运维在 Windows 层挂载后才会出现在候选列表。

- [ ] **Step 3: 实现卷标记与健康探测**

卷根写入 `.yigongbao-volume.json`，内容含卷 UUID、格式版本和创建信息；探测目录、读写、容量、延迟和标记一致性。若 `FileStore` 无法提供 NAS 容量，则使用已关联容量域中的显式配额；两者都不可用时卷可保持 ONLINE/READ_ONLY，但不得成为 HOT ACTIVE 或 STANDBY。探测失败只更新卷健康状态，不更改文件存在性。

- [ ] **Step 4: 运行服务单元测试**

Run: `mvn -q -Dtest=StorageVolumeDiscoveryServiceTest,StorageVolumeMarkerServiceTest test`

- [ ] **Step 5: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume
git commit -m "feat: 支持存储卷发现与健康探测"
```

### Task 3: 存储卷生命周期、纳管与状态转换

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeOperationException.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/audit/AuditLogService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminStorageVolumeControllerTest.java`

**Interfaces:**
- `register(RegisterStorageVolumeCommand command, long operatorId) -> StorageVolumeView`
- `confirmMounted(long volumeId, long operatorId) -> StorageVolumeView`
- `changeTier(long volumeId, Tier tier, WriteStatus writeStatus, long operatorId) -> StorageVolumeView`
- `setActive(long volumeId, long operatorId) -> StorageVolumeView`

- [ ] **Step 1: 写入状态机失败测试**

覆盖未知候选卷不可写入、OFFLINE 卷不可设 ACTIVE、COLD ONLINE 可读但不可自动写入、以及 ACTIVE 唯一性。

- [ ] **Step 2: 实现纳管与挂载确认**

纳管必须先在候选挂载点创建/验证系统受控子目录 `yigongbao-backup`、初始化/验证卷标记、创建卷记录和审计；重新接入只允许匹配既有卷标记后置为 ONLINE。若系统没有该挂载点创建目录的权限，则保留候选状态并提示运维修正 Windows 服务账号权限。

- [ ] **Step 3: 实现 HOT/COLD/IMPORT 与写入状态转换**

禁止未纳管卷、异常卷或目录标记冲突卷参与状态转换；将现有 `bk_cold_disk` 作为兼容关联数据迁移/映射，不重复维护两套真实卷身份。

- [ ] **Step 4: 替换旧路径探测接口的权限入口**

保留旧 `/admin/storage/probe` 一期兼容，但只接受已纳管卷 ID；新增接口的所有操作均执行 `ADMIN` 角色校验和审计。

- [ ] **Step 5: 运行状态机、控制器测试**

Run: `mvn -q -Dtest=StorageVolumeLifecycleServiceTest,AdminStorageVolumeControllerTest test`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin yigongbao-backup-server/src/test
git commit -m "feat: 增加存储卷纳管与状态转换"
```

### Task 4: 按卷路由的文件访问与容量预留

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/api/VolumeArtifactStore.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/VolumeArtifactStoreRouter.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/location/ArtifactCopyEntity.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/location/ArtifactCopyMapper.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/location/ArtifactReplicaResolver.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/local/LocalDirectoryArtifactStore.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/InfrastructureConfig.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/reservation/PersistentSpaceReservationService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/reservation/StoragePoolEntity.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/VolumeArtifactStoreRouterTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/location/ArtifactReplicaResolverTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/reservation/PersistentSpaceReservationServiceTest.java`

**Interfaces:**
- `VolumeArtifactStore.open(long storageVolumeId, String storageKey) -> InputStream`
- `VolumeArtifactStore.put(long storageVolumeId, String storageKey, InputStream content) -> Path`
- `VolumeArtifactStore.delete(long storageVolumeId, String storageKey)`
- `VolumeArtifactStore.resolve(long storageVolumeId, String storageKey) -> Path`
- `ArtifactReplicaResolver.resolveReadable(ArtifactType artifactType, long artifactId) -> ReadableArtifactReplica`，按 `ACTIVE + ONLINE` 副本选择；热、冷多个副本同时存在时优先选择当前可访问且已校验的副本。
- `SpaceReservationService.reserve(long storageVolumeId, long bytes, long jobId) -> SpaceReservation`

- [ ] **Step 1: 写入跨卷同名 key 与越界路径失败测试**

验证 `A/orders/x.zip` 与 `B/orders/x.zip` 独立存在；相对路径逃逸、OFFLINE 或 READ_ONLY 写入必须拒绝；当热副本已删除而冷副本在线时解析器必须返回冷副本，冷副本离线时必须返回可区分的 `STORAGE_VOLUME_OFFLINE` 错误。

- [ ] **Step 2: 实现存储卷路由器**

路由器每次按卷记录解析根路径并构造安全的局部目录访问器；不再注入启动时固定根目录的单例 `ArtifactStore`。实现位置台账写入与副本选择器，所有读取入口必须先解析可读副本，不能自行假定订单包位于当前热备卷。

- [ ] **Step 3: 改造容量预留**

容量探测和预留按 `storage_volume_id -> capacity_pool_id` 执行；保留租约、隔离令牌和并发超卖保护。

- [ ] **Step 4: 移除单根路径业务依赖**

删除 `InfrastructureConfig` 对 `storage.hot-root`、`storage.managed-roots`、`cold.import-root` 的运行时 Bean 绑定；保留仅用于兼容迁移的读取路径，不能再作为新文件路由依据。

- [ ] **Step 5: 运行路由与容量测试**

Run: `mvn -q -Dtest=VolumeArtifactStoreRouterTest,ArtifactReplicaResolverTest,PersistentSpaceReservationServiceTest test`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/InfrastructureConfig.java
git commit -m "refactor: 按存储卷路由文件访问"
```

### Task 5: 订单备份、ZIP 入库、补传与下载改造

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ScheduledBackupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/OrderBackupPipeline.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/MetadataSimulationArtifactWriter.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/SupplementService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/SupplementController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobEntity.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/OrderBackupVolumeBindingTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderPackageDownloadVolumeTest.java`

**Interfaces:**
- 新备份任务的 `JobEntity.targetStorageVolumeId` 保存写入卷；任务 JSON 额外保留卷代码、显示名和选择时间作为审计快照，但不能作为实际路由依据。
- `OrderPackageEntity` 与 `BkSupplementFileEntity` 在创建时写入 `storageVolumeId`，同时创建对应 HOT 位置台账记录。
- 下载接口根据文件所属卷加载文件，返回离线卷业务错误码 `STORAGE_VOLUME_OFFLINE`。

- [ ] **Step 1: 写入任务锁定卷的失败测试**

创建任务后切换 ACTIVE 卷，任务仍须写入原目标卷；新任务才使用新 ACTIVE 卷。

- [ ] **Step 2: 改造备份任务创建与流水线**

在创建任务时通过生命周期服务读取唯一 ACTIVE 卷，记录 `bk_job.target_storage_volume_id` 并在同一事务创建任务明细；ZIP、manifest、模拟占位文件及容量预留均显式传入该卷。调度创建任务时无合格 ACTIVE 卷必须持久化告警且不创建不可执行任务。

- [ ] **Step 3: 改造补传和订单包下载**

补传仍写 `supplements/{订单号}/`，但存储位置属于上传时已锁定的 ACTIVE 卷；下载先经副本选择器再按卷路由。前端不能拼接服务器物理路径。

- [ ] **Step 4: 运行跨卷写入、下载和模拟模式回归测试**

Run: `mvn -q -Dtest=OrderBackupVolumeBindingTest,OrderPackageDownloadVolumeTest,ManualBackupJobHandlerLoggingTest test`

- [ ] **Step 5: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup yigongbao-backup-server/src/main/java/com/yigongbao/backup/order yigongbao-backup-server/src/main/java/com/yigongbao/backup/job
git commit -m "feat: 订单备份按存储卷写入"
```

### Task 6: 热备卷切换与自动故障转移

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/ActiveVolumeSelectionService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthScheduler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/ConfigValueValidator.java`
- Modify: `yigongbao-backup-server/sql/init/04-functional-config-defaults.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/ActiveVolumeSelectionServiceTest.java`

**Interfaces:**
- `selectActiveForNewBackup(long requiredBytes) -> StorageVolumeEntity`
- `switchActive(long targetVolumeId, SwitchReason reason, long operatorId) -> StorageVolumeEntity`
- 新配置只保留策略值：`storage.backup-reservation-percent`、`storage.volume-switch.enabled`、`storage.volume-switch.threshold-percent`、`storage.volume-health.interval-minutes`。

- [ ] **Step 1: 写入 ACTIVE 切换互斥与优先级测试**

覆盖存在引用当前 ACTIVE 卷的运行中备份任务时人工切换拒绝、容量不足备用卷跳过、按优先级选中第一个合格 STANDBY 卷、只读恢复任务不阻塞无关卷切换、无候选卷时新备份暂停并告警。

- [ ] **Step 2: 实现手动 ACTIVE 切换事务**

锁定活动卷记录，校验引用当前卷的运行中写入任务和活动空间预留；原 ACTIVE 转 READ_ONLY，目标卷转 ACTIVE，并写审计与任务级日志。数据库使用生成列或等效唯一索引强制 `HOT + ACTIVE + is_deleted=0` 全局最多一条，不能只依赖应用事务判断。

- [ ] **Step 3: 实现定时健康检查与自动切换**

仅在启用配置、且没有引用当前 ACTIVE 卷的运行中写入任务时，于新备份任务创建前选择备用卷；健康检查只负责标记卷异常和产生切换建议，不能在任意时刻强行改变运行任务的目标卷。切换失败必须产生可见告警，不得写入未知或 COLD 卷。

- [ ] **Step 4: 清理已废弃路径配置**

从配置初始化、校验器、配置页面分类数据和说明文档中移除 `storage.hot-root`、`storage.managed-roots`、`cold.import-root`；新增策略配置说明。

- [ ] **Step 5: 运行选择与调度测试**

Run: `mvn -q -Dtest=ActiveVolumeSelectionServiceTest test`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume yigongbao-backup-server/src/main/java/com/yigongbao/backup/config yigongbao-backup-server/sql/init
git commit -m "feat: 支持热备卷切换与自动故障转移"
```

### Task 7: 冷备批次、重新接入与直接线上恢复改造

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/ColdBatchWorkflowService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/ColdWriteJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/HotCleanupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/ColdImportService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold/ColdImportJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/RestoreJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore/LocalOrderRestoreService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminColdBatchController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminColdImportController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/cold/ColdVolumeReattachRestoreTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/restore/RestoreFromColdVolumeTest.java`

**Interfaces:**
- 冷备批次写入命令改为 `targetStorageVolumeId`，不接受浏览器提交的任意 `coldRoot`。
- 恢复任务创建时，针对每个订单包通过 `ArtifactReplicaResolver` 选择并固化 `packageId + sourceStorageVolumeId + storageKey + SHA-256` 到 `bk_job_order`；离线时返回 `STORAGE_VOLUME_OFFLINE`。
- 导入区能力仅支持 `IMPORT` 卷，属于兼容流程，不是冷盘重新接入的前置步骤；导入前后均保留来源批次与副本审计，不能将导入结果误记为原冷备卷仍在线。

- [ ] **Step 1: 写入冷备卷重新接入并直接恢复的失败测试**

验证 COLD OFFLINE 时订单仍可查但恢复被拒绝；确认挂载后不复制 ZIP 即可执行恢复；热副本删除后解析器可选择冷副本；`COLD -> HOT` 不移动文件且不会自动变 ACTIVE；恢复任务创建后源卷再次离线时任务必须失败为可重试的卷不可达错误而不能改从未知路径读取。

- [ ] **Step 2: 改造冷备写入与热备清理**

冷备写任务必须以已纳管 COLD 卷作为目标；批次/副本保存源卷和目标卷，并在 `bk_artifact_copy` 中创建已校验 COLD 副本。热清理只能删除已验证冷备副本覆盖的原 HOT 副本，删除成功后更新该 HOT 副本为 RETIRED/ABSENT，不能删除订单包元数据或冷副本位置。

- [ ] **Step 3: 改造恢复和兼容导入**

恢复 handler 从任务订单中已固化的源副本读取 ZIP 并继续使用受控临时解压目录；导入任务只处理 IMPORT 卷中的批次大压缩文件，导入结果必须创建新的 HOT 或 COLD 副本位置记录并标记来源 IMPORT 副本的生命周期，不能覆盖原始冷备副本身份。

- [ ] **Step 4: 暴露冷备卷挂载确认与 COLD→HOT 操作**

管理员可确认挂载、转为 READ_ONLY / STANDBY / ACTIVE；变更记录操作审计、卷日志及受影响订单数。

- [ ] **Step 5: 运行冷备与恢复回归测试**

Run: `mvn -q -Dtest=ColdVolumeReattachRestoreTest,RestoreFromColdVolumeTest,LocalOrderRestoreServiceTest test`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/cold yigongbao-backup-server/src/main/java/com/yigongbao/backup/restore yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin
git commit -m "feat: 支持冷备卷重新接入直接恢复"
```

### Task 8: 管理接口、订单查询返回与操作审计

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/audit/AuditLogService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeQueryService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderStorageVolumeViewTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeAuditTest.java`

**Interfaces:**
- `GET /api/v1/admin/storage-volumes`：已纳管卷分页列表。
- `POST /api/v1/admin/storage-volumes/scan`：候选卷扫描。
- `POST /api/v1/admin/storage-volumes/{id}/probe|confirm-mounted|set-active|set-standby|to-cold|to-hot|disable`：管理员状态操作。
- 订单列表和详情返回包的 `storageVolumeId`、`storageVolumeName`、`storageTier`、`mountStatus`、`accessible`。

- [ ] **Step 1: 写入权限、离线显示与审计查询测试**

普通用户可查看订单所在卷和不可用原因，但不能访问卷管理接口；每项管理员操作都记录操作人、前后状态、原因和影响数量。

- [ ] **Step 2: 实现 DTO 与查询接口**

禁止向浏览器返回未脱敏的敏感 NAS 凭据；根路径仅向管理员展示，普通用户只看到卷显示名和状态。

- [ ] **Step 3: 更新订单文件可用性语义**

将“文件不存在”和“所在卷 OFFLINE/UNAVAILABLE”明确分开，供下载和恢复按钮使用。

- [ ] **Step 4: 运行接口/权限测试**

Run: `mvn -q -Dtest=OrderStorageVolumeViewTest,StorageVolumeAuditTest test`

- [ ] **Step 5: Commit**

```bash
git add yigongbao-backup-server/src/main/java/com/yigongbao/backup/order yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin yigongbao-backup-server/src/main/java/com/yigongbao/backup/audit
git commit -m "feat: 提供存储卷管理与订单定位接口"
```

### Task 9: 前端存储卷管理与订单文件可用性展示

**Files:**
- Create: `yigongbao-backup-web/src/views/StorageVolumeView.vue`
- Create: `yigongbao-backup-web/src/components/storage/StorageVolumeCandidateTable.vue`
- Create: `yigongbao-backup-web/src/components/storage/StorageVolumeTable.vue`
- Create: `yigongbao-backup-web/src/components/storage/StorageVolumeDetailDrawer.vue`
- Create: `yigongbao-backup-web/src/components/storage/StorageVolumeActionDialog.vue`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Modify: `yigongbao-backup-web/src/layouts/BackupLayout.vue`
- Modify: `yigongbao-backup-web/src/stores/routeStore.js`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/components/orders/OrderTable.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderDetail.vue`
- Modify: `yigongbao-backup-web/src/style-production.css`
- Test: `yigongbao-backup-web/test/storage-volume.test.js`

**Interfaces:**
- `useBackupConsole` 新增 `scanStorageVolumes`、`registerStorageVolume`、`probeStorageVolume`、`changeStorageVolumeState`、`storageVolumes`、`storageCandidates`。
- 路由使用 `storage-volumes`；原 `storage` 页面重定向或仅保留为兼容入口，不能再允许输入任意物理路径探测。

- [ ] **Step 1: 写入前端结构与角色控制测试**

验证组件化页面、管理员操作入口、普通用户只读状态、OFFLINE 订单禁用下载/恢复并显示原因。

- [ ] **Step 2: 实现候选扫描与纳管交互**

扫描结果与已纳管卷分区展示；纳管、挂载确认、切换 ACTIVE、转 HOT/COLD、停用均需二次确认和后端返回的影响说明。

- [ ] **Step 3: 实现卷详情及订单关联展示**

展示容量、健康、优先级、状态、最近错误、历史包数量、运行任务阻塞信息和审计记录入口。

- [ ] **Step 4: 替换旧路径输入容量页**

保留容量监控数据，但以卷为维度展示。移除“受控存储目录”文本输入和任意路径提交。

- [ ] **Step 5: 运行前端测试与生产构建**

Run: `npm test && npm run build`

- [ ] **Step 6: Commit**

```bash
git add yigongbao-backup-web/src yigongbao-backup-web/test
git commit -m "feat: 增加存储卷管理页面"
```

### Task 10: 端到端 Windows 验收、迁移演练与文档更新

**Files:**
- Create: `yigongbao-backup/docs/WINDOWS-STORAGE-VOLUME-ACCEPTANCE.md`
- Modify: `yigongbao-backup/系统备份子系统-最终配置清单.md`
- Modify: `yigongbao-backup/系统备份子系统-配置项说明.md`
- Modify: `yigongbao-backup/docs/ORDER-BACKUP-PRODUCTION-CHECKLIST.md`
- Modify: `yigongbao-backup/docs/superpowers/specs/2026-11-06-storage-volume-management-design.md`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeEndToEndTest.java`

- [ ] **Step 1: 写入 A/B/C Windows 模拟端到端测试**

使用临时目录模拟 A、B、C：A 写入订单包、切换 B、验证 A/B 同名相对键独立、将 A 置 COLD OFFLINE、重新接入 A、直接从 A 完成恢复读取。

- [ ] **Step 2: 进行历史数据迁移演练**

在复制的本地测试数据库执行迁移，核对全部 `bk_order_package`、`bk_supplement_file` 均关联 legacy-hot 卷，抽样下载和恢复成功；记录回滚策略为“迁移前完整数据库备份 + 不删除旧路径配置/旧索引直到验收完成”。

- [ ] **Step 3: 更新部署与配置文档**

删除三项路径配置的管理员操作说明，替换为卷纳管、Windows 服务账号权限、UNC 路径、NAS 现场检查、自动切换阈值和离线冷备重接入步骤。

- [ ] **Step 4: 运行全量验证**

Run: `mvn -q test`

Run: `npm test && npm run build`

- [ ] **Step 5: Commit**

```bash
git add docs yigongbao-backup-server/src/test yigongbao-backup-server/sql yigongbao-backup-web
git commit -m "docs: 补充存储卷部署与验收说明"
```

## 实施顺序与依赖

任务 1 是数据兼容基础；任务 2、3 建立卷生命周期；任务 4 完成 I/O 路由；任务 5、7 迁移核心业务链路；任务 6 在路由与任务锁定后实现自动切换；任务 8、9 提供管理体验；任务 10 完成迁移演练和验收。

建议严格按 `1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10` 执行。任务 6 与 7 都依赖任务 4/5，若并行实施需先冻结接口契约。

## 计划自审

- 设计中的多卷读写、唯一 ACTIVE、自动切换、离线冷备重新接入、直接恢复、IMPORT 兼容、NAS Windows 访问边界、路径配置废弃、审计、Windows 验收均已映射到任务 1–10。
- 所有跨任务接口均在对应任务的 Interfaces 段明确了卷 ID、路由器、生命周期服务或 REST 端点契约。
- Review Focus 的五类高风险输入均在任务 1、2、3、5、6、7 中安排了明确测试。
- 线上医工宝在本设计中无存储卷职责，已明确为不改动范围，避免产生无关改造。
