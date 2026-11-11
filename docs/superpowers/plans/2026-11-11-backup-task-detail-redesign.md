# Backup Task Detail Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended for this tightly coupled change) or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将订单备份任务详情改为近全屏弹窗和订单详情抽屉，并为任务/订单文件明细提供真正的服务端分页与按需加载。

**Architecture:** 后端将现有任务文件数组接口升级为统一 `JobFilePage`，支持可选订单过滤并仅投影当前页的存储卷状态。前端把任务摘要、订单页、任务文件页、订单文件页和两类日志拆成独立状态与加载器，由 Dialog/Drawer 生命周期控制懒加载和轮询，杜绝全量文件请求及轮询重复请求。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5/Mockito、Vue 3、Element Plus、Vite、Node test runner。

**Spec:** [2026-11-11-backup-task-detail-redesign.md](../specs/2026-11-11-backup-task-detail-redesign.md)

## Global Constraints

- 当前 `yigongbao-backup` 工作区已有多项未提交修改；实施时只修改本计划列出的文件，禁止覆盖或顺带整理无关变更。
- 用户未要求 Git 提交，本轮实施不自动提交；后续若明确要求提交，再按仓库规范检查并只暂存本功能文件。
- 现有任务列表分页、自动刷新、取消、失败重试和降级备份资格判断保持不变。
- 文件接口一次最多返回 100 条，任务详情轮询不得加载文件或日志。
- 任务文件和订单文件复用同一分页服务端接口，但前端必须维护两套独立分页状态。
- 优先复用 Element Plus 现有视觉语言和项目 CSS 变量，不引入新的 UI 依赖。

## Review Focus

- 验证 `sourceOrderId` 只能收窄当前 `jobId` 下的文件，不能跨任务读取。
- 验证页码参数非法时失败关闭，而不是退化成全量查询。
- 验证任务切换、关闭弹窗及慢请求返回时不会污染新任务状态。
- 验证 3 秒运行态轮询只刷新摘要/订单页，不请求文件和日志接口。
- 验证任务级日志与订单级日志拥有独立页码、总数和加载状态。

---

### Task 1: 后端增加任务文件服务端分页

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/job/PersistentJobService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/admin/AdminJobController.java`
- Modify: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/job/PersistentJobServiceTest.java`
- Create: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/admin/AdminJobControllerTest.java`

**Interfaces:**
- Produces: `PersistentJobService.listFiles(long jobId, Long sourceOrderId, long current, long size) -> JobFilePage`。
- Changes: `GET /api/v1/admin/jobs/{id}/files?current&size&sourceOrderId` 从数组响应升级为 `JobFilePage`。
- Reuses: 现有 `JobFilePage(records,total,current,size,pages)` 和 `enrichCurrentVolumes(...)`。

- [ ] **Step 1: 写服务层分页失败测试**

覆盖默认任务查询、可选 `sourceOrderId` 过滤、`id ASC` 稳定排序、总数/页数透传、每页卷状态批量投影，以及 `current < 1`、`size < 1`、`size > 100` 的拒绝行为。

- [ ] **Step 2: 运行服务层测试并确认失败**

Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=PersistentJobServiceTest test`

Expected: FAIL，新的分页方法尚不存在。

- [ ] **Step 3: 实现统一任务文件分页方法**

使用 MyBatis-Plus `Page.of(current, size)` 和 `LambdaQueryWrapper`；始终过滤 `jobId`，仅在 `sourceOrderId != null` 时追加订单过滤，按 ID 升序。只对 `page.getRecords()` 调用 `enrichCurrentVolumes`。保留现有 `listFiles(long)` 给内部汇总路径使用，避免扩大改动面。

- [ ] **Step 4: 写控制器契约失败测试**

通过反射或轻量 MVC 测试断言控制器接收 `current/size/sourceOrderId` 并返回 `JobFilePage`，默认页码分别为 1 和 20；确认管理员权限检查仍存在。

- [ ] **Step 5: 升级控制器接口并运行定向测试**

Run: `mvn -f yigongbao-backup-server/pom.xml -Dtest=PersistentJobServiceTest,AdminJobControllerTest test`

Expected: PASS；接口不再调用全量 `listFiles(id)`。

### Task 2: 拆分前端详情状态、分页加载与轮询生命周期

**Files:**
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Create: `yigongbao-backup-web/src/jobDetailState.js`
- Create: `yigongbao-backup-web/test/backup-task-detail.test.js`
- Modify: `yigongbao-backup-web/test/backup-flow.test.js`

**Interfaces:**
- Produces: `jobDetailVisible/jobDetailTab/selectedJobOrder/jobOrderDetailVisible`。
- Produces: 任务文件与订单文件各自的 `records/current/size/total/loading` 状态。
- Produces: `loadJobSummary/loadJobOrders/loadJobFiles/loadOrderFiles/closeJobDetail/openJobOrderDetail/closeJobOrderDetail/changeJobDetailTab`。
- Changes: 运行态轮询仅调用任务摘要和当前订单页加载器。

- [ ] **Step 1: 为纯状态规则写失败测试**

在 `jobDetailState.js` 中设计可独立测试的页签懒加载判定、任务切换重置和请求令牌判定；测试关闭详情后旧请求失效、切换任务重置两套文件页码、重复进入已加载页签不重复首载。

- [ ] **Step 2: 运行前端定向测试并确认失败**

Run in `yigongbao-backup-web`: `node --test test/backup-task-detail.test.js test/backup-flow.test.js`

Expected: FAIL，状态助手和新行为尚未实现。

- [ ] **Step 3: 拆分任务详情加载器**

将当前 `loadJobDetails` 的三接口 `Promise.all` 拆开；打开详情时只并行加载摘要与订单第一页。`loadJobFiles` 使用 `current/size` 参数并通过 `normalizePage` 写入任务文件分页状态，`loadOrderFiles` 额外携带 `sourceOrderId` 并写入独立状态。

- [ ] **Step 4: 增加可见性与慢请求保护**

任务/订单切换时生成新的请求令牌；写入状态前校验任务 ID、订单 ID 和令牌仍匹配。关闭详情时停止轮询、关闭抽屉并使未完成请求失效，但不重置任务列表页码。

- [ ] **Step 5: 收紧轮询与分页事件**

轮询只刷新任务摘要和当前订单页；任务文件、订单文件和日志只由页签/抽屉首次进入或用户翻页触发。终态任务和关闭详情立即停止轮询。

- [ ] **Step 6: 重跑前端状态测试**

Expected: PASS，并确认旧的“首次加载全量文件”断言已替换为懒加载断言。

### Task 3: 将任务详情改为近全屏 Dialog 和分页页签

**Files:**
- Modify: `yigongbao-backup-web/src/views/BackupView.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Create: `yigongbao-backup-web/src/components/jobs/JobFileTable.vue`
- Create: `yigongbao-backup-web/src/components/jobs/ExecutionLogPanel.vue`
- Modify: `yigongbao-backup-web/src/style-production.css`
- Modify: `yigongbao-backup-web/test/backup-task-detail.test.js`
- Modify: `yigongbao-backup-web/test/architecture.test.js`

**Interfaces:**
- `JobDetail.vue` consumes Task 2 的可见性、页签和加载状态。
- `JobFileTable.vue` accepts `records/loading/current/size/total`，emits `page-change/size-change`。
- `ExecutionLogPanel.vue` accepts 独立日志分页状态并 emits 分页事件。

- [ ] **Step 1: 写 Dialog、页签和懒加载结构失败测试**

断言 `BackupView` 不再渲染内联详情卡语义；`JobDetail` 使用 `el-dialog`、三个页签和关闭回调；文件页签使用 `JobFileTable` 与分页状态；任务日志使用 `ExecutionLogPanel`，不再包含嵌套 `el-dialog`。

- [ ] **Step 2: 实现近全屏任务详情外壳**

使用 `96vw`、`94vh`、`destroy-on-close=false` 和响应式全屏样式；头部摘要固定，正文独立滚动。保留任务状态、进度、容量、目标卷、失败原因、取消与重试操作。

- [ ] **Step 3: 实现订单/文件/日志页签**

订单页签保留服务端分页并移除展开行；文件页签复用 `JobFileTable`，首次进入才加载；执行日志页签复用 `ExecutionLogPanel`。切换页签不得重置其他页签当前页。

- [ ] **Step 4: 完成可读性与响应式样式**

固定标题区和操作区，窄屏时摘要由四列降为两列/一列，表格保持核心列优先；只在内容确实超宽时允许表格内部横向滚动，页面本身不产生横向滚动。

- [ ] **Step 5: 运行前端定向测试和构建**

Run in `yigongbao-backup-web`: `node --test test/backup-task-detail.test.js test/architecture.test.js`

Run in `yigongbao-backup-web`: `npm run build`

Expected: PASS，生产构建无 Vue 模板或样式错误。

### Task 4: 增加订单详情 Drawer 及订单级文件/日志分页

**Files:**
- Create: `yigongbao-backup-web/src/components/jobs/JobOrderDetailDrawer.vue`
- Modify: `yigongbao-backup-web/src/components/jobs/JobDetail.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: `yigongbao-backup-web/src/style-production.css`
- Modify: `yigongbao-backup-web/test/backup-task-detail.test.js`
- Modify: `yigongbao-backup-web/test/storage-volume.test.js`

**Interfaces:**
- `JobOrderDetailDrawer.vue` consumes 选中订单、订单文件分页、订单日志分页及降级资格。
- 点击订单“详情”调用 `openJobOrderDetail(row)`；抽屉文件请求复用 `/admin/jobs/{id}/files` 并附带 `sourceOrderId`。

- [ ] **Step 1: 写订单抽屉行为失败测试**

覆盖表格详情按钮、抽屉宽度和关闭事件、业务快照默认折叠、订单文件首次进入才加载、订单日志独立分页、降级备份按钮只在 `degradedBackupEligible` 时出现。

- [ ] **Step 2: 实现订单详情抽屉**

以约 820px（窄屏 100%）右侧 Drawer 展示基础信息、患者、创建时间、状态、失败证据、存储卷和相对位置；完整业务 JSON 放入默认折叠区，不再占用订单列表展开行。

- [ ] **Step 3: 接入订单文件与日志**

复用 `JobFileTable` 和 `ExecutionLogPanel`，但使用 Task 2 的独立订单级状态。抽屉切换订单时清理旧页数据并从第一页按需加载，关闭后旧请求不得回写。

- [ ] **Step 4: 保留受控订单操作**

把“降级备份”放在抽屉固定底部操作区；继续调用现有服务端资格校验接口。订单日志不再打开嵌套弹窗。

- [ ] **Step 5: 运行订单详情与卷状态回归测试**

Run in `yigongbao-backup-web`: `node --test test/backup-task-detail.test.js test/storage-volume.test.js test/backup-flow.test.js`

Expected: PASS；任务时卷快照与当前卷状态仍同时展示。

### Task 5: 完整回归、性能边界与人工验收

**Files:**
- Modify only if verification exposes defects in files already listed above.

- [ ] **Step 1: 运行后端完整测试**

Run: `mvn -f yigongbao-backup-server/pom.xml test`

Expected: PASS，无恢复、冷备、存储卷生命周期回归。

- [ ] **Step 2: 运行前端完整测试和构建**

Run in `yigongbao-backup-web`: `npm test`

Run in `yigongbao-backup-web`: `npm run build`

Expected: PASS。

- [ ] **Step 3: 检查网络请求边界**

使用浏览器开发者工具验证：打开详情只请求任务摘要与订单第一页；进入文件页只请求一页；翻页参数正确；3 秒轮询期间文件/日志请求计数不增长；订单抽屉请求含正确 `sourceOrderId`。

- [ ] **Step 4: 验证大任务展示**

用文件总数大于 100 的任务验证总数、页数、20/50/100 每页切换和最后一页；确认单次响应不超过选择的 `size`，关闭/重开后默认回到第一页且任务列表位置保持。

- [ ] **Step 5: 验证关键操作和响应式布局**

验证运行中取消、失败重试、关系损坏订单降级备份、任务/订单日志、终态停止轮询；在常见桌面宽度和窄屏下确认 Dialog/Drawer 可用且页面无整体横向滚动。

- [ ] **Step 6: 最终差异自审**

运行 `git status --short` 和针对本计划文件的 `git diff --check`；确认没有纳入或改写工作区中既有的无关改动，并记录所有验证命令及结果。
