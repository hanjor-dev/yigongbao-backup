# 存储卷异常状态修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为文件完整性已经得到证明、但因封存业务流程异常进入 `REPAIR_REQUIRED` 的卷提供受控恢复入口。

**Architecture:** 新增独立的异常状态修复服务，集中执行卷状态、最新封存尝试、逐文件校验、任务引用、租约、空间预留、卷标记和健康检查。只有全部检查通过时才原子地将 `operationStatus` 恢复为 `IDLE` 并清除残留 `operationRefId`；前端提供“修复异常状态”操作，后端始终作为最终安全边界。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Vue 3、Element Plus、Node test

**Spec:** `docs/superpowers/specs/2026-11-09-cold-storage-volume-lifecycle-design.md`

## Global Constraints

- 只允许 `HOT/ONLINE/READ_ONLY/ENABLED/REPAIR_REQUIRED` 卷执行修复。
- 最近封存失败必须是提交阶段流程异常，且冻结清单中的全部文件均为 `VERIFIED`。
- 任一文件为 `FAILED`、`PENDING` 或校验数量不完整时必须拒绝修复。
- 存在运行任务、恢复引用、读取租约、空间预留或非终态封存尝试时必须拒绝修复。
- 修复只执行 `REPAIR_REQUIRED → IDLE` 和清空 `operationRefId`，不得改变分层、挂载或写入状态。
- 修复原因必填，并记录管理员审计日志。

## Review Focus

- 提交失败但文件全部验证通过的 D 盘应可恢复。
- 文件校验失败或尚未完成时不得通过修复功能绕过隔离。
- 历史失败后已有新的进行中封存尝试时不得修复旧状态。
- 卷标记不匹配或健康探测失败时不得恢复业务可读性。
- 前端隐藏按钮不能代替后端校验，直接调用接口仍必须安全失败。

---

### Task 1: 后端异常状态修复安全边界

**Files:**
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/StorageVolumeAbnormalStateRepairService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/StorageVolumeAbnormalStateRepairServiceTest.java`

**Interfaces:**
- Produces: `StorageVolumeEntity repair(long volumeId, long actorId, String reason)`
- Produces: `POST /api/v1/admin/storage-volumes/{id}/repair-abnormal-state`

- [x] 写失败测试：提交失败、全部文件已验证且卷健康时恢复为 `IDLE` 并清除操作引用。
- [x] 写失败测试：存在失败/待校验文件、非提交失败、运行引用、卷标记或健康异常时拒绝恢复。
- [x] 运行目标测试确认因服务不存在而失败。
- [x] 实现最小安全判定、状态更新、审计和控制器接口。
- [x] 运行目标测试确认通过。

### Task 2: 前端“修复异常状态”操作闭环

**Files:**
- Modify: `yigongbao-backup-web/src/storageVolumeActions.js`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/components/StatusBadge.js`
- Test: `yigongbao-backup-web/test/storage-volume-actions.test.js`
- Test: `yigongbao-backup-web/test/storage-volume.test.js`

**Interfaces:**
- Consumes: `POST /admin/storage-volumes/{id}/repair-abnormal-state`
- Produces: `canRepairAbnormalState(volume)` 与用户可见文案“修复异常状态”

- [x] 写失败测试：只有符合基础卷状态的记录展示“修复异常状态”。
- [x] 写失败测试：API 动作、必填修复原因、成功反馈和审计文案完整。
- [x] 运行目标测试确认失败。
- [x] 实现能力函数、确认输入、接口调用和刷新反馈。
- [x] 运行目标测试确认通过。

### Task 3: 全量验证

- [x] 运行后端全部测试，失败数和错误数均为 0。
- [x] 运行前端全部测试。
- [x] 运行前端生产构建。
- [x] 运行 `git diff --check` 并复核安全边界。
