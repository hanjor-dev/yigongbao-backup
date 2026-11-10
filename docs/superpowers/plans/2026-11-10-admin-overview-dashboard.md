# 管理概览首页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前只返回存储探测与原始配置的管理概览改造成可信的管理员工作台，完整提供真实备份统计、持久化容量快照、当前有效告警、近 7 日趋势、近期任务和可处理的业务下钻。

**Architecture:** 后端新增独立概览查询层、纯策略层和编排服务：复杂统计由 MySQL 8 聚合查询一次完成，当前告警由数据库事实即时推导，容量只读取后台已持久化的成功快照。前端使用独立概览模型和小型 Vue 组件组合页面，并通过带结构化查询参数的轻量路由下钻到现有业务页面；首页加载和刷新不执行任何磁盘、NAS 或线上服务调用。

**Tech Stack:** Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.8、MySQL 8、JUnit 5/Mockito/AssertJ、Vue 3、Element Plus、Vite、Node.js `node:test`。

**Spec:** `docs/superpowers/specs/2026-11-10-admin-overview-dashboard-requirements.md`

## Global Constraints

- 所有业务日期使用 `Asia/Shanghai`；“今日”为 `[00:00:00, 次日 00:00:00)`，“近 7 日”包含今天及此前 6 日。
- 首页只统计真实备份；`METADATA_SIMULATION`、`SIMULATED`、`SIMULATION_FAILED` 不得进入真实订单、文件、今日结果和趋势容量。
- 首页请求不得调用 `StorageProbe`、`Files.getFileStore`、磁盘、NAS 或线上 API；管理员手动刷新仍只读数据库。
- 告警仅表示当前有效问题，不新增告警表，不从审计日志反推当前状态，恢复后下一次查询自动消失。
- 正常 `COLD/OFFLINE/READ_ONLY` 冷备卷不告警、不进入在线容量汇总，但保留并展示离线前容量快照。
- 页面只展示“可用备份订单、有效备份文件、今日订单备份”三张核心指标卡；不得增加备份覆盖率或正在执行任务 KPI。
- 状态接口保留稳定英文码，用户界面复用公共 `StatusBadge` 显示中文和统一颜色语义。
- 不增加图表依赖；近 7 日趋势使用 Vue + 原生 SVG/CSS，保持键盘可读和文本兜底。
- 所有复杂列表/统计必须批量查询，禁止逐订单、逐副本或逐卷 N+1 查询。
- MySQL 迁移脚本、`sql/init/01-schema.sql`、`sql/init/02-indexes.sql` 与 `sql/init/yigongbao-backup-full-init.sql` 必须同步更新。
- 遵守仓库 `AGENTS.md`：整个实施只创建一个最终 Git 提交；提交前依次运行 `git status`、`git diff HEAD`、`git branch --show-current`、`git log --oneline -10`，只暂存本需求文件并使用中文 Conventional Commit。

## Review Focus

1. **健康离线冷备副本**：订单不计入“当前可用”，但不得产生制品不可读告警；Task 2 与 Task 3 的测试必须同时固定这两个结果。
2. **过期且历史使用率过高的容量快照**：只能产生 `VOLUME_CAPACITY_UNKNOWN`，不能继续产生容量高告警；Task 3 覆盖抑制测试。
3. **真实快照与另一次模拟包交叉关联**：不能拼成真实备份成果；Task 2 的 SQL 契约和聚合测试覆盖。
4. **首次运行与缺失检查点**：全新无卷返回 `SETUP_REQUIRED`，账号同步启用但初始化后仍无检查点产生明确告警；Task 3、Task 4 覆盖。
5. **查询部分失败**：不得用 `0`、空数组或“系统正常”掩盖，必须返回 `sections=UNAVAILABLE`、`null` 数据和 `UNKNOWN` 健康状态；Task 4 与 Task 6 覆盖。

---

## 计划文件结构

### 后端新增文件

- `yigongbao-backup-server/sql/migration/2026-11-10-10-admin-overview-dashboard.sql`：容量成功观测时间和概览查询索引迁移。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeCapacitySnapshotPolicy.java`：统一更新探测时间、错误和最后一次合法容量快照。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewView.java`：概览响应、告警、趋势、容量和数据质量只读 DTO。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewQueryMapper.java`：概览数据库聚合接口与行投影。
- `yigongbao-backup-server/src/main/resources/mapper/AdminOverviewQueryMapper.xml`：真实备份、今日结果、趋势、制品质量和近期任务聚合 SQL。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/OverviewTimePolicy.java`：今日/近 7 日边界、成功相对备份周期和账号同步陈旧判断。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/OverviewStoragePolicy.java`：卷可读性、容量新鲜度、容量汇总和逐卷展示策略。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewAlertService.java`：当前有效告警、去重、抑制和健康级别推导。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewService.java`：分区查询编排、失败隔离、数据质量合并和最终响应。
- `yigongbao-backup-server/src/main/java/com/yigongbao/backup/account/AccountSyncCheckpointInitializer.java`：应用启动时为启用的账号同步建立 `USER/IDLE` 检查点。
- 对应的 `AdminOverview*Test.java`、策略测试和 SQL 契约测试。

### 前端新增文件

- `yigongbao-backup-web/src/overviewModel.js`：概览数据规范化、容量格式化、健康文案、趋势数据和下钻目标的纯函数。
- `yigongbao-backup-web/src/components/overview/OverviewHealthPanel.vue`：健康摘要和当前有效告警。
- `yigongbao-backup-web/src/components/overview/OverviewMetricGrid.vue`：三张核心指标卡。
- `yigongbao-backup-web/src/components/overview/OverviewStoragePanel.vue`：在线容量汇总和逐卷容量。
- `yigongbao-backup-web/src/components/overview/OverviewTrendPanel.vue`：7 日订单结果与新增逻辑字节趋势。
- `yigongbao-backup-web/src/components/overview/OverviewRecentJobs.vue`：最近 10 条任务与业务下钻。
- `yigongbao-backup-web/test/overview.test.js`：模型、路由、组件契约和禁止项测试。

### 主要修改文件

- 容量：`StorageVolumeEntity.java`、`StorageVolumeHealthScheduler.java`、`StorageVolumeLifecycleService.java`、`AdminStorageVolumeController.java` 和三份初始化 SQL。
- 概览 API：`AdminOverviewController.java`、`local-backup-v1.yaml`。
- 下钻：`OrderController.java`、`AdminBackupController.java`、`PersistentJobService.java` 及对应测试。
- 前端状态与路由：`routeStore.js`、`useBackupConsole.js`、`OverviewView.vue`、`StorageView.vue`、`BackupView.vue`、`OrdersView.vue`、`ColdStorageView.vue`、`AccountsView.vue`、`style-production.css`。

---

### Task 1: 持久化最后一次成功容量快照

**Files:**
- Create: `yigongbao-backup-server/sql/migration/2026-11-10-10-admin-overview-dashboard.sql`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeCapacitySnapshotPolicy.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/AdminOverviewMigrationContractTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeCapacitySnapshotPolicyTest.java`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/02-indexes.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthScheduler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthSchedulerTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleServiceTest.java`

**Interfaces:**
- Produces: `StorageVolumeEntity#getCapacityObservedAt()`；`StorageVolumeCapacitySnapshotPolicy.apply(StorageVolumeEntity, HealthSnapshot, LocalDateTime)`。
- Consumes: `StorageVolumeHealthService.HealthSnapshot` 的 `accessible/totalBytes/usableBytes/errorCode/errorMessage`。

- [ ] **Step 1: 写失败的迁移契约测试**

  在 `AdminOverviewMigrationContractTest` 断言迁移和三份初始化 SQL 均包含 `capacity_observed_at DATETIME(3)`；迁移需要用现有合法容量的 `last_probed_at` 回填，并包含以下索引：

  - `idx_bk_job_overview_finished(job_type,finished_at,id,is_deleted)`；
  - `idx_bk_job_order_overview(source_order_id,status,job_id,is_deleted)`；
  - `idx_bk_package_overview(backup_mode,status,snapshot_id,id,is_deleted)`；
  - `idx_bk_artifact_overview(artifact_type,lifecycle,state,artifact_id,storage_volume_id,is_deleted)`。

- [ ] **Step 2: 运行迁移契约测试并确认失败**

  Run: `mvn -Dtest=AdminOverviewMigrationContractTest test`

  Expected: FAIL，提示 `capacity_observed_at` 或索引不存在。

- [ ] **Step 3: 写容量快照策略失败测试**

  `StorageVolumeCapacitySnapshotPolicyTest` 至少固定：

  - 成功且 `total>0`、`0<=usable<=total` 时同时更新容量、`lastProbedAt` 和 `capacityObservedAt`；
  - 探测失败只更新 `lastProbedAt/lastError*`，保留已有容量和 `capacityObservedAt`；
  - 非法容量不覆盖最后合法快照；
  - 主动离线卷探测失败后仍可展示离线前快照。

- [ ] **Step 4: 运行容量策略测试并确认失败**

  Run: `mvn -Dtest=StorageVolumeCapacitySnapshotPolicyTest test`

  Expected: FAIL，类或方法尚不存在。

- [ ] **Step 5: 实现迁移、实体字段和统一策略**

  实现签名：

  ```java
  public final class StorageVolumeCapacitySnapshotPolicy {
      public static void apply(StorageVolumeEntity volume,
                               StorageVolumeHealthService.HealthSnapshot snapshot,
                               LocalDateTime probedAt);
      public static boolean legalCapacity(StorageVolumeHealthService.HealthSnapshot snapshot);
  }
  ```

  `apply` 始终更新探测尝试时间和错误；只有合法成功快照才更新 `totalBytes/usableBytes/capacityObservedAt`。迁移回填只对两项容量都非空且合法的记录执行。

- [ ] **Step 6: 替换重复容量更新逻辑**

  `StorageVolumeHealthScheduler` 和 `StorageVolumeLifecycleService` 统一调用策略；删除“探测失败就把容量设为 null”的代码。`AdminStorageVolumeController.StorageVolumeView` 增加 `capacityObservedAt`，但不执行新探测。

- [ ] **Step 7: 运行容量相关测试**

  Run: `mvn -Dtest=AdminOverviewMigrationContractTest,StorageVolumeCapacitySnapshotPolicyTest,StorageVolumeHealthSchedulerTest,StorageVolumeLifecycleServiceTest,AdminStorageVolumeControllerTest test`

  Expected: PASS。

- [ ] **Step 8: 阶段检查点**

  检查 `git diff --check`；不要提交，保留到最终单一提交。

---

### Task 2: 建立可信概览查询层

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewView.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewQueryMapper.java`
- Create: `yigongbao-backup-server/src/main/resources/mapper/AdminOverviewQueryMapper.xml`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminOverviewQueryMapperSqlContractTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminOverviewQueryProjectionTest.java`

**Interfaces:**
- Produces: `AdminOverviewQueryMapper` 的摘要、7 日结果、趋势字节、制品质量和近期任务批量查询；`AdminOverviewView` 全部只读响应类型。
- Consumes: Task 1 的 `capacityObservedAt` 字段以及现有订单、包、副本、卷、任务表。

- [ ] **Step 1: 定义响应 DTO 失败编译测试**

  在 `AdminOverviewQueryProjectionTest` 直接构造并断言以下记录的访问器：

  ```java
  AdminOverviewView
  AdminOverviewView.HealthView
  AdminOverviewView.SummaryView
  AdminOverviewView.TodayBackupView
  AdminOverviewView.StorageSummaryView
  AdminOverviewView.StorageVolumeView
  AdminOverviewView.AlertView
  AdminOverviewView.TrendPointView
  AdminOverviewView.RecentJobView
  AdminOverviewView.DataQualityView
  AdminOverviewView.DataQualityMessageView
  AdminOverviewView.SectionErrorView
  ```

  `AdminOverviewView` 顶层字段必须与需求响应结构一致：`observedAt/health/sections/summary/storageSummary/storageVolumes/activeAlerts/backupTrend/recentJobs/dataQuality/sectionErrors`。

- [ ] **Step 2: 运行 DTO 测试并确认失败**

  Run: `mvn -Dtest=AdminOverviewQueryProjectionTest test`

  Expected: FAIL，DTO 尚不存在。

- [ ] **Step 3: 定义 Mapper 精确接口**

  ```java
  ReadableSummaryRow selectReadableSummary();
  ManagedArtifactRow selectManagedArtifactSummary();
  List<BackupResultBucketRow> selectBackupResultBuckets(LocalDateTime from, LocalDateTime to);
  List<BackupBytesBucketRow> selectBackupBytesBuckets(LocalDateTime from, LocalDateTime to);
  ArtifactAlertAggregateRow selectArtifactAlertAggregate();
  List<RecentJobRow> selectRecentJobs(int limit);
  ```

  `BackupResultBucketRow` 按日期和最终订单状态返回；`RecentJobRow` 一次关联任务目标卷，不允许服务层逐任务查卷。

- [ ] **Step 4: 写 SQL 契约失败测试**

  `AdminOverviewQueryMapperSqlContractTest` 读取 XML 并断言：

  - 真实成果严格使用 `snapshot.id -> package.snapshot_id -> copy.artifact_id`；快照与包都为 `REAL`；
  - 可读副本只接受 `ACTIVE + PRESENT/VERIFIED` 和需求定义的卷真值表；合法 ACTIVE `ARCHIVE_GUARD` 有明确关联判断；
  - 使用 `ROW_NUMBER() OVER (PARTITION BY source_order_id ...)` 固定最新包及今日/每日最后尝试；
  - 正常离线冷备不算可读，但制品异常聚合排除健康主动离线冷备；
  - `managedArtifactBytes` 只统计能关联未删除卷的健康活动副本；
  - 趋势字节只统计 `REAL + AVAILABLE` 包，并按 `backup_completed_at` 归日；
  - 查询没有绝对路径、文件函数或用户配置原文。

- [ ] **Step 5: 运行 SQL 契约测试并确认失败**

  Run: `mvn -Dtest=AdminOverviewQueryMapperSqlContractTest test`

  Expected: FAIL，Mapper/XML 尚不存在。

- [ ] **Step 6: 实现 DTO、Mapper 和 XML 聚合**

  SQL 使用 MySQL 8 CTE 和窗口函数：订单数按不同 `source_order_id`；逻辑文件数只取每个订单最新可读真实包；今日/趋势按订单每天最后一次已终结真实任务结果；近期任务固定 `LIMIT 10`、`requested_at DESC,id DESC`。未知 `file_count/size/completed_at` 只返回质量计数，不使用 `COALESCE(...,0)` 伪造完整值。

- [ ] **Step 7: 增加真实/模拟交叉和离线冷备投影测试**

  在 `AdminOverviewQueryProjectionTest` 用 Mapper 行投影组装器或最小 H2 数据集固定 Review Focus 1、3：真实快照不能借用模拟包；只有健康离线冷备副本时“当前可用订单”为 0，但异常制品数也为 0。

- [ ] **Step 8: 运行查询层测试**

  Run: `mvn -Dtest=AdminOverviewQueryMapperSqlContractTest,AdminOverviewQueryProjectionTest test`

  Expected: PASS。

- [ ] **Step 9: 阶段检查点**

  检查查询数量固定、XML 无逐行子查询循环；不要提交。

---

### Task 3: 实现时间、容量、告警与健康策略

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/OverviewTimePolicy.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/OverviewStoragePolicy.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewAlertService.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/OverviewTimePolicyTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/OverviewStoragePolicyTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminOverviewAlertServiceTest.java`

**Interfaces:**
- Produces: 纯函数时间边界、卷投影、告警列表、数据质量严重度与最终健康级别。
- Consumes: Task 1 的容量时间、Task 2 DTO/查询行及 `RuntimeConfigService` 提供的已校验配置值。

- [ ] **Step 1: 写时间策略失败测试**

  覆盖：

  - `todayWindow(now)` 与 `sevenDayWindow(now)` 使用 `Asia/Shanghai`；
  - `nextBackupDue(lastSuccess,startTime,intervalDays,now)` 使用“成功相对周期”；跨日延迟成功重置下一周期；
  - 无成功记录时锚定今天 `startTime`；`now == due+120m` 即逾期；
  - 账号同步阈值为 `max(delayMinutes*3,60m)`；优先锚定成功、其次尝试、最后检查点创建时间。

- [ ] **Step 2: 写存储策略失败测试**

  覆盖：

  - 快照新鲜度 `max(healthInterval*3,15m)`；
  - 汇总排除离线、退役、停用、未知和过期卷；
  - 离线冷备保留 `lastKnown=true` 的展示值但不计入汇总；
  - `ARCHIVE_GUARD` 只有在唯一 ACTIVE 且关联合法运行中封存时允许读写；
  - 负数、`usable>total`、`total=0` 均为非法容量。

- [ ] **Step 3: 写告警失败测试**

  对第 9.3 节每个告警至少写一组触发/恢复测试，并额外固定：

  - 从未纳管卷时为 `SETUP_REQUIRED`，纳管后 ACTIVE 数量非 1 为 `CRITICAL`；
  - 退役 ACTIVE 告警，退役/停用非 ACTIVE 不产生普通卷告警；
  - 过期的 95% 旧快照只产生 `VOLUME_CAPACITY_UNKNOWN`；
  - `FAILED` 抑制备份逾期/同步陈旧；
  - 只有健康主动离线冷备副本不产生 `UNREADABLE_ARTIFACTS`；
  - 封存失败和同源卷 `REPAIR_REQUIRED` 合并；
  - `BLOCKING` 数据质量使健康为 `UNKNOWN`，`WARNING` 质量使健康至少为 `WARNING`；
  - 同一告警成员变化不改变 `alertKey`。

- [ ] **Step 4: 运行策略测试并确认失败**

  Run: `mvn -Dtest=OverviewTimePolicyTest,OverviewStoragePolicyTest,AdminOverviewAlertServiceTest test`

  Expected: FAIL，策略尚不存在。

- [ ] **Step 5: 实现纯策略接口**

  ```java
  public final class OverviewTimePolicy {
      DateWindow todayWindow(LocalDateTime now);
      DateWindow sevenDayWindow(LocalDateTime now);
      LocalDateTime nextBackupDue(LocalDateTime lastSuccess, LocalTime start, long intervalDays, LocalDateTime now);
      boolean accountSyncStale(BkSyncCursorEntity cursor, long delayMinutes, LocalDateTime now);
  }

  public final class OverviewStoragePolicy {
      boolean capacityFresh(StorageVolumeEntity volume, int healthIntervalMinutes, LocalDateTime now);
      StorageProjection project(StorageVolumeEntity volume, ArchiveGuardContext guard, LocalDateTime now, int healthIntervalMinutes);
      StorageSummaryView summarize(List<StorageProjection> volumes);
  }

  public final class AdminOverviewAlertService {
      AlertEvaluation evaluate(AlertContext context);
  }
  ```

  `AlertEvaluation` 同时返回排序去重后的告警和健康级别；告警文本为安全中文摘要，不包含根路径、marker JSON 或堆栈。

- [ ] **Step 6: 运行全部策略测试**

  Run: `mvn -Dtest=OverviewTimePolicyTest,OverviewStoragePolicyTest,AdminOverviewAlertServiceTest test`

  Expected: PASS。

- [ ] **Step 7: 阶段检查点**

  对照需求 §6、§8、§9、§13.3 逐项勾选；不要提交。

---

### Task 4: 编排概览接口并初始化同步检查点

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/account/AccountSyncCheckpointInitializer.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminOverviewServiceTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminOverviewControllerTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/account/AccountSyncCheckpointInitializerTest.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminOverviewController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/account/AccountSyncCheckpointService.java`
- Modify: `yigongbao-backup-server/src/main/resources/openapi/local-backup-v1.yaml`

**Interfaces:**
- Produces: `AdminOverviewService.overview(): AdminOverviewView` 和保持现有路径的 `GET /api/v1/admin/overview`。
- Consumes: Task 2 查询、Task 3 策略、现有 `StorageVolumeMapper/BkSyncCursorMapper/VolumeArchiveMapper`。

- [ ] **Step 1: 写服务编排失败测试**

  `AdminOverviewServiceTest` 固定：

  - 正常数据返回 7 个趋势点、最多 10 个任务和 `sections` 全部 AVAILABLE；
  - 单个查询分区抛异常时其他分区仍返回，失败分区数据为 `null`、状态 UNAVAILABLE，并写入安全 `sectionErrors`；
  - 告警分区失败时健康为 UNKNOWN，绝不返回 NORMAL；
  - 数据质量问题保留已知值和受影响数量；
  - 服务依赖中没有 `StorageProbe`，且测试不允许文件系统交互；
  - 近期任务未知类型不会使整个响应失败。

- [ ] **Step 2: 写控制器失败测试**

  使用 mock `AdminOverviewService` 验证管理员鉴权、统一 `ApiResponse`、请求 ID；反射或构造器断言 `AdminOverviewController` 不再依赖 `StorageProbe/PersistentConfigService/StorageVolumeMapper`，不再返回原始 `configs`。

- [ ] **Step 3: 写检查点初始化失败测试**

  `AccountSyncCheckpointInitializerTest` 覆盖：同步启用且不存在 `USER` 时创建 `IDLE`；已存在时不覆盖游标/成功时间；同步关闭时不创建；初始化异常有清晰日志且概览可通过缺失告警暴露。

- [ ] **Step 4: 运行测试并确认失败**

  Run: `mvn -Dtest=AdminOverviewServiceTest,AdminOverviewControllerTest,AccountSyncCheckpointInitializerTest test`

  Expected: FAIL，新服务尚不存在。

- [ ] **Step 5: 实现分区编排**

  `AdminOverviewService` 为 `summary/storage/activeAlerts/backupTrend/recentJobs` 分别执行有边界的数据库查询。内部使用：

  ```java
  private <T> SectionResult<T> section(String name, Supplier<T> query);
  ```

  捕获运行异常时记录 requestId、分区、耗时和异常类型，不输出配置原文与路径；返回安全错误码。数据质量问题与查询失败分别进入 `dataQuality` 和 `sectionErrors`。

- [ ] **Step 6: 实现控制器和启动初始化**

  控制器只保留管理员鉴权、调用和响应。`AccountSyncCheckpointService.ensureInitialized()` 使用事务锁安全创建；`AccountSyncCheckpointInitializer` 在应用 ready 前调用。更新 OpenAPI 的 Overview schema、五种 health level、分区状态和告警字段。

- [ ] **Step 7: 运行后端概览测试**

  Run: `mvn "-Dtest=AdminOverview*Test,Overview*Test,AccountSyncCheckpointInitializerTest" test`

  Expected: PASS。

- [ ] **Step 8: 阶段检查点**

  用 `rg "StorageProbe|probe\(" AdminOverview*.java` 确认概览链路不探测；不要提交。

---

### Task 5: 完成首页下钻的后端筛选契约

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminBackupController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderControllerFilterTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminBackupControllerTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobServiceTest.java`

**Interfaces:**
- Produces: 订单页 `backupMode/storageAccessible/storageIssue` 筛选；备份任务页 `finishedFrom/finishedTo/orderResult/jobId` 筛选。
- Consumes: `AdminOverviewView.AlertView.actionRoute/actionQuery` 约定的字符串参数。

- [ ] **Step 1: 写订单下钻失败测试**

  扩展 `OrderControllerFilterTest`：

  - `backupMode=REAL&storageAccessible=true` 只返回至少一个当前可读真实包的订单；
  - `storageIssue=UNREADABLE` 返回全部活动副本损坏、卷缺失或预期在线不可用的真实订单，但排除仅健康主动离线冷备；
  - 非法枚举返回参数错误；
  - 查询使用 `EXISTS` 批量谓词，不在 enrich 阶段先分页再过滤。

- [ ] **Step 2: 写备份任务下钻失败测试**

  `AdminBackupControllerTest/PersistentJobServiceTest` 固定日期半开区间、订单结果筛选、精确 jobId，以及原无筛选调用仍兼容。`orderResult=FAILED` 使用 `EXISTS bk_job_order`，不是只看任务总状态。

- [ ] **Step 3: 运行筛选测试并确认失败**

  Run: `mvn -Dtest=OrderControllerFilterTest,AdminBackupControllerTest,PersistentJobServiceTest test`

  Expected: FAIL，新参数尚未实现。

- [ ] **Step 4: 实现筛选参数和共享校验**

  控制器只接受固定枚举和 ISO 本地日期；`PersistentJobService.listBackupJobs` 增加筛选对象：

  ```java
  public record BackupJobFilter(Long jobId,
                                LocalDateTime finishedFrom,
                                LocalDateTime finishedTo,
                                String orderResult) {}
  ```

  保留现有无筛选重载供其他调用方使用。

- [ ] **Step 5: 运行下钻测试与订单回归测试**

  Run: `mvn -Dtest=OrderControllerFilterTest,OrderStorageLocationServiceTest,AdminBackupControllerTest,PersistentJobServiceTest test`

  Expected: PASS。

- [ ] **Step 6: 阶段检查点**

  确认所有 `actionQuery` 都能由目标接口消费；不要提交。

---

### Task 6: 建立前端概览状态、模型和结构化路由

**Files:**
- Create: `yigongbao-backup-web/src/overviewModel.js`
- Create: `yigongbao-backup-web/test/overview.test.js`
- Modify: `yigongbao-backup-web/src/stores/routeStore.js`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Modify: `yigongbao-backup-web/src/views/BackupView.vue`
- Modify: `yigongbao-backup-web/src/views/OrdersView.vue`
- Modify: `yigongbao-backup-web/src/views/ColdStorageView.vue`
- Modify: `yigongbao-backup-web/src/views/AccountsView.vue`

**Interfaces:**
- Produces: `navigate(target, query)`、`routeQuery`、`clearRouteQuery()`；概览 loading/error/lastSuccess 状态；下钻纯函数。
- Consumes: Task 4 API 响应和 Task 5 筛选参数。

- [ ] **Step 1: 写纯模型失败测试**

  `overview.test.js` 覆盖：

  ```js
  formatBytes(null) === '不可用'
  formatBytes(0) === '0 B'
  healthPresentation('SETUP_REQUIRED').label === '待完成存储初始化'
  normalizeSection('UNAVAILABLE', []).data === null
  buildSevenDayTrend([]).length === 7
  overviewActionTarget(alert).route === alert.actionRoute
  ```

  另断言未知状态安全回退、百分比总量为 0 时不产生 `NaN`、告警排序保持后端顺序。

- [ ] **Step 2: 写路由失败测试**

  模拟 `window.location.hash/localStorage`，验证：

  - `navigate('storage-volumes',{volumeId:'12'})` 生成可恢复的编码 hash；
  - hash 解析只接受白名单页面和键值，不执行 URL；
  - `clearRouteQuery()` 不改变当前页面；
  - 旧 `#orders` hash 继续兼容。

- [ ] **Step 3: 运行前端测试并确认失败**

  Run: `node --test test/overview.test.js test/architecture.test.js`

  Expected: FAIL，模型和查询路由不存在。

- [ ] **Step 4: 实现 `overviewModel.js`**

  导出 `formatBytes/formatPercent/healthPresentation/buildSevenDayTrend/overviewActionTarget`。函数不依赖 DOM，严格区分 `null` 与 `0`，不重新推导后端健康或告警。

- [ ] **Step 5: 扩展轻量路由**

  `routeStore` 使用 `URLSearchParams` 编解码 hash 查询；只保留字符串、数字和 ISO 日期。目标页挂载时消费参数：存储卷打开指定详情，冷备打开 archiveId，订单页应用真实/可读/异常筛选，备份页应用日期/结果/jobId，账号页定位同步状态。消费后调用 `clearRouteQuery()` 防止普通返回时重复应用。

- [ ] **Step 6: 完善概览加载状态**

  `useBackupConsole` 新增 `overviewLoading/overviewError/overviewLastSuccessAt`。`loadOverview()` 在 loading 时直接返回；失败保留旧 `overview`，保存 `message/code/requestId`；成功清除错误并更新时间。刷新不增加探测参数或写操作。

- [ ] **Step 7: 运行模型、路由和架构测试**

  Run: `node --test test/overview.test.js test/architecture.test.js`

  Expected: PASS。

- [ ] **Step 8: 阶段检查点**

  检查所有路由参数都经过编码且目标页只消费白名单；不要提交。

---

### Task 7: 实现管理概览页面与展示组件

**Files:**
- Create: `yigongbao-backup-web/src/components/overview/OverviewHealthPanel.vue`
- Create: `yigongbao-backup-web/src/components/overview/OverviewMetricGrid.vue`
- Create: `yigongbao-backup-web/src/components/overview/OverviewStoragePanel.vue`
- Create: `yigongbao-backup-web/src/components/overview/OverviewTrendPanel.vue`
- Create: `yigongbao-backup-web/src/components/overview/OverviewRecentJobs.vue`
- Modify: `yigongbao-backup-web/src/views/OverviewView.vue`
- Modify: `yigongbao-backup-web/src/style-production.css`
- Modify: `yigongbao-backup-web/test/overview.test.js`
- Modify: `yigongbao-backup-web/test/architecture.test.js`

**Interfaces:**
- Produces: 完整首页 UI；组件只通过 props/emits 工作。
- Consumes: Task 6 的模型、概览状态和 `navigate(target,query)`。

- [ ] **Step 1: 写组件契约失败测试**

  读取 Vue 源文件并断言：

  - 首页组合五个 overview 组件；
  - 只出现三张核心指标，不出现“订单备份覆盖率”“正在执行的任务”或 `JSON.stringify`；
  - 告警、卷状态和任务状态使用 `status-badge`；
  - 存在刷新 loading、错误 requestId、上次成功时间和保留旧数据文案；
  - 趋势含文本表格/aria-label 兜底，不只依赖颜色或图形；
  - 逐卷容量区明确“当前快照/离线前快照/数据过期/不计入汇总”。

- [ ] **Step 2: 运行组件契约测试并确认失败**

  Run: `node --test test/overview.test.js test/architecture.test.js`

  Expected: FAIL，组件尚不存在。

- [ ] **Step 3: 实现健康与指标组件**

  `OverviewHealthPanel` 按后端 `health.level` 展示 NORMAL/WARNING/CRITICAL/UNKNOWN/SETUP_REQUIRED；告警点击 emit `navigate`。`OverviewMetricGrid` 只显示三张卡，未知值显示“暂不可用”，文件卡同时展示逻辑文件数和受管物理数据量，今日卡显示成功/失败/跳过。

- [ ] **Step 4: 实现容量、趋势和近期任务组件**

  `OverviewStoragePanel` 展示在线总已用/可用/总量、使用率、参与/排除卷数和逐卷状态；过期高容量不使用危险进度色伪装成当前值。`OverviewTrendPanel` 用原生 SVG/CSS 显示 7 天成功/失败/跳过与逻辑字节，并提供同数据文本摘要。`OverviewRecentJobs` 最多 10 条，未知类型使用通用只读摘要。

- [ ] **Step 5: 重写 `OverviewView.vue`**

  页面顺序固定为：标题/刷新 → 健康告警 → 三指标 → 容量 → 趋势 → 近期任务。首次无数据用骨架/空态；失败且已有旧数据时保留组件并在顶部显示“刷新失败”；失败且无数据时显示错误态，不显示正常结论。

- [ ] **Step 6: 完成响应式和视觉语义**

  在 `style-production.css` 增加 `overview-*` 类：1366×768 下首屏可见健康和三指标；1100px 以下双列，700px 以下单列；HOT 暖色、COLD 冷色，告警严重度与全局颜色一致；所有可点击卡片具有 focus 样式。

- [ ] **Step 7: 运行前端测试和构建**

  Run: `npm test`

  Expected: 全部 PASS。

  Run: `npm run build`

  Expected: Vite build 成功，无 Vue 编译错误；仅允许既有 bundle size 提示。

- [ ] **Step 8: 阶段检查点**

  在 1366×768 与窄屏做浏览器检查；不要提交。

---

### Task 8: 全链路回归、性能验证、审查与单一提交

**Files:**
- Create: `docs/superpowers/verification/2026-11-10-admin-overview-dashboard.md`
- Modify: 仅修复本轮验证发现且属于本需求的问题。

**Interfaces:**
- Produces: 可交付实现、验证证据和唯一 Git 提交。
- Consumes: Task 1–7 全部结果。

- [ ] **Step 1: 运行后端全量测试**

  Run: `mvn test`

  Expected: 全部测试 PASS，失败数为 0。

- [ ] **Step 2: 运行前端全量测试和生产构建**

  Run: `npm test`

  Expected: 全部测试 PASS。

  Run: `npm run build`

  Expected: 构建成功。

- [ ] **Step 3: 执行数据库迁移与查询计划验证**

  在 MySQL 8 测试库从当前版本执行迁移，确认列、回填与索引成功；对摘要、趋势、制品异常和近期任务 SQL 运行 `EXPLAIN ANALYZE`，确认使用预期索引且没有按记录数增长的相关子查询。

- [ ] **Step 4: 执行性能基准**

  使用规格 §17.1 数据规模：10 万快照、10 万订单包、100 万资产/关系、30 万副本、10 万任务、100 万任务订单、100 卷；MySQL 8/4 vCPU/8 GB，10 并发、100 次预热、至少 1000 次采样。记录 `/api/v1/admin/overview` P50/P95/P99、错误率、数据库查询数和“无外部 I/O”证据；P95 必须 ≤1 秒。

- [ ] **Step 5: 执行浏览器验收**

  使用本地测试数据检查需求 §19 的全部 15 个场景，重点验证：离线冷备不告警、重新挂载后订单重新计入、ACTIVE 故障恢复、容量过期抑制、模拟备份排除、部分查询失败、中文状态、三指标禁止项和所有下钻。

- [ ] **Step 6: 写验证报告**

  `docs/superpowers/verification/2026-11-10-admin-overview-dashboard.md` 记录命令、测试数量、构建结果、性能数据、浏览器场景、已知非阻断提示和最终 Ready/Not Ready。不得写入凭据、绝对卷根路径或业务敏感数据。

- [ ] **Step 7: 进行最终代码审查并修复**

  审查重点：统计 SQL 去重、状态真值表、告警抑制、分区失败、容量快照保留、SQL 注入/路由参数、N+1、日志脱敏、前端未知值。任何 P1/P2 必须修复并重复相关测试；无剩余 P1/P2 才继续。

- [ ] **Step 8: 执行最终差异检查**

  Run: `git diff --check`

  Expected: 无输出。

  按仓库规范依次执行：

  ```powershell
  git status
  git diff HEAD
  git branch --show-current
  git log --oneline -10
  ```

  确认不暂存用户其他未提交改动。

- [ ] **Step 9: 创建唯一提交**

  仅暂存本计划实施直接相关文件，创建一个中文 Conventional Commit：

  ```powershell
  git commit -m "feat: 完善管理概览首页"
  ```

- [ ] **Step 10: 交付说明**

  向用户提供：提交号、需求/计划/验证文档链接、测试结果、迁移文件、首次启动注意事项和本地测试入口。

---

## 需求覆盖矩阵

| 需求区域 | 实施任务 |
|---|---|
| 页面信息架构与三指标 | Task 6、Task 7 |
| 可读订单、逻辑文件、物理字节口径 | Task 2、Task 3 |
| 今日结果与近 7 日趋势 | Task 2、Task 3、Task 7 |
| 容量成功快照、在线汇总、离线前快照 | Task 1、Task 3、Task 7 |
| 当前有效告警、去重、恢复与健康级别 | Task 3、Task 4、Task 7 |
| 自动备份/账号同步时间与初始化 | Task 3、Task 4 |
| 分区失败与数据质量 | Task 2、Task 3、Task 4、Task 6 |
| 业务下钻 | Task 5、Task 6、Task 7 |
| 权限、安全、日志和无外部 I/O | Task 4、Task 8 |
| 性能、全量测试和验收 | Task 8 |

## 完成门禁

实施只有在以下条件全部满足后才可标记完成：

- 需求覆盖矩阵无遗漏；
- 后端全量测试、前端全量测试和生产构建全部通过；
- MySQL 迁移、初始化 SQL 和查询计划验证通过；
- 概览 P95 在规定基准下不超过 1 秒；
- 首页请求证实不调用文件系统、NAS 或线上 API；
- 需求 §19 全部验收场景通过；
- 无剩余已知 P1/P2；
- 只创建一个符合仓库规范的最终提交。

## 计划自审结论

- **需求完整性：通过。** 需求文档中的指标口径、容量快照、当前有效告警、健康状态、趋势、近期任务、下钻、权限、性能和异常降级均已映射到明确任务与测试。
- **依赖顺序：通过。** 先建立容量事实和查询层，再实现策略、接口、下钻与前端；后续任务消费的接口均在前置任务中定义。
- **异常场景：通过。** 已覆盖首次初始化、部分查询失败、容量快照过期、正常离线冷备、模拟数据污染、未知任务类型和刷新失败保留旧数据。
- **可验证性：通过。** 每个任务均包含先失败测试、最小实现、回归命令和阶段检查点，最终另有迁移、查询计划、性能与浏览器验收门禁。
- **仓库适配：通过。** 测试命令与当前 Maven、`node:test` 脚本匹配；迁移和三套初始化 SQL 同步；执行阶段遵循只创建一个最终提交的项目规范。
- **实施边界：明确。** 本计划不引入告警持久表、图表依赖或首页实时探盘，也不扩展为覆盖率、正在执行任务等已排除功能。
