# 冷备管理页面与封存校验修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复正常订单包封存时的 manifest 误判，建立独立冷备管理页面，并使整盘封存、进度查看、离线和原盘重新接入形成完整闭环。

**Architecture:** 后端以 `VolumeArchiveEntity` 作为冷备封存任务的业务事实，提供全局封存列表和带卷信息的详情；`VOLUME_ARCHIVE` 不再借用订单备份列表。前端新增 `ColdStorageView`，集中展示封存尝试与冷备介质，存储卷页只负责卷基础管理和发起封存。重新接入复用服务端扫描结果，最终仍由 marker 与物理指纹双重校验。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、Vue 3、Element Plus、Vitest、Vite。

**Spec:** `docs/superpowers/specs/2026-11-09-cold-storage-volume-lifecycle-design.md`

## Global Constraints

- 冷备封存不复制、不移动、不删除订单文件。
- 当前存储卷状态以 `bk_storage_volume` 为事实来源，封存 attempt 只保存历史快照。
- 冷备盘重新接入必须校验持久化 marker 与物理指纹，不能仅凭盘符。
- 冷备任务不得出现在“订单备份”页面。
- 保留工作区现有未提交改动，仅增量修改本需求文件。

## Review Focus

- 正常备份包中的线上 JCS 摘要与本地 JSON 字节不同：必须通过解析声明值校验，不能误报。
- manifest 声明摘要被篡改：即使 ZIP 可解压也必须拒绝。
- 封存任务失败或刷新页面：冷备管理页仍能恢复 attempt 和逐文件失败信息。
- 离线冷备盘盘符变化：只能选择扫描到的候选挂载点，后端身份不匹配必须拒绝。
- 旧批次导入能力：新导航与页面不得重新暴露 `COLD_IMPORT/COLD_WRITE/HOT_CLEANUP` 创建入口。

---

### Task 1: 修正订单包 manifest 封存校验语义

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveVerifier.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/storage/archive/VolumeArchiveVerifierTest.java`

**Interfaces:**
- Consumes: `VolumeArchiveItemEntity.expectedManifestSha256`
- Produces: `VerificationResult`，正常包返回成功，声明不一致返回 `MANIFEST_SHA256_MISMATCH`。

- [x] **Step 1: 写失败测试**：创建 package SHA/size 均正确、ZIP 内 `manifest.json.manifestSha256` 与期望一致但 JSON 原始字节摘要不同的订单包，断言校验成功；再覆盖声明值错误、字段缺失和 JSON 非法。
- [x] **Step 2: 运行目标测试并确认旧实现失败**
  - Run: `mvn -Dtest=VolumeArchiveVerifierTest test`
  - Expected: 新增正常包测试 FAIL，报 `MANIFEST_SHA256_MISMATCH`。
- [x] **Step 3: 最小实现**：从同一稳定文件句柄读取 ZIP；整体包 SHA 仍先校验；对 `manifest.json` 设置解压上限并用 Jackson 解析，读取 `manifestSha256`（兼容 `manifestJcsSha256`），与期望进行常量语义比较。
- [x] **Step 4: 运行目标测试**
  - Run: `mvn -Dtest=VolumeArchiveVerifierTest test`
  - Expected: PASS。

### Task 2: 提供独立冷备管理查询契约

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveController.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/storage/archive/VolumeArchiveMapper.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminStorageVolumeArchiveControllerTest.java`

**Interfaces:**
- Produces: `GET /api/v1/admin/storage-volumes/archives`，按 attempt ID 倒序返回全部封存尝试并附源卷/接替卷当前展示信息。
- Produces: 既有 `GET /api/v1/admin/storage-volumes/archives/{archiveId}` 详情继续返回 attempt 与 items。

- [x] **Step 1: 写失败测试**：断言全局列表需要 ADMIN、按倒序返回，且不依赖订单备份 job 列表。
- [x] **Step 2: 运行目标测试确认 404/契约缺失**。
- [x] **Step 3: 实现只读列表 DTO 和查询，保留单卷历史兼容接口**。
- [x] **Step 4: 运行控制器目标测试，Expected: PASS**。

### Task 3: 冷备管理前端领域状态与正确跳转

**Files:**
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/stores/routeStore.js`
- Modify: `yigongbao-backup-web/src/App.vue`
- Modify: `yigongbao-backup-web/src/layouts/BackupLayout.vue`
- Test: `yigongbao-backup-web/test/cold-volume-lifecycle.test.js`
- Test: `yigongbao-backup-web/test/storage-volume.test.js`

**Interfaces:**
- Produces: route key `cold-storage`。
- Produces: `coldStorageArchives`、`loadColdStorage()`、`openColdStorageArchive(id)`、`scanColdMountCandidates()`。
- Changes: `startStorageArchive()` 成功后聚焦 archive attempt 并导航 `cold-storage`，不得调用订单备份 `openJob()`。

- [x] **Step 1: 写失败测试**：断言导航存在、创建封存后进入 `cold-storage`、不进入 `backup`，页面数据来自 archive API。
- [x] **Step 2: 运行前端目标测试并确认 FAIL**。
- [x] **Step 3: 实现最小领域状态、路由和导航入口；旧 `cold` 路由不出现在导航**。
- [x] **Step 4: 运行目标测试，Expected: PASS**。

### Task 4: 实现独立冷备管理页面

**Files:**
- Create: `yigongbao-backup-web/src/views/ColdStorageView.vue`
- Create: `yigongbao-backup-web/src/components/storage/VolumeArchiveDetail.vue`
- Create: `yigongbao-backup-web/src/components/storage/ColdVolumeMountDialog.vue`
- Modify: `yigongbao-backup-web/src/views/StorageView.vue`
- Test: `yigongbao-backup-web/test/cold-volume-lifecycle.test.js`

**Interfaces:**
- Consumes: Task 3 状态与动作。
- Produces: 概览、封存任务列表/详情、冷备介质列表、准备离线、扫描候选并确认挂载。

- [x] **Step 1: 写页面失败测试**：断言封存阶段/进度/订单周期/统计/失败信息可见，冷备介质展示身份与线下定位信息，接入对话框只能选择扫描候选。
- [x] **Step 2: 运行目标测试确认 FAIL**。
- [x] **Step 3: 实现页面与两个聚焦组件；采用现有管理台视觉体系，状态颜色和危险操作确认保持一致**。
- [x] **Step 4: 从 `StorageView` 移除重复的封存历史/文件详情展示，只保留发起入口和跳转冷备管理入口**。
- [x] **Step 5: 运行前端测试和构建**：`npm test`、`npm run build`，Expected: PASS。

### Task 5: 回归验证与审查

**Files:**
- Modify: 本计划与 `task_plan.md`/`findings.md`/`progress.md` 状态记录。

**Interfaces:**
- Consumes: Tasks 1-4。
- Produces: 可交付的测试证据与问题清单。

- [x] **Step 1: 运行后端封存相关测试和后端全量测试**：`mvn test`。
- [x] **Step 2: 运行前端全量测试和构建**：`npm test`、`npm run build`。
- [x] **Step 3: 运行 `git diff --check`，审查冷备任务与订单备份任务是否仍有交叉路由**。
- [x] **Step 4: 对照需求自审查异常场景，修复本次范围内全部 P1/P2 问题并重复验证**。

## 自审查结果

- 覆盖用户提出的三个问题，并将“冷备导入”明确落实为本期已确认的原盘扫描与确认挂载流程。
- 各任务接口字段一致：统一使用 `cold-storage`、`coldStorageArchives` 和 archive detail API。
- 重点风险均在对应任务中有测试归属，没有引入新的旧批次导入入口。
- 计划只决定接口、行为和验证方式，没有展开可由实现者自行完成的代码正文。
