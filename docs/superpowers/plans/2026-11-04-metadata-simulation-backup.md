# Metadata Simulation Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加安全隔离的订单备份元数据模拟模式，以真实候选订单规模跑通任务全流程，同时确保不下载 COS 文件、不形成正式备份证据、不影响线上增量状态。

**Architecture:** 在任务创建时把环境级执行模式固化进任务配置快照，由独立的订单执行器分派真实备份与模拟备份。模拟执行器只读取 Manifest 并调用隔离的模拟制品写入器；正式的下载、容量预留、证据持久化和线上回执链路保持封闭且不可被模拟模式调用。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Jackson、JUnit 5/Mockito、Vue 3、Vite、Element Plus、Vitest、MySQL 8。

**Spec:** [2026-11-04-metadata-simulation-backup-design.md](../specs/2026-11-04-metadata-simulation-backup-design.md)

---

## Global Constraints

- 不改变线上医工宝候选、Manifest 和正式回执协议；本功能仅修改本地备份系统。
- 执行模式只来自环境变量，数据库配置页面不得提供切换入口。
- `REAL` 必须是缺省模式；`prod + METADATA_SIMULATION` 必须启动失败。
- 模拟任务不得写正式快照/资产/包记录，不得出现在订单文件列表。
- 所有后端新增类、公开方法和关键安全分支需要中文注释；关键流程输出结构化、可读的中文业务日志。
- 任何路径都必须经过标准化和根目录边界校验，资产文件名不得造成路径穿越或覆盖。

### Task 1: 建立执行模式配置与启动安全校验

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupExecutionMode.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupExecutionModeProvider.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/BackupExecutionModeProviderTest.java`
- Modify: `yigongbao-backup-server/src/main/resources/application.yml`
- Modify: `.env.example`

- [ ] **Step 1: 写失败测试**

覆盖默认 `REAL`、显式 `METADATA_SIMULATION`、非法值启动失败、模拟目录为空启动失败、`prod` Profile 禁用模拟模式。

- [ ] **Step 2: 运行定向测试并确认失败**

Run: `mvn -pl yigongbao-backup-server -Dtest=BackupExecutionModeProviderTest test`

- [ ] **Step 3: 实现枚举、环境读取与启动校验**

配置键仅映射 `BACKUP_EXECUTION_MODE`、`BACKUP_SIMULATION_ROOT`、`BACKUP_DEV_CANDIDATE_LIMIT`；不得加入数据库配置初始化数据。

- [ ] **Step 4: 更新环境变量示例及中文说明**

明确 `0=无限制`、模拟目录不得指向正式 `data/orders` 目录、生产环境固定使用 `REAL`。

- [ ] **Step 5: 重跑测试**

Expected: 全部通过。

### Task 2: 固化任务级执行模式并调整候选数量限制

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ScheduledBackupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/ScheduledBackupServiceTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/ManualBackupJobHandlerTest.java`

- [ ] **Step 1: 为 Payload 增加 `executionMode`，并写兼容测试**

旧任务 Payload 缺少字段时按 `REAL` 解释；新任务创建时必须写入当前模式，任务执行中不得再次读取环境模式。

- [ ] **Step 2: 写真实全量候选测试**

验证开发上限为 `0` 时持续翻页到 `hasMore=false`，正整数时只用于显式限量；自然日截止时间、游标和去重规则保持不变。

- [ ] **Step 3: 修改候选扫描与日志**

日志输出执行模式、是否限量、线上总接纳数；禁止在日志中输出预签名 URL。

- [ ] **Step 4: 运行定向测试**

Run: `mvn -pl yigongbao-backup-server -Dtest=ScheduledBackupServiceTest,ManualBackupJobHandlerTest test`

### Task 3: 实现隔离的模拟制品写入器

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/MetadataSimulationArtifactWriter.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/SimulationArtifactResult.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/MetadataSimulationArtifactWriterTest.java`

- [ ] **Step 1: 写目录和文件结构测试**

验证 `{taskDate}/{jobNo}/{orderNo}-{orderId}/manifest.json`、`_simulation.json` 和零字节占位文件。

- [ ] **Step 2: 写安全边界测试**

覆盖 `../`、绝对路径、Windows 保留名、非法字符、重名文件、超长文件名；最终路径必须仍位于模拟根目录内。

- [ ] **Step 3: 写原子性和失败清理测试**

先写临时目录，成功后原子移动；失败时不能留下被误判为成功的完整目录。

- [ ] **Step 4: 实现写入器**

`_simulation.json` 至少记录任务 ID/编号、订单 ID/编号、模式、Manifest 版本、预计文件数、预计总容量、生成时间和 `simulated=true`。

- [ ] **Step 5: 运行定向测试**

Run: `mvn -pl yigongbao-backup-server -Dtest=MetadataSimulationArtifactWriterTest test`

### Task 4: 抽取订单执行器并实现强制安全隔离

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/OrderBackupExecutor.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/RealOrderBackupExecutor.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/MetadataSimulationOrderExecutor.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/OrderBackupExecutionResult.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ManualBackupJobHandler.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/MetadataSimulationOrderExecutorTest.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/ManualBackupJobHandlerTest.java`

- [ ] **Step 1: 用测试锁定模拟模式的禁止调用清单**

注入 Mock 并验证 `CosObjectClient`、`SpaceReservationService`、`BackupEvidenceService`、Manifest 确认与回执方法调用次数均为零。

- [ ] **Step 2: 把现有真实流程迁入 `RealOrderBackupExecutor`**

保持现有下载、重试、容量预留、正式证据、线上回执和日志语义不变，避免模拟分支散落在真实流程中。

- [ ] **Step 3: 实现模拟执行器**

只调用 Manifest 查询与模拟制品写入器；返回预计文件数/容量、模拟目录和完整订单业务快照。

- [ ] **Step 4: Handler 按任务快照分派执行器**

模拟成功写订单状态 `SIMULATED`，终态失败写 `SIMULATION_FAILED`；沿用心跳、取消、安全点和订单级重试。

- [ ] **Step 5: 运行定向测试**

Run: `mvn -pl yigongbao-backup-server -Dtest=MetadataSimulationOrderExecutorTest,ManualBackupJobHandlerTest test`

### Task 5: 持久化模拟统计并扩展任务查询接口

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/Job.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobOrderEntity.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/JobExecutionContext.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobServiceTest.java`
- Modify: `database/init.sql`
- Create: `database/migration/2026-11-04-metadata-simulation-backup.sql`

- [ ] **Step 1: 先核对当前初始化脚本的实际表定义**

以现有字段为准做最小迁移：任务增加 `execution_mode`；订单明细增加预计文件数、预计容量和模拟制品路径。聚合统计优先由任务订单明细计算，避免同时维护重复计数列。

- [ ] **Step 2: 写持久化与重启恢复测试**

验证模式、逐单统计和模拟路径可从数据库恢复；进程重启后不会把模拟任务切换成真实模式。

- [ ] **Step 3: 更新实体、查询 DTO 和聚合逻辑**

接口返回 `executionMode`、`manifestSucceeded`、`manifestFailed`、`expectedFileTotal`、`expectedBytes`；真实任务的既有字段保持兼容。

- [ ] **Step 4: 编写幂等迁移和新环境初始化 SQL**

迁移须兼容已有数据并回填 `REAL`；初始化 SQL 直接包含最终表结构。禁止写入模拟模式数据库配置项。

- [ ] **Step 5: 运行数据库相关测试**

Run: `mvn -pl yigongbao-backup-server -Dtest=PersistentJobServiceTest test`

### Task 6: 完善订单备份任务前端展示

**Files:**
- Modify: `yigongbao-backup-web/src/components/jobs/JobTable.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupContext.js`
- Modify: `yigongbao-backup-web/src/api/jobs.js`
- Create or Modify: `yigongbao-backup-web/src/components/jobs/__tests__/JobTable.spec.js`
- Create or Modify: `yigongbao-backup-web/src/components/jobs/__tests__/JobDetail.spec.js`

- [ ] **Step 1: 写模拟任务渲染测试**

验证“元数据模拟”“模拟完成”、Manifest 成功/失败、预计文件数/容量、模拟目录和订单核心字段。

- [ ] **Step 2: 调整列表语义**

模拟任务不得显示“文件备份成功”；文件列改为“预计文件”，成功终态显示“模拟完成”。真实任务保持原文案。

- [ ] **Step 3: 调整详情与刷新逻辑**

详情展示逐订单 `SIMULATED/SIMULATION_FAILED`、业务快照、预计统计及失败原因；运行中任务继续轮询直到终态。

- [ ] **Step 4: 确认订单文件页隔离**

后端无正式快照时前端不得拼装或展示模拟订单；增加回归测试。

- [ ] **Step 5: 运行前端测试和构建**

Run: `npm test -- --run`

Run: `npm run build`

### Task 7: 增加端到端模拟冒烟测试

**Files:**
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/smoke/BackupWorkflowSmokeTest.java`
- Modify: `yigongbao-backup-server/src/test/resources/` 下对应 Mock 配置
- Create: `docs/testing/metadata-simulation-backup.md`

- [ ] **Step 1: 构造非固定数量的候选订单**

Mock 线上服务至少提供跨多页候选和不同文件数量的 Manifest，并记录各接口调用次数。

- [ ] **Step 2: 对禁止接口设置硬失败**

一旦请求 COS 下载 URL、确认、回执或清理接口，测试立即失败，而不是仅在结束后检查日志。

- [ ] **Step 3: 验证任务终态、统计和目录**

断言真实候选数=任务订单数、预计文件/容量聚合正确、模拟目录完整、任务终态正确且订单文件正式列表为空。

- [ ] **Step 4: 验证关闭模拟后仍可正式入选**

同一批 Mock 订单切回 `REAL` 后仍应被候选接口接纳，证明模拟未提交线上回执。

- [ ] **Step 5: 编写本地操作说明**

包含 `.env` 示例、目录检查、前端识别方式、禁止调用校验及恢复 `REAL` 的步骤。

### Task 8: 全量回归与代码审查

- [ ] **Step 1: 后端全量测试**

Run: `mvn -pl yigongbao-backup-server test`

- [ ] **Step 2: 前端全量测试与构建**

Run in `yigongbao-backup-web`: `npm test -- --run && npm run build`

- [ ] **Step 3: SQL 静态与新库初始化验证**

在临时 MySQL Schema 执行最终初始化脚本，再执行迁移脚本确认幂等性和默认值。

- [ ] **Step 4: 安全审查**

全文检索模拟分支，确认没有 `submitReceipt`、`confirm`、COS 下载、正式证据写入和清理调用；确认日志不会输出预签名 URL 或 HMAC 信息。

- [ ] **Step 5: 需求回归审查**

逐条对照 Spec 验收标准，记录通过证据和仍需人工联调的环境项，不以编译通过代替功能完成。
