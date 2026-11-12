# Automatic Degraded Order Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新创建的严格订单备份任务在遇到 `RELATION_BROKEN` 时，按任务快照配置在同一任务内自动完成 `INCOMPLETE` 降级备份，并明确展示“文件可能不完整”。

**Architecture:** 线上服务用独立、稳定的业务码区分“关系损坏”和“降级读取时关系已恢复”，备份子系统把配置固化进任务 Payload，并在普通重试阶段之后运行有界的严格/降级补偿状态机。已有 `INCOMPLETE` 快照、暂存制品和恢复链路继续作为唯一事实来源，任务查询层批量投影完整性，PC 管理端只负责展示。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、Vue 3、Element Plus、Node test runner、MySQL SQL 初始化/迁移脚本

**Spec:** `docs/superpowers/specs/2026-11-12-auto-degraded-backup-design.md`

## Global Constraints

- 配置键固定为 `backup.auto-degrade-on-relation-broken`，分类固定为 `BACKUP_COLLECTION / 备份采集策略`，默认值为 `true`。
- 只有严格清单返回精确业务码 `RELATION_BROKEN` 才进入自动降级；其他异常沿用现有重试/失败策略。
- 自动降级发生在原任务内；不创建子任务，不扫描历史失败任务。
- 新任务固化当前配置；旧任务 Payload 缺少字段时按 `false` 处理。
- 零个可恢复文件也必须生成包含订单业务快照和关系缺口证据的有效 `INCOMPLETE` 备份。
- `INCOMPLETE` 是成功快照；所有订单为 `COMPLETE` 或 `INCOMPLETE` 时任务成功，否则保持现有部分失败/失败语义。
- 自动降级状态机必须有界；关系状态来回变化最终以本地 `RELATION_STATE_UNSTABLE` 终止，禁止无限循环。
- 仅修改 `yigongbao-backup-web` PC 管理端，不扩展 H5 或主业务前端。
- 保留工作区既有修改；本次不做无关重构，也不在未获要求时创建 Git 提交。

## Review Focus

- 旧任务 JSON 没有新布尔字段时必须保持严格备份，不得因数据库默认值为 true 而改变历史任务行为；Task 2 的兼容测试覆盖。
- 降级清单读取后关系恰好恢复、随后又再次损坏时最多执行一次严格确认和一次最终降级读取；Task 3 的状态机测试覆盖。
- 执行器在 `DEGRADE_PENDING`/`DEGRADING` 后崩溃重启时必须恢复队列且不重复发布已有 AVAILABLE 快照；Task 4 的恢复测试覆盖。
- 零资产 `INCOMPLETE` 清单必须生成可校验 ZIP、快照及回执；Task 4 的工作流测试覆盖。
- 同一订单并发补偿或幂等回执冲突时，只有存在可验证本地 AVAILABLE 快照才可对账为成功；Task 4 的并发测试覆盖。

---

### Task 1: 线上清单协议提供稳定状态码

**Files:**
- Modify: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/common/enums/BackupErrorCode.java`
- Modify: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/OrderManifestServiceImpl.java`
- Modify: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/main/resources/openapi/backup-internal-v1.yaml`
- Test: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/OrderManifestServiceImplTest.java`
- Test: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/manifest/controller/OrderManifestControllerTest.java`
- Test: `../医工宝/yigongbao-parent/yigongbao-module-backup/src/test/java/com/yigongbao/module/backup/common/InternalApiResponseTest.java`

**Interfaces:**
- Consumes: 既有 `ManifestResolutionException(BackupErrorCode, String)` 和降级清单端点。
- Produces: `BackupErrorCode.RELATION_GAPS_RESOLVED`，HTTP 409、`retryable=false`，仅表示降级读取时已不存在关系缺口。

- [ ] **Step 1: Write failing tests** asserting degraded creation without gaps returns `RELATION_GAPS_RESOLVED`, strict broken relations still return `RELATION_BROKEN`, and the controller JSON exposes the exact code.
- [ ] **Step 2: Run focused tests** with `mvn -pl yigongbao-module-backup -am -Dtest=OrderManifestServiceImplTest,OrderManifestControllerTest,InternalApiResponseTest test`; expect failures mentioning the missing enum/code.
- [ ] **Step 3: Implement the enum and replace only the degraded-no-gap `STATE_CONFLICT` branch; document the code in OpenAPI.**
- [ ] **Step 4: Re-run the focused tests; expect PASS.**

### Task 2: 配置默认值与任务快照

**Files:**
- Modify: `yigongbao-backup-server/sql/init/04-functional-config-defaults.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Create: `yigongbao-backup-server/sql/migration/2026-11-12-10-auto-degraded-backup.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ScheduledBackupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminJobController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminBackupController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/ScheduledBackupServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminBackupControllerTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/config/ConfigValueValidatorTest.java`

**Interfaces:**
- Consumes: `RuntimeConfigService.getBoolean(String, boolean)`。
- Produces: `ManualBackupJobHandler.Payload(..., Boolean autoDegradeOnRelationBroken)` 和 `boolean resolvedAutoDegradeOnRelationBroken()`；所有新严格任务构造器显式传入当前配置，旧 JSON 的 null 返回 false。

- [ ] **Step 1: Write failing tests** for default SQL metadata, scheduled/manual/retry-created payloads freezing true/false, and legacy payload JSON resolving false.
- [ ] **Step 2: Run focused server tests; expect constructor/property assertion failures.**
- [ ] **Step 3: Add the idempotent config migration/default rows and extend Payload without changing explicit `DEGRADED_BACKUP` semantics.**
- [ ] **Step 4: Centralize new strict-task payload creation so scheduled scan, manual creation and failed-order retry snapshot the current boolean; preserve the original time window and execution mode.**
- [ ] **Step 5: Re-run focused tests; expect PASS.**

### Task 3: 有界自动降级决策状态机

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/AutomaticDegradedBackupPolicy.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/AutomaticDegradedBackupPolicyTest.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedBackupJobHandlerTest.java`

**Interfaces:**
- Consumes: `RemoteCallException.code()` and task-snapshot boolean.
- Produces: `AutomaticDegradedBackupPolicy` predicates for `RELATION_BROKEN`, `RELATION_GAPS_RESOLVED`, transient retryability, and local terminal code `RELATION_STATE_UNSTABLE`; handler manifest mode `STRICT|DEGRADED` is passed explicitly into one attempt.

- [ ] **Step 1: Write policy tests** proving exact-code matching, disabled behavior, no text matching, and the bounded `strict broken -> degraded resolved -> strict broken -> final degraded resolved -> unstable` path.
- [ ] **Step 2: Run the policy test; expect FAIL because the class/methods do not exist.**
- [ ] **Step 3: Implement the small policy class and refactor `backupOnce`/`executeAttempt` to accept explicit manifest mode while preserving explicit degraded-job refresh behavior.**
- [ ] **Step 4: Add handler tests** proving `RELATION_BROKEN` is queued for post-retry automatic compensation only when the task snapshot is true, and nonmatching failures remain unchanged.
- [ ] **Step 5: Run the focused handler tests; expect PASS.**

### Task 4: 同任务补偿、恢复、零文件与幂等安全

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobExecutionContext.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobEvidenceService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupStageService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedBackupJobHandlerTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedOrderBackupWorkflowTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/JobEvidenceServiceTest.java`

**Interfaces:**
- Consumes: Task 3 manifest modes/policy and existing staged artifact resume.
- Produces: persisted order statuses `DEGRADE_PENDING` and `DEGRADING`; context methods to mark/query them; mode-specific idempotency; one automatic compensation phase after ordinary retries.

- [ ] **Step 1: Write failing orchestration tests** for mixed COMPLETE/INCOMPLETE/FAILED orders, one compensation phase in the original job, attempted/succeeded uniqueness, and cancel safe points.
- [ ] **Step 2: Write failing recovery tests** that reconstruct pending/degrading orders after restart, skip an existing AVAILABLE snapshot, and reconcile a concurrent success only when that snapshot verifies.
- [ ] **Step 3: Write a zero-file workflow test** asserting an INCOMPLETE package contains manifest/business snapshot/gap evidence, has a valid hash, and publishes an AVAILABLE snapshot.
- [ ] **Step 4: Run the focused tests; expect failures on missing statuses/query/orchestration.**
- [ ] **Step 5: Implement persistence/context methods and the automatic compensation phase, keeping the existing global per-order retry budget and safe points.**
- [ ] **Step 6: Implement mode-specific idempotency and local snapshot verification before accepting concurrent terminal success.**
- [ ] **Step 7: Re-run focused tests; expect PASS.**

### Task 5: 任务统计与订单完整性投影

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobOrderEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobServiceTest.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/DegradedBackupJobHandlerTest.java`

**Interfaces:**
- Consumes: snapshot `backupCompleteness`/`relationGapCount` and Task 4 outcomes.
- Produces: result JSON fields `completeSucceeded`, `incompleteSucceeded`, `orderFailed`, `autoDegradedAttempted`, `autoDegradedSucceeded`; order rows expose transient `backupCompleteness`, `relationGapCount`, `autoDegraded` without schema migration.

- [ ] **Step 1: Write failing summary tests** proving `orderSucceeded = completeSucceeded + incompleteSucceeded`, repaired-to-COMPLETE counts attempted but not degradedSucceeded, and task status succeeds when no orderFailed remains.
- [ ] **Step 2: Write a query projection test** proving snapshot completeness and gap count are batch-enriched without N+1 queries.
- [ ] **Step 3: Run focused tests; expect missing fields/assertion failures.**
- [ ] **Step 4: Implement counters and batch projection from existing snapshots.**
- [ ] **Step 5: Re-run focused tests; expect PASS.**

### Task 6: PC 管理端明确展示不完整备份

**Files:**
- Modify: `yigongbao-backup-web/src/taskCenter.js`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobOrderDetailDrawer.vue`
- Test: `yigongbao-backup-web/test/taskCenter.test.js`
- Test: `yigongbao-backup-web/test/backup-task-detail.test.js`

**Interfaces:**
- Consumes: Task 5 的订单投影与任务结果统计。
- Produces: Element Plus warning tag/alert “文件可能不完整”，显示缺失关系数；该状态优先于普通“已重试成功”提示但不改变订单成功状态。

- [ ] **Step 1: Write failing frontend tests** for summary labels, INCOMPLETE priority, gap count, COMPLETE and legacy rows.
- [ ] **Step 2: Run `npm test -- --runInBand` or the repository's focused node tests; expect assertion failures.**
- [ ] **Step 3: Implement display helpers and Element Plus-only task/detail UI.**
- [ ] **Step 4: Run frontend tests and production build; expect PASS.**

### Task 7: Cross-repository verification

**Files:**
- Verify only: all files above

**Interfaces:**
- Consumes: Tasks 1-6.
- Produces: evidence that the complete feature works without regressing strict backup, explicit degraded backup, restore, task details, or configuration.

- [ ] **Step 1: Run the online module test suite** with `mvn -pl yigongbao-module-backup -am test`; expect BUILD SUCCESS.
- [ ] **Step 2: Run the backup server test suite** with the repository Maven command; expect all tests PASS.
- [ ] **Step 3: Run the backup web full test suite and production build; expect PASS.**
- [ ] **Step 4: Inspect `git diff --check` and relevant diffs in both repositories; expect no whitespace errors, generated junk, secrets, or unrelated changes.**
- [ ] **Step 5: Report exact test counts, any pre-existing unrelated dirty files, and deployment order: online protocol first, then backup server/config migration, then web.**
