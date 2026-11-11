# 本地备份系统：管理概览首页需求与技术设计

> 文档版本：2.0
> 重写日期：2026-11-11
> 状态：已实施，待预发布环境容量基准与浏览器联调
> 说明：本文完全替代同路径旧版内容。旧版仅作为历史讨论记录，不再作为实现或验收依据。

## 1. 文档目的

本文基于当前代码已经实现的订单备份、降级备份、线上恢复、整盘冷备封存、存储卷生命周期、COS 清理、账号同步、系统配置和统一任务日志能力，重新定义管理员首页“管理概览”的产品需求、统计口径、状态语义、数据来源、页面布局、后端接口、前端交互和验收标准。

本文同时承担需求说明和技术设计职责。实施人员应以本文和对应实施计划为唯一依据，不再从旧版概览文档恢复已被本文删除的规则。

## 2. 当前系统事实

### 2.1 已实现的业务模块

当前管理员控制台已经包含：

- 订单文件：查询真实或模拟订单快照、文件资产、存储位置、下载、补传和发起恢复；
- 订单备份：自动扫描、管理员立即扫描、手工备份、失败订单重试和管理员显式降级备份；
- 线上恢复：任务列表、任务弹窗、订单抽屉、文件明细和执行日志；
- 冷备管理：整盘封存任务、逐文件校验、冷备介质离线与重新接入；
- 存储卷管理：卷纳管、健康探测、活动/备用卷、卷切换、封存和生命周期操作；
- COS 清理：清理预览、许可确认和执行任务；
- 本地账号：线上账号同步、本地启停和本地密码修改；
- 系统配置：数据库配置、运行时校验、分类展示和版本控制；
- 通用任务：持久化任务、订单级结果、文件级证据和任务执行日志。

### 2.2 当前首页问题

现有 `/api/v1/admin/overview` 会在请求过程中逐卷执行存储探测并返回原始配置列表；现有前端则读取接口没有提供的统计字段、固定显示“服务正常”并直接展示原始 JSON。因此当前页面不是可信的管理概览，且访问离线盘或慢速网络卷时可能阻塞。

### 2.3 当前实现对新首页的约束

- 正式完整备份为 `REAL + COMPLETE`；
- 成功降级备份为 `REAL + INCOMPLETE`，是正式终态备份，不再自动补偿，不等同于模拟备份；
- 元数据模拟为 `METADATA_SIMULATION + NOT_APPLICABLE`，不得计入生产备份指标；
- 整盘冷备是卷状态转换，不复制、不移动订单包；
- 封存尝试是不可变历史，不能由卷当前层级覆盖；
- 订单下载和线上恢复通过制品副本与当前卷状态选择可读副本；
- 备份、恢复、补传、清理等任务使用 `bk_job`；整盘封存同时有独立的 `bk_volume_archive` 业务状态；
- 自动备份错过当天计划时间后不自动补跑，管理员可通过“立即执行扫描”补偿；
- 首页必须复用现有状态语义，不能建立与详情页冲突的第二套判断规则。

## 3. 首页定位

管理概览是管理员登录后的运行工作台，应让管理员在数秒内回答四个问题：

1. 系统现在还能否安全接收新的订单备份；
2. 已有正式备份中，有多少当前可读取和恢复；
3. 自动备份、账号同步、封存和其他运行活动最近是否正常；
4. 此刻有哪些问题需要管理员采取行动，应该进入哪个页面处理。

首页不是长期统计报表、任务中心、审计中心或系统配置页。首页提供结论、证据摘要和处理入口，详细操作仍在现有业务页面完成。

## 4. 目标、非目标与反目标

### 4.1 目标

- 展示可信的正式备份订单、文件和物理副本规模；
- 明确区分完整备份与成功降级备份；
- 展示今日和近 7 日真实备份结果；
- 展示活动热备卷、备用卷和冷备介质的运行能力；
- 根据当前事实生成可恢复、可消失、可下钻的有效告警；
- 统一展示备份、恢复、封存、补传和清理的近期运行活动；
- 展示自动备份、账号同步和自动清理的运行状态；
- 首页访问稳定、快速，不触发磁盘、NAS 或线上接口调用；
- 部分查询失败时保留可用分区，不使用零值伪装未知结果。

### 4.2 非目标

- 不计算“备份覆盖率”；当前系统没有线上全部应备订单的可靠分母；
- 不在首页执行切卷、封存、重试、恢复、清理或同步等写操作；
- 不建设告警确认、静默、指派、通知和历史生命周期平台；
- 不展示完整任务日志、逐文件校验列表、审计日志或原始 JSON；
- 不在首页实时探测存储卷、访问文件系统或调用线上服务；
- 不用首页替代订单文件、任务详情、冷备管理和存储卷详情；
- 不引入长期报表、容量预测或增长预测。

### 4.3 反目标

出现以下任一情况，即使页面可以打开也视为未完成：

- 固定显示“系统正常”或根据前端字符串自行推导健康状态；
- 将模拟备份计入真实订单、文件、今日结果或新增容量；
- 将成功降级备份排除出正式备份，或将其伪装为完整备份；
- 将正常离线冷备介质显示为异常；
- 将历史失败任务永久显示为当前有效告警；
- 将封存历史绑定到卷当前 `HOT/COLD` 状态，导致卷重新接入后历史消失；
- 首页刷新触发磁盘、NAS、Marker、容量或线上连接探测；
- 查询失败时使用 `0`、空数组或“无告警”掩盖未知；
- 同一订单因多个任务或多个物理副本被重复计数；
- 首页与下载、恢复或备份预检对同一卷给出相反的可用性结论。

## 5. 产品信息架构

页面按“风险优先、核心规模、运行趋势、处理入口”的顺序组织：

1. 页面标题、数据观察时间和刷新状态；
2. 系统健康结论与当前待处理问题；
3. 三张核心指标卡；
4. 近 7 日备份趋势与存储运行状态；
5. 近期运行活动与自动化运行状态。

桌面端推荐布局：

```text
┌──────────────────────────────────────────────────────────────┐
│ 管理概览                     数据截至 17:30     [刷新数据]   │
├───────────────────┬──────────────────────────────────────────┤
│ 系统健康结论      │ 当前待处理问题 Top 3                    │
│ 严重 1 / 警告 2  │ 问题摘要 + 对象 + 处理入口              │
├───────────────────┴──────────────────────────────────────────┤
│ 可恢复订单        │ 可恢复文件          │ 今日任务结果      │
├──────────────────────────────────┬───────────────────────────┤
│ 近 7 日备份结果与新增容量        │ 存储运行状态              │
├──────────────────────────────────┼───────────────────────────┤
│ 近期运行活动                     │ 自动化运行状态            │
└──────────────────────────────────┴───────────────────────────┘
```

## 6. 系统健康与当前告警

### 6.1 健康级别

后端返回唯一健康级别，前端只负责展示：

| 级别 | 中文结论 | 条件 |
|---|---|---|
| `CRITICAL` | 存在影响备份或读取的严重问题 | 至少一条有效严重告警 |
| `UNKNOWN` | 系统状态暂时无法可靠判断 | 告警或核心统计分区不可用，或存在阻断级数据质量问题 |
| `WARNING` | 存在需要关注的问题 | 至少一条警告，且不存在更高级别条件 |
| `SETUP_REQUIRED` | 待完成存储初始化 | 从未纳管存储卷且其他分区可用 |
| `NORMAL` | 系统运行正常 | 所有核心分区可用且无有效告警 |

整体请求失败时，前端必须用“刷新失败”覆盖旧健康结论。即使上一次成功结果为 `NORMAL`，也不能继续把它当作当前结论。

### 6.2 告警模型

当前告警由后端根据当前数据库事实动态生成，不新增告警实例表。每条告警至少包含 `alertKey`、`severity`、`category`、`code`、`title`、`message`、`evidenceAt`、`targetType`、`targetId`、`actionRoute` 和 `actionQuery`。

相同 `alertKey` 只返回一条。大量文件或订单异常必须聚合，不允许产生逐文件告警风暴。

`alertKey` 必须由 `code + targetType + targetId` 稳定生成。同一对象同一根因只显示最高优先级告警：检查点不存在时只产生 `*_CHECKPOINT_MISSING`；已有明确失败检查点时 `*_FAILED` 抑制同一槽位的 `*_MISSED` 或 `*_STALE`；首次启用且尚未到首次应执行时间不告警。

首次应执行时间以当前有效开关配置行的 `bk_config.changed_at` 为启用时间：自动备份取不早于启用时间的第一个 `backup.schedule.start-time` 计划分钟；账号同步取 `enabledAt + account.sync-delay-minutes`。有效开关配置行缺失或时间非法时，自动化分区返回 `UNKNOWN` 和数据质量消息，不猜测启用时间，也不产生“检查点缺失”告警。

### 6.3 告警规则

#### 存储与写入能力

| 代码 | 级别 | 触发条件 |
|---|---|---|
| `ACTIVE_VOLUME_COUNT_INVALID` | `CRITICAL` | 系统已经纳管卷，但不存在唯一活动热备卷 |
| `ACTIVE_VOLUME_UNAVAILABLE` | `CRITICAL` | 活动卷离线、不可用、停用、退役、Marker 异常或处于不允许写入的操作状态 |
| `ACTIVE_VOLUME_CAPACITY_HIGH` | `WARNING` | 活动卷的最新成功容量快照达到切换阈值 |
| `ACTIVE_VOLUME_CAPACITY_UNKNOWN` | `WARNING` | 活动卷没有合法或足够新的成功容量快照 |
| `STANDBY_VOLUME_NOT_READY` | `WARNING` | 活动卷已接近切换阈值或不可用，且不存在满足条件的备用卷 |
| `EXPECTED_VOLUME_UNAVAILABLE` | `WARNING` | 预期在线的启用卷不可用；正常主动离线冷备卷除外 |

备用卷“满足条件”仅依据持久化事实判断：`HOT/ONLINE/STANDBY/ENABLED/IDLE`，容量快照新鲜且未达到阈值。首页不为判断备用卷而执行探测。

#### 制品与可恢复性

| 代码 | 级别 | 触发条件 |
|---|---|---|
| `UNREADABLE_BACKUP_ARTIFACTS` | `CRITICAL` | 正式可用订单包因副本损坏、卷缺失或预期在线卷异常而完全不可读 |
| `DEGRADED_ARTIFACT_REDUNDANCY` | `WARNING` | 至少一个副本可读，但同一制品的其他活动副本异常 |
| `ARTIFACT_METADATA_INCOMPLETE` | `WARNING` | 活动副本缺少卷 ID、相对键、大小或必要校验信息 |

仅存在于健康、主动离线的 `COLD/OFFLINE/READ_ONLY/ENABLED/IDLE` 卷时，订单当前不计入“可恢复订单”，但不产生不可读制品告警。`REPAIR_REQUIRED`、卷引用缺失、预期在线卷不可用和零活动副本均不属于正常离线。

成功降级备份 `REAL + INCOMPLETE` 本身不是告警。它在指标卡中单独标识，以表达完整性而不是故障。

#### 整盘封存

| 代码 | 级别 | 触发条件 |
|---|---|---|
| `VOLUME_ARCHIVE_REPAIR_REQUIRED` | `CRITICAL` | 最新失败封存尝试仍对应源卷 `REPAIR_REQUIRED`，或卷与封存操作引用不一致 |
| `VOLUME_ARCHIVE_STATE_INCONSISTENT` | `CRITICAL` | 活动封存尝试与源卷/保障卷的操作状态不匹配 |

单独存在历史 `PREPARE_FAILED/VERIFY_FAILED/COMMIT_FAILED/CANCELLED/ABANDONED` 记录不构成当前告警。告警必须由未解除的当前卷状态支撑。

#### 自动备份

| 代码 | 级别 | 触发条件 |
|---|---|---|
| `BACKUP_SCHEDULE_FAILED` | `WARNING` | `BACKUP_CANDIDATE` 检查点当前状态为 `FAILED` |
| `BACKUP_SCHEDULE_MISSED` | `WARNING` | 自动备份开启，最近应执行的计划槽位已经过去，但该槽位后没有成功检查点 |
| `BACKUP_SCHEDULE_CHECKPOINT_MISSING` | `WARNING` | 自动备份开启但检查点不存在 |

自动备份遵循当前实现的“不补跑”语义。错过计划槽位后，首页显示已错过和下一次计划时间，并提供进入订单备份页的入口；首页不自动触发补偿扫描。管理员显式执行“立即扫描”且扫描成功后，`BACKUP_CANDIDATE.lastSuccessAt` 成为新的周期锚点；下一槽位精确按 `lastSuccessAt.toLocalDate().plusDays(intervalDays).atTime(configuredStartTime)` 计算，而不是在强制扫描时刻直接加天数。

#### 账号同步

| 代码 | 级别 | 触发条件 |
|---|---|---|
| `ACCOUNT_SYNC_FAILED` | `WARNING` | 账号同步开启且检查点当前状态为 `FAILED` |
| `ACCOUNT_SYNC_STALE` | `WARNING` | 最近成功时间超过 `max(syncDelay × 3, 60 分钟)` |
| `ACCOUNT_SYNC_CHECKPOINT_MISSING` | `WARNING` | 账号同步开启但检查点不存在 |

账号同步或线上通信被管理员关闭时显示为“已停用”，不产生告警。

### 6.4 不进入当前告警的内容

- 历史失败、部分失败或取消的备份和恢复任务；
- 当前已经解除卷异常的历史封存失败；
- 成功降级备份；
- 正常离线冷备介质；
- 管理员主动关闭的自动备份、同步、清理或恢复功能；
- 审计日志中的历史失败事件。

上述内容可以出现在近期运行活动或状态说明中，但不能影响当前健康结论。

## 7. 核心指标

### 7.1 通用规则

- 业务时区固定为 `Asia/Shanghai`。应用必须在 Spring 初始化和任何时间事实写入前，把进程默认时区设置为上海，并把 JDBC 会话、Jackson 及每个可独立执行的数据库初始化入口统一为 `+08:00`；因此现有 `LocalDateTime.now()`、MyBatis 自动填充和数据库 `CURRENT_TIMESTAMP` 写入同一上海本地时间。数据库 `DATETIME` 与 `Instant` 的转换必须使用统一上海时区编解码器，不得直接按 `ZoneOffset.UTC` 解释；外部协议要求 UTC 时，也必须先把上海本地时间绑定 `Asia/Shanghai`，再转换为同一瞬间的 UTC 偏移时间。概览策略额外注入 `Clock` 以便确定性测试，前端不使用浏览器时区重算；
- `REAL` 为生产备份，`METADATA_SIMULATION` 为模拟；
- 逻辑删除数据不参与统计；
- `null` 表示未知或不可计算，`0` 只表示可靠计算出的零；
- 同一订单按 `source_order_id` 去重；
- 订单、逻辑文件与物理副本分别计量，不能混用；
- 所有读写可用性规则必须来自共享策略，不能在概览 SQL 中复制另一套硬编码真值表。

### 7.2 可恢复订单

“可恢复订单”指当前至少存在一个可读正式订单包的去重订单。

必须同时满足：

1. 快照 `backup_mode=REAL`；
2. 快照 `snapshot_status=AVAILABLE`；
3. 订单包 `backup_mode=REAL` 且 `status=AVAILABLE`；
4. 存在 `artifact_type=ORDER_PACKAGE`、`lifecycle=ACTIVE`、`state in (PRESENT, VERIFIED)` 的活动副本；
5. 副本关联的存储卷当前满足共享可读策略。

同一订单存在多个正式包时，按以下规则归并：

1. 先找出当前至少有一个可读副本的正式可用包；查询必须保留零活动副本的正式包，不能因内连接提前丢弃；
2. 按 `(backup_completed_at IS NULL) ASC, backup_completed_at DESC, snapshot_id DESC, package_id DESC` 选择最新可读代表包；完成时间缺失同时进入数据质量摘要；
3. `complete`、`incomplete` 和 `file_count` 全部取自代表包，因此 `complete + incomplete = total`；
4. 最新正式包不可读但旧正式包可读时，允许回退到最新可读代表包，确保当前仍可恢复的历史版本不被误报为不可恢复。

每个订单最终只进入一个顶层互斥分类，优先级如下：

1. 任一正式可用包存在可读副本 → `readable`；
2. 否则只要存在损坏副本、卷引用缺失、预期在线卷不可用、元数据缺失或零活动副本 → `abnormalUnreadable`；
3. 只有全部健康活动副本都位于 `COLD/OFFLINE/READ_ONLY/ENABLED/IDLE` 的预期离线卷时 → `temporarilyOffline`。

卡片返回 `total`、`complete`、`incomplete`、`temporarilyOffline` 和 `abnormalUnreadable`。`total` 仅等于当前可读订单数，`complete` 与 `incomplete` 是其互斥拆分且两者之和必须等于 `total`；`temporarilyOffline` 与 `abnormalUnreadable` 是另外两个互斥的不可读分类，不包含在 `total` 中。不得将 `incomplete` 文案显示为“完整成功”。可读订单中其他异常冗余副本仍可产生 `DEGRADED_ARTIFACT_REDUNDANCY`，但不改变订单分类。

### 7.3 可恢复文件和受管物理容量

“可恢复文件”使用 7.2 所定义的每个可恢复订单代表包 `file_count` 求和。同一订单即使存在多个历史包或物理副本也只计算一次逻辑文件数。

“受管物理容量”统计 `ORDER_PACKAGE` 和 `SUPPLEMENT_FILE` 的活动健康物理副本 `size` 之和：

- 多个真实物理副本分别占用空间，应分别计量；
- 正常离线冷备副本仍属于受管物理容量；
- 缺少大小的副本不补零，返回已知下限及未知副本数量；
- 该值不是磁盘已用容量，也不是近 7 日新增容量。

卡片返回 `logicalFileCount`、`managedArtifactBytes`、`unknownFileCountPackageCount` 和 `unknownSizeCopyCount`。

### 7.4 今日结束任务中的订单结果

统计父任务 `job.finished_at` 落在 `Asia/Shanghai` 当日 `[00:00, 次日 00:00)` 的真实订单最终结果。该口径表示“今日结束任务中的订单结果”，不是订单自身在当天某时刻完成；现有 `bk_job_order` 没有独立完成时间，不伪造订单级时间精度。

纳入 `ORDER_BACKUP`、`MANUAL_BACKUP` 和 `DEGRADED_BACKUP`；排除模拟执行、`SIMULATED/SIMULATION_FAILED`、未终结任务和逻辑删除记录。

同一订单在当日结束的多个任务中出现时，按 `job.finished_at DESC, job.id DESC` 取最后一次订单结果。返回 `completeSucceeded`、`incompleteSucceeded`、`failed`、`skipped` 和 `total`。成功完整性优先根据最终发布快照的 `backup_completeness` 判断，不能仅依赖任务类型。

## 8. 近 7 日趋势

趋势固定返回今天及此前 6 个自然日，无数据日期补零。

页面在同一面板内使用两个独立图表：

1. 订单结果堆叠柱状图：完整成功、降级成功、失败、跳过；
2. 新增正式订单包逻辑容量趋势：每天新增可用正式订单包 `package_size`。

不使用双 Y 轴，不将物理副本容量与逻辑新增容量混为一条曲线。

每日订单结果沿用今日结果的去重规则；新增容量按快照 `backup_completed_at` 归日，只统计 `REAL + AVAILABLE` 的不同订单包。多物理副本不重复增加逻辑新增容量；缺少完成时间或大小的包被排除并进入数据质量摘要。

点击某日或某个结果段进入订单备份页，并携带日期和订单结果筛选。

## 9. 存储运行状态

### 9.1 首页展示内容

存储面板按运行优先级展示：

1. 当前活动热备卷；
2. 可用备用热备卷；
3. 当前冷备介质摘要；
4. 在线卷容量汇总。

活动热备卷展示卷名称、卷代码、挂载/写入/操作状态、已用/可用/总量、使用率、告警阈值、最近探测时间和最近成功容量观测时间。

备用卷展示满足持久化条件的数量和优先级最高候选卷。如果活动卷健康且容量充足，没有备用卷只是信息；只有活动卷接近阈值或不可用时才升级为警告。

冷备摘要展示当前 `COLD` 卷在线、正常离线和需要修复数量，同时展示累计成功封存次数和最近成功封存时间。封存历史来自 `bk_volume_archive`；原卷后来转为 `HOT/ACTIVE` 时历史仍然保留。

### 9.2 容量快照规则

首页只读取持久化容量快照，不执行探测。存储卷需要区分：

- `last_probed_at`：最近一次探测尝试时间；
- `capacity_observed_at`：最近一次合法成功容量快照时间。

探测失败只更新探测时间和错误，必须保留最近合法容量和 `capacity_observed_at`。

合法容量必须满足 `totalBytes > 0` 且 `0 <= usableBytes <= totalBytes`。使用率按 `(totalBytes - usableBytes) / totalBytes × 100` 计算，使用可防溢出的十进制定点运算并四舍五入保留 1 位小数；总量非法时返回 `null`，不返回 `0%`。

容量新鲜度阈值为 `max(storage.volume-health.interval-minutes × 3, 15 分钟)`。

在线容量汇总只纳入启用、在线、非停用、容量合法且快照新鲜的卷。离线冷备卷可显示“离线前快照”，但不进入在线容量汇总。

### 9.3 共享卷访问策略

首页、下载、恢复、副本解析和备份预检必须共用一个卷访问策略。

可读卷至少满足：未逻辑删除、`ONLINE`、`ENABLED`、非 `DISABLED`、层级为 `HOT` 或 `COLD + READ_ONLY`，且操作状态为 `IDLE` 或匹配活动封存尝试的合法 `ARCHIVE_GUARD`。

合法 `ARCHIVE_GUARD` 必须同时满足：卷仍为唯一 `HOT/ONLINE/ACTIVE`；`operation_ref_id` 指向未终结封存尝试；封存尝试的 `successor_volume_id` 等于该卷；封存阶段属于 `REQUESTED/SWITCHED/MANIFEST_FROZEN/VERIFYING/COMMITTING`。

其他非 `IDLE` 状态均不能被首页自行视为可读或可写。

共享策略必须同时提供 Java 判定和 SQL 谓词生成能力，两者来自同一规则定义并通过完整状态矩阵等价测试。概览聚合可读取候选行后在 Java 分类；订单列表的 `storageAccessible/storageIssue` 筛选必须在数据库分页和 `COUNT` 之前使用共享策略生成的谓词，禁止复制 SQL 真值表，也禁止分页后过滤。

## 10. 近期运行活动

### 10.1 展示范围

首页展示最近 10 条运行活动，统一覆盖自动/手工/降级订单备份、线上恢复、整盘冷备封存、补传文件、COS 清理预览与执行、自动清理，以及历史冷备写出、导入和热备清理任务。

### 10.2 统一活动模型

每条活动至少包含 `activityId`、`sourceType=JOB|VOLUME_ARCHIVE`、`sourceId`、`activityType`、中文名称、`status`、进度、订单结果、请求/开始/完成时间、存储摘要、失败摘要和受控下钻目标。

整盘封存状态以 `bk_volume_archive.phase` 为准，任务进度可以引用关联 `bk_job`；不能只看通用任务状态而丢失封存业务阶段。

运行中活动正常显示，但不建立单独 KPI。历史失败活动只影响行状态，不自动生成当前告警。

### 10.3 下钻

- 备份活动 → 订单备份任务弹窗；
- 恢复活动 → 线上恢复任务弹窗；
- 封存活动 → 冷备封存详情抽屉；
- 清理活动 → COS 清理页；
- 补传活动 → 对应订单文件页，无法可靠定位时只显示摘要；
- 未知历史任务类型 → 只读摘要，不导致整个首页失败。

首页不读取 `bk_job_execution_log`。任务日志在进入详情后按现有分页接口加载。

## 11. 自动化运行状态

### 11.1 自动备份

展示是否启用、当前执行模式、计划开始时间和周期、最近扫描尝试、最近扫描成功、最近扫描处理订单数、最近错误、下一次计划时间和是否错过最近计划槽位。`BACKUP_CANDIDATE` 成功仅表示候选扫描及任务创建阶段成功，不得标记为“最近备份成功”。

当前调度语义是“错过不补跑”。首页只说明状态并提供进入订单备份页的入口，不执行“立即扫描”。

### 11.2 账号同步

展示账号同步开关、线上通信开关、当前检查点状态、最近尝试、最近成功、最近处理数量和错误摘要。

### 11.3 COS 自动清理

展示自动清理开关、配置周期、最近一条 `AUTO_CLEANUP` 任务状态和完成时间。自动清理当前没有独立检查点时，首页不得伪造“下一次精确运行时间”。

### 11.4 线上恢复

恢复是管理员按需操作，不属于自动化流水线。首页可以显示恢复功能是否启用，但关闭不影响健康级别。

## 12. 权威数据来源

| 展示内容 | 主要事实来源 | 不使用的来源 |
|---|---|---|
| 可恢复订单 | `bk_order_snapshot`、`bk_order_package`、`bk_artifact_copy`、`bk_storage_volume` | 审计日志、任务总状态 |
| 完整/降级拆分 | `bk_order_snapshot.backup_completeness` | 仅凭任务类型猜测 |
| 可恢复文件 | `bk_order_package.file_count` | 物理副本条数 |
| 受管物理容量 | `bk_artifact_copy.size/state/lifecycle` | 存储卷已用容量 |
| 今日结束任务中的订单结果 | `bk_job.finished_at`、`bk_job_order`、发布快照 | 任务条数、订单自身完成时间 |
| 7 日新增容量 | `bk_order_snapshot.backup_completed_at`、`bk_order_package.package_size` | 多副本容量 |
| 活动/备用/冷备卷 | `bk_storage_volume` 持久化状态与容量快照 | 首页实时探测 |
| 封存历史 | `bk_volume_archive` | 卷当前层级 |
| 封存进度 | `bk_volume_archive` 聚合字段与关联 `bk_job` | 首页扫描全部 `bk_volume_archive_item` |
| 自动备份 | `bk_sync_cursor(BACKUP_CANDIDATE)` 与有效配置 | 调度器内存状态 |
| 账号同步 | `bk_sync_cursor(USER)` 与有效配置 | 审计日志 |
| 自动清理 | 有效配置与最近 `AUTO_CLEANUP` 任务 | 内存时间戳 |
| 近期活动 | `bk_job`、`bk_job_order`、`bk_volume_archive` | 任务日志全文 |
| 当前告警 | 以上当前事实动态推导 | 历史审计失败 |

## 13. 后端设计

### 13.1 接口

沿用管理员接口：

```http
GET /api/v1/admin/overview
GET /api/v1/admin/overview?refresh=true
```

`refresh=true` 只绕过最多 30 秒的响应缓存并读取最新已提交数据库状态，仍然不得执行磁盘、NAS 或线上调用。

### 13.2 响应结构

```json
{
  "observedAt": "2026-11-11T17:30:00",
  "health": { "level": "WARNING", "activeAlertCount": 2, "criticalAlertCount": 0, "warningAlertCount": 2 },
  "sections": {
    "summary": "AVAILABLE",
    "storage": "AVAILABLE",
    "activeAlerts": "AVAILABLE",
    "backupTrend": "AVAILABLE",
    "automation": "AVAILABLE",
    "recentActivities": "AVAILABLE"
  },
  "summary": {
    "readableOrders": { "total": 1280, "complete": 1272, "incomplete": 8, "temporarilyOffline": 50, "abnormalUnreadable": 2 },
    "readableFiles": { "logicalFileCount": 9650, "managedArtifactBytes": 348966092800, "unknownFileCountPackageCount": 0, "unknownSizeCopyCount": 0 },
    "todayBackup": { "completeSucceeded": 34, "incompleteSucceeded": 1, "failed": 2, "skipped": 1, "total": 38 }
  },
  "storage": { "activeVolume": {}, "standby": {}, "coldMedia": {}, "onlineCapacity": {} },
  "activeAlerts": [],
  "backupTrend": [],
  "automation": {},
  "recentActivities": [],
  "dataQuality": { "complete": true, "messages": [] },
  "sectionErrors": []
}
```

### 13.3 分区失败契约

`sections` 取值为 `AVAILABLE` 或 `UNAVAILABLE`。分区查询失败时对应数据字段必须为 `null`，不能返回零或空数组；`sectionErrors` 只返回分区、错误码、requestId 和安全中文消息。

不得返回 SQL、堆栈、绝对路径、配置值或业务敏感原文。告警分区不可用时，健康级别至少为 `UNKNOWN`；已经可靠计算出的 `CRITICAL` 可以保留更高级别。

### 13.4 后端组件边界

#### `AdminOverviewController`

只负责管理员鉴权、解析 `refresh`、调用服务和统一响应，不依赖 `StorageProbe`、卷 Mapper 或配置列表服务。

#### `AdminOverviewService`

负责编排分区、隔离失败、合并数据质量、调用告警和健康策略、管理短缓存并记录结构化耗时。缓存保存业务分区结果及其真实 `observedAt`；命中缓存时必须保留该数据观察时间，只重新绑定当前 `requestId` 和 `sectionErrors` 中的请求关联信息，禁止把首次请求 ID 返回给后续请求，也禁止把缓存数据伪装成刚查询的数据。

#### `AdminOverviewQueryMapper`

项目继续遵守“禁止 Mapper XML”的现有约束。`AdminOverviewQueryMapper` 使用 MyBatis 注解或 `@SelectProvider` 执行固定数量查询：可恢复性查询返回保留决策维度的副本候选行，由 Java 共享访问策略归类；今日和 7 日结果、新增容量、近期任务和最近自动清理可以在 SQL 中聚合。禁止在 SQL 中复制卷访问真值表，禁止 N+1。

副本候选行至少保留 `sourceOrderId`、`snapshotId`、`packageId`、`backupCompletedAt`、`completeness`、`fileCount`、`copyId`、`copyState`、`copyLifecycle`、`volumeId`、卷状态、`operationRefId` 及判断合法 `ARCHIVE_GUARD` 所需封存上下文。制品问题统计必须复用同一批分类结果，不能另写第二套可读性 SQL。

#### `StorageVolumeAccessPolicy`

统一卷的可读、可写、备用候选、正常离线和合法 `ARCHIVE_GUARD` 判断，并从同一规则定义生成 Java 判定与 MyBatis SQL 谓词。现有 `ArtifactReplicaResolver`、备份预检、概览策略和订单分页筛选必须复用该组件；Java/SQL 等价性由状态矩阵契约测试保证。

#### `StorageVolumeCapacitySnapshotPolicy`

统一更新探测尝试、最后成功容量和错误字段，确保失败探测不会清空合法历史容量。

#### `OverviewTimePolicy`

注入 `Clock`，统一 `Asia/Shanghai` 今日/7 日边界、自动备份计划槽位、账号同步陈旧阈值和容量新鲜度；应用级时区配置保证现有全部权威时间写入路径与数据库会话使用同一上海本地时间。

#### `AdminOverviewAlertService`

根据当前事实生成去重告警并计算健康级别。前端不实现业务告警规则。

### 13.5 数据库变更

`bk_storage_volume` 新增：

```sql
capacity_observed_at DATETIME(3) NULL COMMENT '最近一次合法成功容量快照时间'
```

迁移只在现有 `total_bytes/usable_bytes` 合法时用 `last_probed_at` 回填。迁移脚本必须同时增加字段和索引；`01-schema.sql` 同步字段，`02-indexes.sql` 同步索引，`yigongbao-backup-full-init.sql` 同步两者，实体字段同步映射。

至少补充概览查询索引：

- `bk_job(job_type, finished_at, id, is_deleted)`；
- `bk_job_order(source_order_id, status, job_id, is_deleted)`；
- `bk_order_snapshot(backup_mode, snapshot_status, backup_completed_at, source_order_id, is_deleted)`；
- `bk_order_package(backup_mode, status, snapshot_id, is_deleted)`；
- `bk_artifact_copy(artifact_type, lifecycle, state, artifact_id, storage_volume_id, is_deleted)`；
- `bk_volume_archive(phase, update_time, source_volume_id, is_deleted)`。

## 14. 前端设计

### 14.1 页面组件

建议拆分：

- `OverviewHealthPanel.vue`：健康结论与刷新失败覆盖状态；
- `OverviewAlertList.vue`：Top 3 当前告警和处理入口；
- `OverviewMetricGrid.vue`：三张核心指标卡；
- `OverviewTrendPanel.vue`：两个 7 日图表与文本摘要；
- `OverviewStoragePanel.vue`：活动卷、备用卷、冷备和容量；
- `OverviewRecentActivity.vue`：统一近期活动；
- `OverviewAutomationPanel.vue`：自动备份、同步和清理；
- `overviewModel.js`：格式化、空值处理、状态展示和下钻目标纯函数。

组件只通过 props/emits 工作，不直接访问接口或复制后端告警规则。

### 14.2 路由与下钻

现有 hash 路由扩展为结构化查询参数：

```text
#orders?backupMode=REAL&storageAccessible=true
#backup?jobId=123
#backup?finishedFrom=2026-11-11&finishedTo=2026-11-12&orderResult=FAILED
#restore?jobId=456
#cold-storage?archiveId=789
#storage-volumes?volumeId=12
#accounts?focus=sync
```

路由目标和参数使用白名单解析。目标页消费一次性聚焦参数后清理参数，避免普通返回页面时重复弹出抽屉。

### 14.3 图表

近 7 日只有固定 7 个点，不新增大型图表依赖。使用 Vue + 原生 SVG/CSS，并提供中文图例、悬浮提示、键盘焦点、`aria-label` 和同数据文本摘要。

### 14.4 状态、空态和错误态

- 首次加载：各分区独立骨架屏；
- 刷新：保留旧数据并显示局部 loading；
- 刷新失败且已有旧数据：保留旧数据，显示“数据可能已过期”、上次成功时间和 requestId；
- 首次请求失败：显示错误态，不渲染正常结论；
- 真实零数据：显示 `0` 和相应空态说明；
- 未知数据：显示“暂不可用”或“已知下限”，不显示 `0`；
- 全新系统：显示 `SETUP_REQUIRED` 和“前往纳管存储卷”；
- 正常离线冷备：显示“正常离线”，不使用危险色；
- 降级备份：使用警示色辅助标签“文件可能不完整”，不使用失败色。

### 14.5 视觉与响应式

- 复用 Element Plus 和现有 `StatusBadge`；
- 扩充公共字典以覆盖健康级别、降级备份和全部活动类型；
- 健康区域使用浅色状态背景，颜色不是唯一信息载体；
- 主指标字号 28–32px，卡片保持高信息密度；
- 容量进度条只对当前、新鲜、可计算数据使用阈值色；
- 离线前容量快照使用中性色和明确文字；
- 1366×768 下首屏能够看到健康区、三张指标卡和下一分区标题；
- 1100px 以下两列，700px 以下单列；
- 过长卷名、失败摘要和告警文本省略，并提供完整 tooltip。

## 15. 数据质量

查询成功但发现脏数据时返回 `dataQuality`，不得与分区查询失败混用。每项包含 `code`、`severity=BLOCKING|WARNING|INFO`、`section`、`affectedCount` 和中文安全消息。

至少覆盖正式快照与模拟包模式矛盾、活动副本引用卷缺失、副本缺少关键字段、容量非法、趋势包缺少完成时间或大小、配置非法回退默认值、封存操作引用与卷状态不一致。

`BLOCKING` 使健康级别至少为 `UNKNOWN`；`WARNING` 使健康级别至少为 `WARNING`。同一问题已经生成当前告警时，前端不重复显示第二张问题卡。

## 16. 权限、安全与日志

- 概览仅允许 `ADMIN`；
- 不返回密码、令牌、密钥引用、完整配置值、绝对根路径、Marker JSON 或异常堆栈；
- 错误摘要限制长度并清理敏感内容；
- 下钻只能使用后端允许的路由和 ID；
- 首页为只读页面，不写审计日志、不创建任务、不更新卷状态；
- 正常请求不输出逐订单、逐卷 INFO 日志；
- 成功请求记录总耗时和各分区耗时的结构化 DEBUG 或慢查询 WARN；
- 分区失败记录 requestId、分区、耗时和异常类型；
- 告警是查询结果，不因刷新重复写审计或错误日志。

## 17. 性能与可靠性

- 首页请求不得包含文件系统、NAS、Marker、容量或线上网络调用；
- 趋势固定 7 天，近期活动最多 10 条，告警返回聚合结果；
- 复杂统计使用数据库聚合或批量查询；
- 单条脏数据或单个异常卷不得阻塞整页；
- 所有计数和字节使用 64 位整数并防止溢出；
- 百分比由后端按 9.2 的公式和精度计算；
- 允许 30 秒短缓存，手动刷新绕过缓存；缓存保留真实 `observedAt`，不包含 requestId 等请求关联字段；
- 基准数据集：10 万订单快照、10 万订单包、30 万副本、10 万任务、100 万任务订单、100 个卷；
- MySQL 8、4 vCPU、8 GB、10 并发预热后，接口 P95 目标不超过 1 秒；
- 性能验证必须确认没有 N+1 和外部 I/O。

## 18. 测试要求

### 18.1 后端

至少覆盖：

1. 完整、降级、模拟和失败快照的统计差异；
2. 同订单多正式包按“最新可读”选代表包，覆盖旧完整+新降级、新包不可读但旧包可读，且多个副本不重复订单和逻辑文件；
3. 降级成功计入可恢复订单并单独拆分；
4. 正常离线冷备订单不计当前可恢复，但不告警；
5. 冷盘重新接入后订单重新计入；
6. 卷重新转为 HOT/ACTIVE 后封存历史仍存在；
7. 合法与非法 `ARCHIVE_GUARD` 的可读、可写和告警差异；
8. 探测失败保留最后成功容量与观测时间；
9. 容量合法性、1 位小数舍入、64 位防溢出、活动卷高容量、容量未知和无备用卷的告警抑制；
10. 自动备份失败、错过计划槽位、管理员关闭、首次无检查点、告警抑制及强制扫描后的新周期锚点；
11. 账号同步失败、陈旧、关闭、首次无检查点及告警抑制；
12. 历史失败任务不产生当前告警；
13. 封存失败且卷仍需修复才产生当前告警；
14. 分区真实空数据与分区查询失败的差异；
15. 数据质量严重度对健康级别的影响；
16. 近期活动覆盖已知和未知任务类型；
17. 管理员鉴权、OpenAPI 与实现一致；
18. 概览链路没有依赖或调用 `StorageProbe`，且刷新不写审计；
19. 固定查询数量且无 N+1；
20. 基准数据量下满足性能目标；
21. 进程启动前默认时区不是上海、UTC 日期边界和管理员强制扫描场景下，应用启动后全部权威时间写入、首页与调度器结论一致；
22. 错误摘要脱敏，缓存命中响应使用当前 requestId，正常请求无逐记录 INFO 日志。

### 18.2 前端

至少覆盖五种健康状态、真实零值和未知值、完整/降级拆分、正常与异常离线、趋势零数据和混合数据、告警下钻、已知和未知活动、结构化路由、刷新失败保留旧数据、禁止原始 JSON 和裸英文状态、三档响应式布局及图表无障碍兜底。

### 18.3 回归

- 订单包下载和线上恢复的副本选择结果与首页一致；
- 备份任务创建和预检使用同一卷访问策略；
- 冷备详情逐文件分页和历史数据不受影响；
- 恢复任务弹窗、订单抽屉和多 Tab 详情不受影响；
- 账号同步、COS 清理和系统配置原有功能不受影响；
- 首页访问不创建任务、不探盘、不写审计。

## 19. 验收场景

1. 有唯一健康活动卷且无异常时显示“系统运行正常”。
2. 全新系统没有卷时显示“待完成存储初始化”，不显示严重告警。
3. 完整真实备份和成功降级备份都计入可恢复订单，并明确拆分。
4. 模拟备份不进入任何生产指标和趋势。
5. 同订单两个健康副本时订单和逻辑文件只计一次，物理容量计两份。
6. 订单仅存在于正常离线冷备卷时暂不计可恢复，但不告警。
7. 冷备原盘重新接入后，无需复制文件即可重新计入可恢复订单。
8. 已封存卷重新设为 HOT/ACTIVE 后，封存历史和逐文件校验历史仍可下钻查看。
9. 活动卷不可用时显示严重告警；恢复后下一次查询自动消失。
10. 活动卷达到阈值且无备用卷时同时说明容量风险和接替能力不足。
11. 探测失败后仍显示上次成功容量，并标注数据时间和当前探测失败。
12. 自动备份错过计划时间时显示警告和下一次计划，不自动执行补偿。
13. 历史失败的恢复任务只出现在近期活动，不使系统健康永久告警。
14. 封存失败但卷已经修复后只保留历史活动，不继续告警。
15. 首页刷新不会访问磁盘、NAS、Marker 或线上服务。
16. 任一分区失败时其他分区继续展示，失败分区不伪装为零。
17. 点击备份、恢复、封存、卷和账号问题能够打开正确页面和详情。
18. 页面不展示原始配置、原始 JSON、绝对路径或异常堆栈。
19. 同订单存在旧完整和新降级可读包时，按最新可读包归类；新包不可读但旧包可读时回退旧包。
20. 同订单没有可读副本且同时存在正常离线冷备和损坏热备副本时，只计入异常不可读并产生告警。
21. 宿主默认时区不是上海时，应用启动会在任何业务写入前统一为上海；“今日”、权威时间事实、计划槽位和真实调度结果保持一致。
22. 缓存命中、部分失败和手动刷新均返回本次请求的 requestId，不泄漏上次请求标识。

## 20. 完成定义

只有同时满足以下条件，管理概览才视为完成：

1. 本文所有信息架构、指标口径、告警和下钻要求已实现；
2. 首页统计正确区分完整、降级和模拟备份；
3. 容量读取最后成功持久化快照，首页无外部 I/O；
4. 卷访问语义在首页、下载、恢复和备份预检之间一致；
5. 封存历史不受卷当前状态变化影响；
6. 后端分区失败、数据质量、权限和日志满足要求；
7. 前端正常、空、未知、失败和过期状态清晰；
8. 后端测试、前端测试、生产构建、数据库迁移和性能验证通过；
9. 验收场景全部通过；
10. 不存在已知 P1/P2 业务逻辑问题。
