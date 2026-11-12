# 备用卷取消与封存状态闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `STANDBY → READ_ONLY → 整盘封存` 的完整操作闭环，并在封存前阻止不合法状态进入耗时校验。

**Architecture:** 后端生命周期服务新增显式取消备用操作，封存阻塞器统一约束源卷必须为 `ACTIVE` 或 `READ_ONLY`。前端按状态暴露互斥操作，并在订单热备副本存在但不可读时同时展示可读性。封存提交失败日志输出参与提交的两块卷的实际状态。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Vue 3、Element Plus、Node test

**Spec:** `docs/superpowers/specs/2026-11-09-cold-storage-volume-lifecycle-design.md`

## Global Constraints

- `STANDBY` 卷不能直接进入封存校验。
- 取消备用只允许 `HOT/STANDBY/IDLE`，结果为 `HOT/READ_ONLY/IDLE`。
- 后端始终作为状态机最终边界，不能只依赖前端隐藏按钮。
- 不改变失败卷 `REPAIR_REQUIRED` 的安全隔离语义。

## Review Focus

- 并发生命周期操作中的备用卷必须拒绝取消备用。
- `STANDBY` 源卷必须在预检阶段被阻塞。
- `ACTIVE`、`READ_ONLY` 两类合法源卷仍可封存。
- 热备副本记录存在但卷不可读时不能只展示“存在”。
- 提交状态不一致时日志必须包含源卷和保障卷的实际状态。

---

### Task 1: 后端取消备用状态机

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeController.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/volume/StorageVolumeLifecycleServiceTest.java`

- [x] 先写测试，断言 `HOT/STANDBY/IDLE` 可取消为 `READ_ONLY`，忙碌卷和非备用卷被拒绝。
- [x] 运行目标测试并确认因接口不存在而失败。
- [x] 实现 `cancelStandby(long volumeId, long operatorId)` 和 `POST /{id}/cancel-standby`。
- [x] 运行目标测试确认通过。

### Task 2: 封存源卷前置状态约束和失败诊断

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveBlockerService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveService.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveServiceTest.java`

- [x] 先写测试，断言 `STANDBY` 源卷立即产生阻塞项，`READ_ONLY` 源卷仍通过。
- [x] 运行目标测试并确认现有实现错误放行。
- [x] 增加源卷写状态校验，并为提交状态异常增加结构化卷状态日志。
- [x] 运行目标测试确认通过。

### Task 3: 前端操作闭环和可读性表达

**Files:**
- Modify: `yigongbao-backup-web/src/storageVolumeActions.js`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/components/orders/OrderTable.vue`
- Test: `yigongbao-backup-web/test/storage-volume-actions.test.js`
- Test: `yigongbao-backup-web/test/storage-volume.test.js`
- Test: `yigongbao-backup-web/test/backup-flow.test.js`

- [x] 先写测试，断言备用卷显示“取消备用”且不显示“整盘封存”，只读卷相反。
- [x] 先写测试，断言热备存在但当前不可读时显示“不可读”。
- [x] 运行目标测试并确认失败。
- [x] 实现操作能力函数、API动作、页面按钮互斥和热备可读性标签。
- [x] 运行前端目标测试确认通过。

### Task 4: 全量验证

- [x] 运行 `mvn test`，要求失败数和错误数均为 0。
- [x] 运行 `npm test`，要求全部通过。
- [x] 运行 `npm run build`，要求退出码为 0。
- [x] 运行 `git diff --check` 检查本次文件。
