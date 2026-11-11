# 管理概览首页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于当前备份、降级备份、恢复、整盘封存、存储卷、清理和账号同步实现，交付可信、只读、可解释且可下钻的管理员概览首页。

**Architecture:** 后端建立独立概览 DTO、聚合查询、共享卷访问策略、时间/容量/告警策略和分区编排服务；首页只读持久化数据库事实，不执行任何文件系统或线上调用。前端以 Element Plus 组件组合健康、告警、核心指标、趋势、存储、近期活动和自动化状态，并通过白名单 hash 查询参数打开现有任务弹窗和详情抽屉。

**Tech Stack:** Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.8、MySQL 8、JUnit 5/Mockito/AssertJ、Vue 3、Element Plus、Vite、Node.js `node:test`。

**Spec:** `docs/superpowers/specs/2026-11-10-admin-overview-dashboard-requirements.md`

## Global Constraints

- 应用在任何业务 Bean 和时间事实写入前，把进程、JDBC 会话、Jackson 和初始化 SQL 统一为 `Asia/Shanghai/+08:00`；概览另用可注入 `Clock` 做确定性测试，前端不按浏览器时区重算。
- 正式完整备份和成功降级备份都计入生产备份；模拟备份全部排除。
- 首页请求不得调用 `StorageProbe`、Marker 校验、文件系统、NAS 或线上接口。
- 首页只读最后成功容量快照；探测失败保留合法历史容量。
- 首页、下载、恢复、副本解析和备份预检必须复用统一卷访问策略。
- 正常 `COLD/OFFLINE/READ_ONLY/ENABLED/IDLE` 冷备卷不告警；`REPAIR_REQUIRED` 不属于正常离线；卷重新转为 HOT 后封存历史仍保留。
- 当前告警只来自当前事实；历史失败任务不自动成为告警。
- 分区失败返回 `UNAVAILABLE + null`，不得以零值或空数组伪装。
- 不增加大型图表依赖；近 7 日图表使用 Vue + 原生 SVG/CSS。
- 复杂统计必须使用固定数量的聚合或批量查询，禁止 N+1。
- 迁移、`sql/init/01-schema.sql`、`02-indexes.sql` 和完整初始化 SQL 必须同步。
- 遵守仓库 Git 规范：实施完成后只创建一个中文 Conventional Commit；各任务检查点不单独提交。

## Review Focus

1. **降级备份口径**：`REAL + INCOMPLETE` 必须计入可恢复订单并单独拆分，不能排除或伪装为完整；Task 3、Task 4、Task 8 覆盖。
2. **正常离线冷备**：当前不计可恢复但不告警，重新接入后自动恢复计数；Task 2、Task 3、Task 4 覆盖。
3. **合法 `ARCHIVE_GUARD`**：匹配活动封存的保障卷与普通异常操作状态不同，首页、下载、恢复和备份预检结果必须一致；Task 2、Task 4 覆盖。
4. **容量失败快照**：探测失败保留最后成功容量，在线汇总排除过期值，离线冷备标记为离线前快照；Task 1、Task 4、Task 8 覆盖。
5. **历史与当前分离**：历史恢复失败、封存失败或卷后来转回 HOT 不得造成永久告警或历史丢失；Task 4、Task 5、Task 8 覆盖。

---

## 计划文件结构

### 后端新增

- `yigongbao-backup-server/sql/migration/2026-11-11-13-admin-overview-dashboard.sql`
- `.../storage/volume/StorageVolumeCapacitySnapshotPolicy.java`
- `.../storage/volume/StorageVolumeAccessPolicy.java`
- `.../storage/volume/StorageVolumeAccessSqlPredicate.java`
- `.../common/time/BusinessClock.java`
- `.../common/time/BusinessTimeConfiguration.java`
- `.../common/time/BusinessTimeCodec.java`
- `.../admin/AdminOverviewView.java`
- `.../admin/AdminOverviewQueryMapper.java`
- `.../admin/AdminOverviewQuerySqlProvider.java`
- `.../admin/OverviewTimePolicy.java`
- `.../admin/OverviewStoragePolicy.java`
- `.../admin/AdminOverviewAlertService.java`
- `.../admin/AdminOverviewActivityService.java`
- `.../admin/AdminOverviewService.java`
- 对应后端测试类。

### 前端新增

- `yigongbao-backup-web/src/overviewModel.js`
- `yigongbao-backup-web/src/components/overview/OverviewHealthPanel.vue`
- `yigongbao-backup-web/src/components/overview/OverviewAlertList.vue`
- `yigongbao-backup-web/src/components/overview/OverviewMetricGrid.vue`
- `yigongbao-backup-web/src/components/overview/OverviewTrendPanel.vue`
- `yigongbao-backup-web/src/components/overview/OverviewStoragePanel.vue`
- `yigongbao-backup-web/src/components/overview/OverviewRecentActivity.vue`
- `yigongbao-backup-web/src/components/overview/OverviewAutomationPanel.vue`
- `yigongbao-backup-web/test/overview.test.js`

### 主要修改

- 后端：应用启动时区、数据源/Jackson 配置、容量实体/调度/生命周期、制品解析、备份预检、概览控制器、订单与任务筛选、OpenAPI、初始化 SQL。
- 前端：`routeStore.js`、`useBackupConsole.js`、`useRestoreTasks.js`、`OverviewView.vue`、目标业务页面、`StatusBadge.js`、生产样式和架构测试。

---

### Task 1: 建立最后成功容量快照事实

**Files:**
- Create: `yigongbao-backup-server/sql/migration/2026-11-11-13-admin-overview-dashboard.sql`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeCapacitySnapshotPolicy.java`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/02-indexes.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthScheduler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Test: `.../storage/volume/AdminOverviewMigrationContractTest.java`
- Test: `.../storage/volume/StorageVolumeCapacitySnapshotPolicyTest.java`

**Interfaces:**
- Produces: `StorageVolumeEntity#getCapacityObservedAt()`；`StorageVolumeCapacitySnapshotPolicy.apply(StorageVolumeEntity, HealthSnapshot, LocalDateTime)`。
- Consumes: `StorageVolumeHealthService.HealthSnapshot`。

- [ ] **Step 1: 写迁移契约失败测试**

  断言迁移同时包含 `capacity_observed_at`、回填和概览索引；`01-schema.sql` 包含容量列，`02-indexes.sql` 包含概览索引，`yigongbao-backup-full-init.sql` 同时包含两者；回填只处理合法的 `total_bytes/usable_bytes`。

- [ ] **Step 2: 运行迁移契约测试并确认失败**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=AdminOverviewMigrationContractTest test`

  Expected: FAIL，缺少列或索引。

- [ ] **Step 3: 写容量快照策略失败测试**

  固定以下行为：只有 `totalBytes > 0` 且 `0 <= usableBytes <= totalBytes` 才是合法成功，并同时更新容量、探测时间和成功观测时间；失败只更新探测时间和错误；非法容量不覆盖历史；离线冷备保留历史容量。使用率用防溢出的十进制定点运算四舍五入保留 1 位小数。

- [ ] **Step 4: 实现容量字段、迁移和策略**

  ```java
  public final class StorageVolumeCapacitySnapshotPolicy {
      public static void apply(StorageVolumeEntity volume,
                               StorageVolumeHealthService.HealthSnapshot snapshot,
                               LocalDateTime observedAt);
      public static boolean legal(StorageVolumeHealthService.HealthSnapshot snapshot);
  }
  ```

- [ ] **Step 5: 替换容量写入逻辑**

  `StorageVolumeHealthScheduler`、`StorageVolumeLifecycleService` 和主动探测路径统一调用策略；列表 DTO 增加 `capacityObservedAt`。

- [ ] **Step 6: 运行容量与卷回归测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=AdminOverviewMigrationContractTest,StorageVolumeCapacitySnapshotPolicyTest,StorageVolumeHealthSchedulerTest,StorageVolumeLifecycleServiceTest,AdminStorageVolumeControllerTest test`

  Expected: PASS。

- [ ] **Step 7: 阶段检查点**

  Run: `git diff --check`

  Expected: 无输出；不提交。

---

### Task 2: 统一卷访问与封存保护语义

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeAccessPolicy.java`
- Create: `.../storage/volume/StorageVolumeAccessSqlPredicate.java`
- Modify: `.../storage/location/ArtifactReplicaResolver.java`
- Modify: `.../backup/BackupTargetPreflightService.java`
- Modify: `.../storage/volume/ActiveVolumeSelectionService.java`
- Modify: `.../storage/volume/StorageVolumeLifecycleService.java`
- Test: `.../storage/volume/StorageVolumeAccessPolicyTest.java`
- Test: existing resolver, preflight and lifecycle tests.

**Interfaces:**
- Produces: `StorageVolumeAccessPolicy.AccessDecision`、`readable(...)`、`writable(...)`、`standbyReady(...)`、`expectedOffline(...)`，以及同源的可读性 SQL 谓词。
- Consumes: `StorageVolumeEntity` 和可选 `ArchiveGuardContext`。

- [ ] **Step 1: 写共享策略失败测试**

  覆盖 HOT/COLD/IMPORT、ONLINE/OFFLINE/UNAVAILABLE、ACTIVE/STANDBY/READ_ONLY/DISABLED、RETIRED、全部操作状态、正常离线冷备、合法和非法 `ARCHIVE_GUARD`。对完整状态矩阵同时执行 Java 判定和 SQL 谓词集成查询，断言结果逐项一致。

- [ ] **Step 2: 定义策略接口**

  ```java
  public final class StorageVolumeAccessPolicy {
      public AccessDecision readable(StorageVolumeEntity volume, ArchiveGuardContext guard);
      public AccessDecision writable(StorageVolumeEntity volume, ArchiveGuardContext guard);
      public boolean standbyReady(StorageVolumeEntity volume, boolean capacityFresh, int threshold);
      public boolean expectedOffline(StorageVolumeEntity volume);
      public StorageVolumeAccessSqlPredicate readableSql(SqlAliases aliases);

      public record ArchiveGuardContext(Long archiveId, Long successorVolumeId, String phase, boolean active) {}
      public record AccessDecision(boolean allowed, String code, String message) {}
  }
  ```

  Java 判定与 SQL 谓词必须由同一规则定义构建，SQL Provider 只能调用 `readableSql(...)`，不得自行拼写第二套卷状态真值表。谓词必须支持关联活动封存上下文，供后续订单列表在数据库分页和 `COUNT` 前使用。

- [ ] **Step 3: 实现并接入制品副本解析**

  批量读取相关 `operation_ref_id` 的封存记录，避免逐副本查询；`ArtifactReplicaResolver` 使用策略决定当前可读性。

- [ ] **Step 4: 接入备份预检和卷选择**

  状态判断复用策略，Marker、实际容量和可写探测仍只发生在创建/执行写任务的原业务路径，不进入首页。

- [ ] **Step 5: 运行共享策略回归测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=StorageVolumeAccessPolicyTest,StorageVolumeAccessSqlPredicateTest,ArtifactReplicaResolverTest,BackupTargetPreflightServiceTest,ActiveVolumeSelectionServiceTest,StorageVolumeLifecycleServiceTest test`

  Expected: PASS，首页后续可以复用相同决策。

- [ ] **Step 6: 阶段检查点**

  搜索并审查仍然散落的 `operationStatus == IDLE` 可用性判断和 SQL 状态真值表；保留纯展示判断，业务判断必须迁移到共享策略。确认 Java/SQL 状态矩阵等价；不提交。

---

### Task 3: 建立概览响应和聚合查询层

**Files:**
- Create: `.../admin/AdminOverviewView.java`
- Create: `.../admin/AdminOverviewQueryMapper.java`
- Create: `.../admin/AdminOverviewQuerySqlProvider.java`
- Test: `.../admin/AdminOverviewProjectionTest.java`
- Test: `.../admin/AdminOverviewQueryMapperSqlContractTest.java`

**Interfaces:**
- Produces: 完整只读 DTO；可恢复性候选行、今日结束任务中的订单结果、7 日结果、趋势容量和近期任务聚合行。
- Consumes: Task 1 容量字段、Task 2 访问策略需要的批量事实。

- [ ] **Step 1: 写 DTO 投影失败测试**

  固定顶层字段：`observedAt/health/sections/summary/storage/activeAlerts/backupTrend/automation/recentActivities/dataQuality/sectionErrors`；固定规格中的完整/降级拆分和活动模型字段。

- [ ] **Step 2: 定义 Mapper 接口**

  ```java
  List<ArtifactReplicaCandidateRow> selectArtifactReplicaCandidates();
  ManagedArtifactSummaryRow selectManagedArtifactSummary();
  List<BackupResultBucketRow> selectBackupResultBuckets(LocalDateTime from, LocalDateTime to);
  List<BackupBytesBucketRow> selectBackupBytesBuckets(LocalDateTime from, LocalDateTime to);
  List<RecentJobRow> selectRecentJobs(int limit);
  RecentJobRow selectLatestAutoCleanup();
  ```

  `ArtifactReplicaCandidateRow` 至少保留 `sourceOrderId/snapshotId/packageId/backupCompletedAt/completeness/fileCount/copyId/copyState/copyLifecycle/volumeId`、全部卷状态、`operationRefId` 和封存上下文。Java 投影先调用 Task 2 共享策略，再按订单归并；制品异常复用同一分类结果。

- [ ] **Step 3: 写 SQL 契约失败测试**

  断言项目仍无 Mapper XML；真实链路为 snapshot → package → active copy → volume；候选行保留 Java 决策所需维度；完整/降级按 `backup_completeness`；模拟排除；订单结果按每日每订单最终尝试；近期任务批量关联订单和卷；SQL 不包含卷可读真值表、文件函数或绝对路径。

- [ ] **Step 4: 实现 DTO、注解 Mapper 和 SQL Provider**

  遵守 `application.yml` 的“禁止 Mapper XML”约束，使用 `@Select`/`@SelectProvider` 与 MySQL 8 CTE、窗口函数。SQL 只筛选正式候选并保留逐包/副本决策维度；通过左连接或等价方式保留零活动副本的正式包。Java 投影用 Task 2 策略完成 `readable → abnormalUnreadable → temporarilyOffline` 互斥分类，再按完成时间非空优先、`backupCompletedAt/snapshotId/packageId` 降序选择最新可读代表包。查询数量必须固定。

- [ ] **Step 5: 增加完整/降级/模拟与离线冷备投影测试**

  覆盖规格 Review Focus 1、2，并固定物理副本容量与逻辑文件数量不混算。补充旧完整+新降级、新包不可读但旧包可读、离线冷备+损坏热备、零活动副本，以及 `complete + incomplete = total`。

- [ ] **Step 6: 运行查询层测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=AdminOverviewProjectionTest,AdminOverviewQueryMapperSqlContractTest test`

  Expected: PASS。

- [ ] **Step 7: 阶段检查点**

  检查注解/Provider SQL 无逐记录相关子查询、无未索引无界扫描、无卷可读性真值表；用 10 万正式包/30 万副本验证固定查询数和候选投影耗时；不提交。

---

### Task 4: 实现时间、存储、活动和告警策略

**Files:**
- Create: `.../common/time/BusinessClock.java`
- Create: `.../common/time/BusinessTimeConfiguration.java`
- Create: `.../common/time/BusinessTimeCodec.java`
- Create: `.../admin/OverviewTimePolicy.java`
- Create: `.../admin/OverviewStoragePolicy.java`
- Create: `.../admin/AdminOverviewActivityService.java`
- Create: `.../admin/AdminOverviewAlertService.java`
- Modify: `.../BackupServerApplication.java`
- Modify: `.../resources/application.yml`
- Modify: `yigongbao-backup-server/.env.example`
- Modify: `.../backup/ScheduledBackupService.java`
- Modify: `.../config/PersistentConfigService.java`
- Modify: `.../job/PersistentJobService.java`
- Modify: `.../cleanup/CleanupTaskService.java`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/04-functional-config-defaults.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Test: 时间配置、调度器及对应四个策略/服务测试类。

**Interfaces:**
- Produces: 日期窗口、计划槽位、存储投影、统一活动、当前告警和健康级别。
- Consumes: Task 1–3 产生的事实与 DTO。

- [ ] **Step 1: 写时间策略失败测试**

  测试先把宿主默认时区设为 UTC，再初始化应用时区，覆盖进程/JDBC/Jackson/三个可独立初始化 SQL 入口均为上海、已有 `LocalDateTime.now()` 和 MyBatis 填充写入上海时间、数据库 `DATETIME` 与 `Instant` 双向转换、今日/7 日与 UTC 日期边界。自动化覆盖当前有效开关配置 `changed_at`、首次应执行时间、配置时间缺失、正常周期、错过不补跑，以及强制扫描成功后按“成功日期 + 周期天数 + 配置开始时间”计算下个槽位。

- [ ] **Step 2: 定义时间策略**

  ```java
  public final class OverviewTimePolicy {
      public OverviewTimePolicy(BusinessClock businessClock);
      public DateWindow today();
      public DateWindow sevenDays();
      public BackupScheduleState backupSchedule(BackupScheduleInput input);
      public boolean accountSyncStale(AccountSyncInput input);
      public boolean capacityFresh(LocalDateTime capacityObservedAt, int intervalMinutes);
  }
  ```

  `BackupServerApplication.main` 在调用 `SpringApplication.run` 前执行 `BusinessTimeConfiguration.initializeProcessTimezone()`；`application.yml` 固定 Jackson 时区，并为每条数据库连接执行 `SET time_zone = '+08:00'`；`01-schema.sql`、`04-functional-config-defaults.sql` 和 `yigongbao-backup-full-init.sql` 各自在开头设置会话时区并逐文件做契约测试；`.env.example` 不再声明 UTC。`BusinessClock` 仅为概览纯策略提供可替换 `Clock`，生产固定 `ZoneId.of("Asia/Shanghai")`。`BusinessTimeCodec` 统一数据库 `DATETIME` 与 `Instant` 的上海时区转换；审查并替换 `PersistentConfigService`、`PersistentJobService`、`CleanupTaskService` 中把数据库本地时间直接按 UTC 解释的代码。`ScheduledBackupService` 的订单截止时间属于外部 UTC 协议，但必须先把上海 `LocalDateTime` 绑定 `Asia/Shanghai`，再转换为同一瞬间的 UTC `OffsetDateTime`，禁止直接 `atOffset(ZoneOffset.UTC)`；测试精确断言 `2026-11-04 00:00 Asia/Shanghai` 转为 `2026-11-03T16:00Z`。这样无需一次性改写全部现有 `LocalDateTime.now()` 调用，但所有权威时间写入与读取都遵守同一语义。

  `BackupScheduleInput` 和 `AccountSyncInput` 必须包含当前有效开关配置行的 `changed_at` 作为 `enabledAt`。自动备份首次应执行时间是不早于 `enabledAt` 的第一个配置计划分钟；账号同步是 `enabledAt + syncDelay`。配置行或时间缺失时返回 `UNKNOWN` 数据质量状态，不产生缺失检查点告警。

- [ ] **Step 3: 写存储与活动策略失败测试**

  固定活动卷、备用卷、冷备介质、在线容量、成功封存历史，以及 `bk_job` 与 `bk_volume_archive` 合并排序、未知任务类型降级展示。

- [ ] **Step 4: 写告警失败测试**

  对规格全部告警逐一覆盖触发、恢复、抑制、去重和稳定 `alertKey`；特别覆盖检查点缺失抑制失败/错过、明确失败抑制同槽位错过、首次启用未到首次应执行时间不告警、启用时间未知返回 `UNKNOWN`、历史失败任务不告警、封存失败但卷已修复不告警、正常离线冷备不告警。

- [ ] **Step 5: 实现四个纯策略/服务**

  `AdminOverviewActivityService` 固定最多返回 10 条；`AdminOverviewAlertService.evaluate(AlertContext)` 同时返回排序后的告警和健康级别。

- [ ] **Step 6: 运行策略测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=BusinessTimeConfigurationTest,BusinessTimeCodecTest,OverviewTimePolicyTest,ScheduledBackupServiceTest,MybatisFillConfigTest,PersistentConfigServiceTest,PersistentJobServiceTest,CleanupTaskServiceTest,OverviewStoragePolicyTest,AdminOverviewActivityServiceTest,AdminOverviewAlertServiceTest test`

  Expected: PASS。

- [ ] **Step 7: 阶段检查点**

  对照规格 §§6–12 检查每个字段和规则都有测试；不提交。

---

### Task 5: 编排概览服务和接口

**Files:**
- Create: `.../admin/AdminOverviewService.java`
- Modify: `.../admin/AdminOverviewController.java`
- Modify: `.../resources/openapi/local-backup-v1.yaml`
- Test: `.../admin/AdminOverviewServiceTest.java`
- Test: `.../admin/AdminOverviewControllerTest.java`

**Interfaces:**
- Produces: `AdminOverviewService.overview(boolean refresh, String requestId): AdminOverviewView`。
- Consumes: Task 1–4 全部能力及现有配置、卷、封存和同步 Mapper。

- [ ] **Step 1: 写服务编排失败测试**

  覆盖正常响应、7 个趋势点、最多 10 条活动、完整/降级摘要、单分区失败、告警分区失败、数据质量、短缓存和 `refresh=true` 绕过缓存。连续两个不同 requestId 命中同一业务缓存时，响应及 `sectionErrors` 必须使用各自当前 requestId，同时保持首次查询的真实 `observedAt`，不能伪报数据新鲜度。

- [ ] **Step 2: 写控制器失败测试**

  验证管理员鉴权、统一 `ApiResponse`、requestId、refresh 参数；反射断言控制器不依赖 `StorageProbe/PersistentConfigService/StorageVolumeMapper`，响应不含原始 `configs`。

- [ ] **Step 3: 实现分区编排和缓存**

  ```java
  public AdminOverviewView overview(boolean refresh, String requestId);
  private <T> SectionResult<T> section(String name, Supplier<T> query, String requestId);
  ```

  缓存最长 30 秒并保存业务分区结果及其真实 `observedAt`；命中时只重新绑定当前 requestId 和请求关联错误，不重置观察时间。分区异常记录 requestId、分区、耗时和异常类型，返回脱敏安全错误；正常请求不写审计、不输出逐记录 INFO 日志。

- [ ] **Step 4: 重写控制器和 OpenAPI**

  删除实时探测、阈值解析和配置列表返回；补齐所有 DTO schema、健康级别、分区状态和活动类型。

- [ ] **Step 5: 运行概览后端测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml "-Dtest=AdminOverview*Test,Overview*Test" test`

  Expected: PASS。

- [ ] **Step 6: 验证概览链路无外部 I/O**

  使用依赖图测试与 `rg "StorageProbe|probe\(|Files\.|Path\.of"` 检查概览包；任何外部 I/O 依赖都必须移除。不提交。

---

### Task 6: 完成首页下钻的后端筛选

**Files:**
- Create: `.../order/OrderOverviewFilterMapper.java`
- Create: `.../order/OrderOverviewFilterSqlProvider.java`
- Modify: `.../order/OrderController.java`
- Modify: `.../admin/AdminBackupController.java`
- Modify: `.../job/PersistentJobService.java`
- Modify: `.../resources/openapi/local-backup-v1.yaml`
- Test: `.../order/OrderControllerFilterTest.java`
- Test: `.../admin/AdminBackupControllerTest.java`
- Test: `.../job/PersistentJobServiceTest.java`
- Modify: `.../order/OrderOpenApiContractTest.java`
- Modify: `.../admin/AdminJobOpenApiContractTest.java`

**Interfaces:**
- Produces: 订单页 `backupMode/storageAccessible/storageIssue`；备份任务页 `jobId/finishedFrom/finishedTo/orderResult`。
- Consumes: 概览 `actionRoute/actionQuery`。

- [ ] **Step 1: 写订单下钻失败测试**

  `backupMode=REAL&storageAccessible=true` 只返回当前可读正式订单；`storageIssue=UNREADABLE` 排除正常离线冷备并返回异常不可读订单；非法枚举返回参数错误。

- [ ] **Step 2: 写备份任务下钻失败测试**

  固定 jobId、日期半开区间和订单结果筛选；`orderResult` 通过任务订单事实筛选，不只看任务总状态。

- [ ] **Step 3: 实现数据库分页前的共享策略筛选**

  ```java
  public record BackupJobFilter(Long jobId,
                                LocalDateTime finishedFrom,
                                LocalDateTime finishedTo,
                                String orderResult) {}
  ```

  `OrderOverviewFilterSqlProvider` 通过 Task 2 的 `StorageVolumeAccessPolicy.readableSql(...)` 构建 `EXISTS/NOT EXISTS` 条件，在数据库执行 `COUNT` 和分页之前完成 `storageAccessible/storageIssue` 分类；不得复制卷状态真值表，不得先分页后 Java 过滤，也不得全量加载订单。查询保持 `order_created_at DESC, id DESC` 稳定排序，返回准确 `total`。保留现有无筛选重载，避免破坏现有页面。

- [ ] **Step 4: 同步 OpenAPI 契约**

  为订单页和备份任务页补齐全部筛选参数、枚举、半开日期区间和错误响应；增加契约测试，确保实现参数与 OpenAPI 一致。订单筛选测试覆盖跨页稳定性、准确 total、空页、正常离线与异常不可读混合、Java/SQL 策略等价和 10 万订单查询计划。

- [ ] **Step 5: 运行筛选和订单回归测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=OrderControllerFilterTest,AdminBackupControllerTest,PersistentJobServiceTest,OrderStorageLocationServiceTest,OrderOpenApiContractTest,AdminJobOpenApiContractTest test`

  Expected: PASS。

- [ ] **Step 6: 阶段检查点**

  逐项验证概览产生的 query 都被目标接口消费；不提交。

---

### Task 7: 建立前端概览模型、状态和结构化路由

**Files:**
- Create: `yigongbao-backup-web/src/overviewModel.js`
- Create: `yigongbao-backup-web/test/overview.test.js`
- Modify: `.../src/stores/routeStore.js`
- Modify: `.../src/composables/useBackupConsole.js`
- Modify: `.../src/composables/useRestoreTasks.js`
- Modify: 订单、备份、恢复、冷备、存储卷、账号和清理页面。

**Interfaces:**
- Produces: `navigate(target, query)`、`routeQuery`、`consumeRouteQuery(keys)`；概览加载/错误/上次成功状态；格式化和下钻纯函数。
- Consumes: Task 5 响应和 Task 6 后端筛选。

- [ ] **Step 1: 写模型失败测试**

  覆盖 null 与 0、字节和百分比、五种健康状态、完整/降级拆分、7 日补零、未知活动类型、告警顺序和受控下钻。

- [ ] **Step 2: 写路由失败测试**

  验证查询参数编码、页面与键白名单、旧 hash 兼容、一次性消费后不重复弹窗，以及恶意 route/query 不能生成可执行 URL。

- [ ] **Step 3: 实现 `overviewModel.js` 和路由仓库**

  纯函数不依赖 DOM，不重新推导健康或告警。路由仓库统一解析 hash，不再使用各页面临时 `sessionStorage` 聚焦方案。

- [ ] **Step 4: 实现概览加载状态**

  `loadOverview({ refresh })` 防止并发；失败保留旧数据并保存 code/message/requestId；成功更新时间并清除错误。

- [ ] **Step 5: 接入目标页一次性聚焦**

  备份/恢复打开 jobId，冷备打开 archiveId，存储卷打开 volumeId，账号定位同步状态，订单和备份应用筛选；消费后清理 query。

- [ ] **Step 6: 运行前端模型和路由测试**

  Run: `node --test yigongbao-backup-web/test/overview.test.js yigongbao-backup-web/test/architecture.test.js`

  Expected: PASS。

- [ ] **Step 7: 阶段检查点**

  确认关闭并重新进入冷备页不会自动打开旧抽屉；不提交。

---

### Task 8: 实现管理概览页面

**Files:**
- Create: 七个 `src/components/overview/*.vue` 组件。
- Modify: `src/views/OverviewView.vue`
- Modify: `src/components/StatusBadge.js`
- Modify: `src/style-production.css`
- Modify: `test/overview.test.js`
- Modify: `test/architecture.test.js`

**Interfaces:**
- Produces: 完整首页 UI 和组件事件。
- Consumes: Task 7 模型、状态和导航。

- [ ] **Step 1: 写组件契约失败测试**

  断言页面组合全部组件；不存在 `JSON.stringify`、固定“服务正常”、覆盖率或运行任务 KPI；存在刷新失败覆盖状态、requestId、上次成功时间、中文状态和文本图表兜底。

- [ ] **Step 2: 实现健康、告警和三指标**

  健康结论完全使用后端 `health.level`；Top 3 告警显示处理入口；三卡明确完整/降级、逻辑/物理和“今日结束任务中的订单结果”口径。

- [ ] **Step 3: 实现趋势与存储面板**

  使用两个独立 SVG/CSS 图表；容量区区分当前快照、离线前快照、过期和不可用，活动/备用/冷备层级清晰。

- [ ] **Step 4: 实现近期活动与自动化状态**

  已知类型下钻到现有详情；未知类型只读展示；自动备份明确“错过不补跑”，关闭状态不使用危险色。

- [ ] **Step 5: 重写 `OverviewView.vue` 和状态字典**

  页面顺序严格遵守规格；扩充 `StatusBadge` 的健康、降级、自动清理和历史任务类型中文映射。

- [ ] **Step 6: 实现响应式和无障碍样式**

  检查 1366×768、1100px、700px；所有可点击卡和图表点支持键盘焦点，颜色不是唯一信息载体。

- [ ] **Step 7: 运行前端测试和构建**

  Run: `npm --prefix yigongbao-backup-web test`

  Expected: 全部 PASS。

  Run: `npm --prefix yigongbao-backup-web run build`

  Expected: Vite 构建成功。

- [ ] **Step 8: 阶段检查点**

  浏览器验证正常、零数据、未知、部分失败、整体失败和过期旧数据；不提交。

---

### Task 9: 全链路验证、性能审查和单一提交

**Files:**
- Create: `docs/superpowers/verification/2026-11-11-admin-overview-dashboard.md`
- Modify: 仅修复本轮验证发现且属于本需求的问题。

**Interfaces:**
- Produces: 验证证据和唯一 Git 提交。
- Consumes: Task 1–8。

- [ ] **Step 1: 运行后端全量测试**

  Run: `mvn -f yigongbao-backup-server/pom.xml test`

  Expected: 全部 PASS。

- [ ] **Step 2: 运行前端全量测试和生产构建**

  Run: `npm --prefix yigongbao-backup-web test`

  Run: `npm --prefix yigongbao-backup-web run build`

  Expected: 全部成功。

- [ ] **Step 3: 验证数据库迁移与查询计划**

  在 MySQL 8 测试库执行迁移，对副本候选、今日任务结果、趋势和近期任务 SQL 运行 `EXPLAIN ANALYZE`，确认使用预期索引且无相关子查询随记录数增长。

- [ ] **Step 4: 执行性能基准**

  按规格数据量和环境记录 P50/P95/P99、错误率、数据库查询数和无外部 I/O 证据；P95 必须 ≤1 秒。

- [ ] **Step 5: 执行 22 个验收场景**

  重点验证完整/降级/模拟、正常离线冷备、重新接入、卷转回 HOT 后历史、错过计划不补跑、历史失败不告警、分区失败和所有下钻。

- [ ] **Step 6: 写验证报告并进行最终审查**

  记录命令、测试数、构建、迁移、性能、浏览器场景和结论；审查统计去重、64 位溢出与百分比舍入、访问策略、告警抑制、OpenAPI、路由白名单、错误脱敏、缓存 requestId、首页不写审计、正常请求日志量和 N+1。

- [ ] **Step 7: 执行最终差异检查**

  Run: `git diff --check`

  按项目规范依次执行：

  ```powershell
  git status
  git diff HEAD
  git branch --show-current
  git log --oneline -10
  ```

- [ ] **Step 8: 创建唯一提交**

  只暂存与本需求直接相关的实现、测试、迁移和文档文件，提交：

  ```powershell
  git commit -m "feat: 完善管理概览首页"
  ```

---

## 需求覆盖矩阵

| 需求区域 | 实施任务 |
|---|---|
| 最后成功容量快照 | Task 1 |
| 统一卷访问与 `ARCHIVE_GUARD` | Task 2 |
| 完整/降级/模拟统计 | Task 3、Task 4 |
| 今日结束任务中的订单结果与 7 日趋势 | Task 3、Task 4、Task 8 |
| 当前告警与健康级别 | Task 4、Task 5、Task 8 |
| 存储、封存历史与自动化 | Task 4、Task 5、Task 8 |
| 分区失败、缓存 requestId、数据质量、安全与日志 | Task 5、Task 9 |
| OpenAPI 契约 | Task 5、Task 6、Task 9 |
| 业务下钻 | Task 6、Task 7 |
| 前端布局、状态与响应式 | Task 7、Task 8 |
| 性能、回归和验收 | Task 9 |

## 完成门禁

- 规格中的 22 个验收场景全部通过；
- 后端全量测试、前端全量测试和生产构建通过；
- 数据库迁移和查询计划验证通过；
- 概览 P95 ≤1 秒；
- 首页链路证实没有文件系统、NAS、Marker 或线上调用；
- 首页、下载、恢复和备份预检使用同一卷访问语义；
- 无剩余已知 P1/P2；
- 只创建一个符合仓库规范的最终提交。
