# 备份系统完整审查问题修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复完整审查确认的恢复、回执、账号、配置、数据库、存储与查询契约缺陷，使新备份可恢复、账号可撤权、部署脚本可执行、长任务和可拔介质保持数据安全。

**Architecture:** 保留现有 Spring/MyBatis 模块边界，在协议入口补全 v2 字段，在事务边界补充状态复核与可恢复幂等，在本地账号和存储边界加入显式所有权、会话撤销、容量续租和介质身份校验。数据库初始化与迁移使用同一现行 schema，OpenAPI 跟随真实控制器契约。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Sa-Token、JUnit 5、Mockito、MySQL 8、OpenAPI 3.1。

**Spec:** 本任务上一轮完整审查的 1 个 P0、11 个 P1、5 个 P2 发现，以及 `系统备份与恢复子系统-详细技术设计.md`。

## Global Constraints

- 降级备份仍只允许管理员从失败订单单独触发，不并入自动备份或普通重试。
- 不完整订单恢复继续复用正常恢复入口与上传流程。
- 已成功降级备份的订单视为已备份，不再自动重复备份。
- 现有公开 HTTP 路径与持久化数据必须保持向后兼容；协议新增字段允许旧 v1 客户端继续工作。
- 所有行为修复遵循测试先行；SQL 修复以真实 schema/迁移执行结果为准，不用纯文本包含测试替代。

## Review Focus

- v1/v2 恢复请求经控制器映射后摘要语义不变。
- 清单确认到回执提交之间发生订单或关系漂移时拒绝 ACTIVE 回执。
- 账号禁用、删除、并发登录失败和游标晚提交均不能保留未授权访问。
- 进程在封存准备事务后退出、任务超过两小时、介质被替换时不能破坏存储安全。
- 模块化 fresh-init、full-init 和连续迁移必须生成与实体一致的数据库。

---

### Task 1: 线上 v2 恢复与回执一致性

**Files:**
- Modify: `yigongbao-module-backup/.../restore/controller/RestoreController.java`
- Modify: `yigongbao-module-backup/.../receipt/ReceiptServiceImpl.java`
- Modify: supporting mapper/service files only where transactional current-state verification requires it
- Test: controller v2 contract and receipt state-drift integration/unit tests

**Interfaces:**
- Consumes: existing `RestoreSessionService.PrepareCommand` full constructor and manifest relationship-state services.
- Produces: lossless v1/v2 prepare mapping; receipt commit that rejects manifest drift with `MANIFEST_CHANGED` or equivalent existing protocol error.

- [ ] Add a failing controller test proving all v2 fields reach `PrepareCommand` unchanged.
- [ ] Add a failing receipt test proving state drift after confirmation cannot create an ACTIVE receipt.
- [ ] Implement full v2 DTO mapping and transaction-bound current-state/token verification.
- [ ] Add remote-object evidence tests for empty ETag and trusted checksum/version requirements.
- [ ] Run the backup module test suite.

### Task 2: 本地账号、配置、数据库与查询契约

**Files:**
- Modify: account sync/field-control/auth/role-provider classes and tests
- Modify: `ConfigValueValidator.java`, `ConfigurationChangeGuard.java` and tests
- Modify: modular init, full init and cold archive migration SQL
- Modify: `local-backup-v1.yaml` and contract tests

**Interfaces:**
- Produces: initial REMOTE ownership, local override only after explicit local change, immediate token revocation on disable, atomic failed-login counting, lookback/high-watermark account sync, HTTPS-only production online endpoint, projected lease configuration validation, consistent schema and OpenAPI.

- [ ] Add failing tests for remote status/password sync, token revocation, concurrent failed-login CAS, cursor lookback and HTTPS validation.
- [ ] Implement ownership-aware merge, session invalidation and atomic login-failure update.
- [ ] Implement projected TTL/heartbeat validation and safe online URL validation.
- [ ] Repair modular init and migration SQL; add executable schema migration verification where project infrastructure permits.
- [ ] Align order query/detail/filter OpenAPI with the controller and run backend contract tests.

### Task 3: 本地存储、封存和恢复证据

**Files:**
- Modify: `VolumeArchiveService.java` and archive recovery tests
- Modify: `PersistentSpaceReservationService.java`, mapper/API and job heartbeat integration
- Modify: `PersistentRestoreEvidenceStore.java`, `HttpOnlineRestoreClient.java`
- Modify: `VolumeArtifactStoreRouter.java` and marker verification support

**Interfaces:**
- Produces: recoverable pre-job archive attempts, renewable fenced reservations, concurrent-safe restore evidence without aborting shared sessions, and marker-verified volume access.

- [ ] Add failing tests for post-prepare crash recovery/idempotent restart.
- [ ] Add failing tests proving reservations remain active beyond the initial lease while the task heartbeat is healthy.
- [ ] Add failing concurrent evidence test proving duplicate remote sessions converge without abort.
- [ ] Add failing media-swap test proving marker mismatch blocks read and write.
- [ ] Implement minimal state recovery, renewal/fencing, upsert/re-read and marker verification.
- [ ] Run the local backend test suite.

### Task 4: 集成验证与审查

- [ ] Run all local backend tests.
- [ ] Run all online backup module tests.
- [ ] Run frontend tests and production build if API models changed.
- [ ] Verify both worktrees are clean except intended changes and review the combined diff against all 17 findings.
- [ ] Perform a final independent code review and fix any blocking regressions.
