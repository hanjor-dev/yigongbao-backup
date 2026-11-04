# Structured Order Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让线上备份清单同时提供订单原始数据和完整 `OrderListVO` 展示数据，并将本地订单快照的所有顶层订单字段结构化持久化和查询。

**Architecture:** 线上医工宝复用订单模块现有的 VO 转换、重建项目批量查询和审核信息组装能力，不复用带用户权限的分页入口。清单契约新增 `orderRaw` 与 `orderDisplay`，本地系统将标量字段写入 `bk_order_snapshot` 独立列，将嵌套对象写入独立 JSON 列，同时保留完整原始和展示 JSON 作为审计证据。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、MySQL 8、Vue 3、JavaScript、Element Plus、Node Test Runner

**Spec:** 本线程已确认的“完整 OrderListVO + 结构化订单快照”方案。

## Global Constraints

- 不修复线上 `OrderListVO.isClassicCase` 未填充的原始问题；本地经典案例字段继续从原始订单数据读取。
- `statusColor`、`regionalAudit`、`designAudit`、`rebuildProjectList` 使用独立 JSON 列。
- `order_raw_json`、`display_json`、`related_business_json` 保留，不以 JSON 代替常用结构化查询。
- 线上内部接口继续使用既有 HMAC 认证，不引入普通用户数据权限上下文。
- 数据库迁移必须兼容已有快照，初始化脚本必须可用于全新环境。

## Review Focus

- 旧版清单仅有 `order` 时，本地客户端应兼容读取且不导致备份中断。
- 展示字段为空或类型异常时，快照映射应安全降级并保留原始 JSON。
- 已有快照迁移时可从旧 JSON 回填的字段应正确回填，无法推导的展示字段保持 NULL。
- 金额、时间、长整型 ID 和 JSON 嵌套对象不得发生精度或时区损失。
- 列表筛选应只接受合法参数，分页和现有三地存储状态筛选不得回归。

---

### Task 1: 线上订单展示快照组装

**Files:**
- Modify: `yigongbao-parent/yigongbao-module-order/src/main/java/com/yigongbao/module/order/service/OrderMainService.java`
- Modify: `yigongbao-parent/yigongbao-module-order/src/main/java/com/yigongbao/module/order/service/impl/OrderMainServiceImpl.java`
- Test: `yigongbao-parent/yigongbao-module-order/src/test/java/.../OrderMainServiceImplTest.java`

**Interfaces:**
- Produces: `OrderListVO buildOrderListSnapshot(OrderMainEntity order)`，不执行登录用户数据权限过滤。

- [x] 增加测试，验证基础翻译、重建项目和审核信息均被组装。
- [x] 提取并实现单订单展示快照组装方法，复用现有 helper/convert。
- [x] 运行订单模块目标测试并确认通过。

### Task 2: 扩展线上 manifest 契约

**Files:**
- Modify: `yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/OrderManifest*.java`
- Modify: `yigongbao-parent/yigongbao-module-backup/src/main/java/com/yigongbao/module/backup/manifest/controller/OrderManifestController.java`
- Test: `yigongbao-parent/yigongbao-module-backup/src/test/java/.../OrderManifestServiceImplTest.java`

**Interfaces:**
- Produces: manifest JSON 的 `orderRaw`、`orderDisplay`；保留旧 `order` 兼容字段。

- [x] 增加契约测试，断言原始、展示和嵌套字段同时返回。
- [x] 使用 Task 1 的组装方法生成展示快照并纳入规范化摘要。
- [x] 运行备份模块测试并确认摘要稳定。

### Task 3: 本地清单客户端兼容新旧契约

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupManifest.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/HttpOnlineManifestClient.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/backup/HttpOnlineManifestClientTest.java`

**Interfaces:**
- Produces: `orderRaw()` 与 `orderDisplay()`，旧 `order` 自动同时作为两者回退值。

- [x] 编写新旧响应兼容测试。
- [x] 实现解析和回退逻辑。
- [x] 运行客户端测试。

### Task 4: 扩展本地订单快照数据库

**Files:**
- Create: `yigongbao-backup-server/sql/migration/2026-11-03-18-structured-order-snapshot.sql`
- Modify: `yigongbao-backup-server/sql/init/01-schema.sql`
- Modify: `yigongbao-backup-server/sql/init/02-indexes.sql`
- Modify: `yigongbao-backup-server/sql/init/yigongbao-backup-full-init.sql`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderSnapshotEntity.java`

**Interfaces:**
- Produces: `OrderListVO` 顶层标量列及四个嵌套 JSON 列。

- [x] 编写一次性增量迁移并从旧 `order_raw_json` 回填可推导字段。
- [x] 同步初始化表结构和索引。
- [x] 扩展实体并验证 MyBatis-Plus 映射。

### Task 5: 持久化完整结构化快照

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupEvidenceService.java`
- Create: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderSnapshotFieldMapper.java`
- Test: `yigongbao-backup-server/src/test/java/com/yigongbao/backup/order/OrderSnapshotFieldMapperTest.java`

**Interfaces:**
- Consumes: `BackupManifest.orderRaw()`、`orderDisplay()`。
- Produces: 完整 `OrderSnapshotEntity`。

- [x] 编写标量、金额、时间、JSON 和经典案例原始值映射测试。
- [x] 实现集中式类型安全映射，避免持久化服务堆积字段转换代码。
- [x] 运行持久化测试。

### Task 6: 扩展订单查询接口和前端展示

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/order/OrderController.java`
- Modify: `yigongbao-backup-web/src/components/orders/OrderFilters.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderTable.vue`
- Modify: `yigongbao-backup-web/src/components/orders/OrderDetail.vue`
- Modify: relevant frontend API/context/types and tests.

**Interfaces:**
- Produces: 支持订单号、机构、医院、患者、医生、业务类型、阶段、状态和时间范围查询的分页接口。

- [x] 增加后端查询参数和组合条件测试。
- [x] 增加前端字段渲染及筛选参数测试。
- [x] 实现后端筛选和前端核心业务字段列表/详情展示。
- [x] 运行后端、前端测试和前端构建。

### Task 7: 全链路回归验证

**Files:**
- Modify: tests and smoke-test documentation only when verification reveals a gap.

**Interfaces:**
- Consumes: Tasks 1-6 outputs.
- Produces: 可在 Windows 本地环境执行的完整订单备份与查询流程。

- [x] 运行线上订单和备份模块测试。
- [x] 运行本地后端全部测试。
- [x] 运行前端全部测试与生产构建。
- [x] 检查两个仓库差异，确认本次改动未引入敏感配置。
