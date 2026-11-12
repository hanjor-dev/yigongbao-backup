# 管理概览首页 UI 优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变后端接口和统计口径的前提下，将管理概览优化为首屏可判断、可处置、可下钻的运维态势台。

**Architecture:** 继续使用现有 Vue 3 与 Element Plus 组件，通过 `OverviewView` 重组首屏结构，由健康组件吸收最高优先级告警。趋势、存储、自动化和活动组件只调整展示层，所有业务格式化和下钻仍复用现有模型与事件。

**Tech Stack:** Vue 3、Element Plus、CSS Grid、Node.js `node:test`、Vite。

**Spec:** `docs/superpowers/specs/2026-11-12-admin-overview-ui-optimization-design.md`

## Global Constraints

- 不新增或修改后端接口。
- 不引入新的运行时依赖和图表库。
- 保留现有刷新失败、分区不可用、空状态和下钻语义。
- 只修改概览页面、概览组件、概览样式、对应测试和本计划文档。
- 不提交当前工作区中已有的无关改动。

## Review Focus

- 无告警时风险区应保持紧凑且不出现空白处理按钮。
- 告警分区不可用时健康结论仍可独立展示。
- 容量未知时不能计算或显示伪造的剩余容量。
- 1100px 以下布局应自然单列，不能出现横向溢出。
- 所有交互按钮继续具备键盘焦点和明确文本。

---

### Task 1: 固定新版概览结构契约

**Files:**
- Modify: `yigongbao-backup-web/test/overview.test.js`

**Interfaces:**
- Consumes: 当前 Vue 单文件组件源码。
- Produces: 新首屏结构、容量文案和可读性约束的回归测试。

- [x] **Step 1: 写失败测试**
- [x] **Step 2: 运行 `node --test test/overview.test.js` 并确认因新版结构缺失而失败**
- [x] **Step 3: 保留失败证据后进入实现**

### Task 2: 重组首屏风险与指标区

**Files:**
- Modify: `yigongbao-backup-web/src/views/OverviewView.vue`
- Modify: `yigongbao-backup-web/src/components/overview/OverviewHealthHero.vue`
- Modify: `yigongbao-backup-web/src/components/overview/OverviewMetrics.vue`

**Interfaces:**
- Consumes: `health`、`activeAlerts`、`overviewLastSuccessAt` 和现有 `navigate` 事件。
- Produces: 工具条、组合风险区与紧凑指标区。

- [x] **Step 1: 让健康组件接收排序后的告警并发出 `navigate` 事件**
- [x] **Step 2: 将刷新移到内容工具条并删除重复页面标题**
- [x] **Step 3: 精简三个指标卡文案**
- [x] **Step 4: 运行概览测试并确认相关结构约束通过**

### Task 3: 优化趋势、存储和列表信息密度

**Files:**
- Modify: `yigongbao-backup-web/src/components/overview/OverviewTrend.vue`
- Modify: `yigongbao-backup-web/src/components/overview/OverviewStorage.vue`
- Modify: `yigongbao-backup-web/src/components/overview/OverviewAutomation.vue`
- Modify: `yigongbao-backup-web/src/components/overview/OverviewActivity.vue`
- Modify: `yigongbao-backup-web/src/overviewModel.js`

**Interfaces:**
- Consumes: 现有趋势、容量、自动化与活动数据。
- Produces: 可读容量摘要、中文自动化元数据和更清晰的趋势说明。

- [x] **Step 1: 为剩余容量和自动化元数据格式化补充失败测试**
- [x] **Step 2: 实现纯函数并确认测试转绿**
- [x] **Step 3: 接入存储、自动化和活动组件**

### Task 4: 重写概览视觉层并验证

**Files:**
- Modify: `yigongbao-backup-web/src/style-production.css`

**Interfaces:**
- Consumes: Tasks 2–3 的类名与结构。
- Produces: 紧凑首屏、顶部对齐双栏、可读图表与响应式布局。

- [x] **Step 1: 调整概览配色、间距、风险区和指标卡**
- [x] **Step 2: 取消双栏强制等高并提高图表字号与高度**
- [x] **Step 3: 完善 1100px、700px 响应式规则和焦点状态**
- [x] **Step 4: 运行前端全量测试和生产构建**
- [x] **Step 5: 浏览器检查 1366×768 与窄屏布局**
- [x] **Step 6: 执行 `git diff --check` 并审查仅包含目标文件**
