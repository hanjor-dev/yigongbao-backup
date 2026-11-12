# P1 Risk Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复全量代码审查确认的 P1 风险，但保持默认管理员账号及首次改密行为不变。

**Architecture:** 在认证边界、任务租约、文件系统边界和跨实例状态转换处增加强约束。所有状态变更依赖数据库条件更新或可验证的介质身份；文件系统副作用采用失败补偿，不假装与数据库事务原子。

**Tech Stack:** Java 21、Spring Boot 3、MyBatis-Plus、Sa-Token、JUnit 5、Mockito、Vue 3、Node test。

**Spec:** 本线程 2026-11-11 全量代码审查报告及用户 2026-11-12 的 P1 修复确认。

## Global Constraints

- 不修改初始化管理员默认密码，不增加首次登录强制改密。
- 保留工作区已有的受控目录自动恢复和存储卷前端提示改动。
- P2 与“其他完整性问题”本轮不修改。
- 每项行为修复必须先新增失败测试，再实现最小修复。
- 不引入新的外部依赖。

## Review Focus

- 过期租约不得提交终态、结果、安全点或任务证据。
- Windows junction/reparse point 不得穿透受控目录。
- marker 正确但物理卷指纹不一致时，健康检查和 I/O 都必须拒绝。
- 多实例封存和清理必须通过数据库 owner/token/ref 条件更新隔离。
- 文件系统副作用失败或数据库事务回滚后，不得留下可被误判为成功的状态。

---

### Task 1: 认证会话撤销与认证异常映射

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/account/AuthController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/account/AdminAccountController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/common/GlobalExceptionHandler.java`
- Modify: `yigongbao-backup-web/src/stores/authStore.js`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/account/AuthSessionContractTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/common/GlobalExceptionHandlerTest.java`
- Test: `yigongbao-backup-web/test/auth-store.test.js`

- [x] 新增测试：前端退出调用 `/auth/logout`；改密/重置密码撤销账号全部会话；Sa-Token 未登录和无角色分别返回 401/403。
- [x] 运行定向测试并确认按缺失行为失败。
- [x] 实现服务端会话撤销、前端远端退出和认证异常映射。
- [x] 运行定向测试并确认通过。

### Task 2: 调度线程隔离

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/config/SchedulingConfiguration.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/config/SchedulingConfigurationTest.java`

- [x] 新增测试：调度器线程池不少于四个线程，任务异常不会终止调度线程。
- [x] 确认测试失败后，注册专用 `ThreadPoolTaskScheduler`。
- [x] 运行定向测试。

### Task 3: 任务租约与证据 fencing

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobCoordinator.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobExecutionContext.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobEvidenceService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobCoordinatorSafetyTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/JobEvidenceServiceTest.java`

- [x] 新增测试：过期租约不能写安全点、结果或终态；证据写入必须处于 owner/token/lease 有效的行锁事务内。
- [x] 确认测试失败。
- [x] 把有效租约条件加入所有 guarded 更新，并在证据入口通过 `SELECT ... FOR UPDATE` 原子校验租约后写入。
- [x] 运行定向测试。

### Task 4: 本地存储路径与物理介质校验

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/local/LocalDirectoryArtifactStore.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthScheduler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/VolumeArtifactStoreRouter.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/local/LocalDirectoryArtifactStoreTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeHealthSchedulerTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/VolumeArtifactStoreRouterTest.java`

- [x] 新增测试：reparse/junction 被拒绝；介质指纹不一致时巡检和 I/O 都失败。
- [x] 确认测试失败。
- [x] 复用卷发现结果校验指纹，并对 Windows reparse point 做无跟随检测。
- [x] 运行定向测试。

### Task 5: 补传暂存容量与遗留文件

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/SupplementController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/SupplementUploadJobHandler.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/SupplementControllerTransactionTest.java`

- [x] 新增测试：文件类型/大小在落盘前校验；任务失败或取消后删除暂存文件并释放预留。
- [x] 确认测试失败。
- [x] 上传体先进入独立系统临时区，后台在写活动卷前用真实 jobId 预留容量；处理器 finally 清理，定时任务清扫孤儿文件。
- [x] 运行定向测试。

### Task 6: 封存保障卷并发隔离

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveBlockerService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveServiceTest.java`

- [x] 新增测试：非 IDLE 的保障卷不能被再次占用；任务只能释放自己的 guard。
- [x] 确认测试失败。
- [x] 对 guard 获取/释放增加 operation status/ref 条件校验。
- [x] 运行定向测试。

### Task 7: 清理 Permit 对象集绑定与跨实例 fencing

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cleanup/CleanupTaskService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cleanup/CleanupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cleanup/PermitPollingService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/cleanup/DeletePermitEntity.java`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Create: `yigongbao-backup-server/sql/migration/2026-11-12-01-cleanup-permit-fencing.sql`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/cleanup/CleanupServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/cleanup/CleanupTaskServiceTest.java`

- [x] 新增测试：Permit key 集合必须等于冻结集合；只有持有 owner/token/有效租约的实例可执行和消费 Permit。
- [x] 确认测试失败。
- [x] 持久化 claim owner/lease/fencing token，并用条件更新 claim、续租、consume。
- [x] 运行定向测试。

### Task 8: 受控目录恢复补偿与历史补传类型迁移

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/ManagedVolumeArtifactCatalog.java`
- Create: `yigongbao-backup-server/sql/migration/2026-11-12-02-normalize-supplement-artifact-type.sql`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/ManagedVolumeArtifactCatalogTest.java`

- [x] 新增测试：数据库/审计失败时删除刚创建且仍为空的受控目录；历史 `SUPPLEMENT` 与新 `SUPPLEMENT_FILE` 均能解析。
- [x] 确认测试失败。
- [x] 添加文件系统补偿并提供兼容读取迁移。
- [x] 运行定向测试。

### Task 9: 全量验证

- [x] 运行 `mvn test`。
- [x] 运行 `npm test -- --run`。
- [x] 复核 `git diff`，确认未包含默认管理员首次改密；P2 与其他问题独立登记、未混入本轮实现。
