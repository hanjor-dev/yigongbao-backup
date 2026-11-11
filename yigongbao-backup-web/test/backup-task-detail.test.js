import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as detailState from '../src/jobDetailState.js';

const { buildJobFilePageUrl, createDetailRequestGuard, createSectionLoadTracker } = detailState;

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('task request tokens become stale after task switch or detail close', () => {
  const guard = createDetailRequestGuard();
  guard.openTask(101);
  const first = guard.captureTask();

  guard.openTask(102);
  const second = guard.captureTask();

  assert.equal(guard.isCurrentTask(first), false);
  assert.equal(guard.isCurrentTask(second), true);
  guard.closeTask();
  assert.equal(guard.isCurrentTask(second), false);
});

test('order request tokens are isolated from task refresh and invalidated on drawer switch', () => {
  const guard = createDetailRequestGuard();
  guard.openTask(101);
  const task = guard.captureTask();
  guard.openOrder(7);
  const firstOrder = guard.captureOrder();

  assert.equal(guard.isCurrentTask(task), true);
  assert.equal(guard.isCurrentOrder(firstOrder), true);

  guard.openOrder(8);
  const secondOrder = guard.captureOrder();
  assert.equal(guard.isCurrentOrder(firstOrder), false);
  assert.equal(guard.isCurrentOrder(secondOrder), true);
  guard.closeOrder();
  assert.equal(guard.isCurrentOrder(secondOrder), false);
});

test('only the latest request in each task or order data lane may update the view', () => {
  const guard = createDetailRequestGuard();
  guard.openTask(101);
  const oldFiles = guard.beginTaskRequest('files');
  const newFiles = guard.beginTaskRequest('files');
  const orders = guard.beginTaskRequest('orders');

  assert.equal(guard.isCurrentTaskRequest(oldFiles), false);
  assert.equal(guard.isCurrentTaskRequest(newFiles), true);
  assert.equal(guard.isCurrentTaskRequest(orders), true);

  guard.openOrder(7);
  const oldOrderFiles = guard.beginOrderRequest('files');
  const newOrderFiles = guard.beginOrderRequest('files');
  assert.equal(guard.isCurrentOrderRequest(oldOrderFiles), false);
  assert.equal(guard.isCurrentOrderRequest(newOrderFiles), true);
});

test('lazy sections load once per task or order key and reset independently', () => {
  const tracker = createSectionLoadTracker();

  assert.equal(tracker.shouldLoad('task-files', '101'), true);
  tracker.markLoaded('task-files', '101');
  assert.equal(tracker.shouldLoad('task-files', '101'), false);
  assert.equal(tracker.shouldLoad('task-files', '102'), true);

  tracker.markLoaded('order-files', '101:7');
  tracker.reset('task-files');
  assert.equal(tracker.shouldLoad('task-files', '101'), true);
  assert.equal(tracker.shouldLoad('order-files', '101:7'), false);

  tracker.resetAll();
  assert.equal(tracker.shouldLoad('order-files', '101:7'), true);
});

test('job file page URL always carries pagination and optionally scopes one order', () => {
  assert.equal(
    buildJobFilePageUrl(99, 2, 50),
    '/admin/jobs/99/files?current=2&size=50',
  );
  assert.equal(
    buildJobFilePageUrl(99, 1, 20, 75),
    '/admin/jobs/99/files?current=1&size=20&sourceOrderId=75',
  );
});

test('job file rows display business order number without falling back to source ID', () => {
  assert.equal(detailState.jobFileOrderCode({ orderCode: '20260707000021', sourceOrderId: 31 }), '20260707000021');
  assert.equal(detailState.jobFileOrderCode({ sourceOrderId: 31 }), '-');
});

test('job file rows display the original file name provided by the server', () => {
  assert.equal(detailState.jobFileOriginalFilename({ originalFilename: '下颌.stl' }), '下颌.stl');
  assert.equal(detailState.jobFileOriginalFilename({ assetIdentity: '20769211132306472961' }), '-');
});

test('backup task detail is a near-fullscreen dialog with lazy detail tabs', () => {
  const detail = read('../src/components/jobs/JobDetail.vue');
  assert.match(detail, /<el-dialog/);
  assert.match(detail, /v-model="jobDetailVisible"/);
  assert.match(detail, /@closed="closeJobDetail"/);
  assert.match(detail, /class="job-detail-dialog"/);
  assert.match(detail, /<el-tabs[^>]*jobDetailTab/);
  assert.match(detail, /label="订单明细" name="orders"/);
  assert.match(detail, /label="文件明细" name="files"/);
  assert.match(detail, /label="任务日志" name="logs"/);
  assert.match(detail, /label="订单号" width="150"/);
  assert.doesNotMatch(detail, /plain @click="changeJobDetailTab\('logs'\)">执行日志/);
  assert.match(detail, /<JobFileTable/);
  assert.match(detail, /<ExecutionLogPanel/);
  assert.equal((detail.match(/<el-dialog/g) || []).length, 1);
  assert.doesNotMatch(detail, /<el-card v-if="selectedJob"/);
});

test('shared task file table exposes server pagination controls', () => {
  const table = read('../src/components/jobs/JobFileTable.vue');
  assert.match(table, /label="订单号" width="150"/);
  assert.match(table, /jobFileOrderCode\(row\)/);
  assert.match(table, /label="文件名"/);
  assert.match(table, /jobFileOriginalFilename\(row\)/);
  assert.match(table, /<el-pagination/);
  assert.match(table, /current-page/);
  assert.match(table, /page-size/);
  assert.match(table, /emit\('page-change'/);
  assert.match(table, /emit\('size-change'/);
});

test('order drawer compacts file identity and state columns and moves step to the last column', () => {
  const drawer = read('../src/components/jobs/JobOrderDetailDrawer.vue');
  const table = read('../src/components/jobs/JobFileTable.vue');

  assert.match(drawer, /<JobFileTable[\s\S]*compact-identity/);
  assert.match(table, /compactIdentity/);
  assert.match(table, /资产：\{\{ row\.assetIdentity \|\| '-' \}\}/);
  assert.match(table, /v-if="!compactIdentity"[^>]*label="资产"/);
  assert.match(table, /v-if="!compactIdentity"[^>]*label="订单号"/);
  assert.match(table, /v-if="!compactIdentity"[^>]*label="状态"/);
  assert.match(table, /v-if="!compactIdentity"[^>]*label="进度"/);
  assert.match(table, /v-else label="状态 \/ 进度"/);
  assert.match(table, /class="file-state-progress"/);

  const storageColumn = table.indexOf('label="存储卷 / 相对位置"');
  const compactStateColumn = table.indexOf('v-else label="状态 / 进度"');
  const compactStepColumn = table.lastIndexOf('v-if="compactIdentity" label="步骤"');
  assert.ok(storageColumn >= 0);
  assert.ok(compactStateColumn > storageColumn);
  assert.ok(compactStepColumn > storageColumn);
});

test('execution logs render as an embedded panel rather than another dialog', () => {
  const panel = read('../src/components/jobs/ExecutionLogPanel.vue');
  assert.match(panel, /class="log-console"/);
  assert.match(panel, /<el-pagination/);
  assert.match(panel, /\.table-pagination\s*\{[^}]*margin-top:\s*16px/);
  assert.doesNotMatch(panel, /<el-dialog/);
});

test('order detail opens in a responsive drawer with files logs and collapsed business snapshot', () => {
  const drawer = read('../src/components/jobs/JobOrderDetailDrawer.vue');
  assert.match(drawer, /<el-drawer/);
  assert.match(drawer, /v-model="jobOrderDetailVisible"/);
  assert.match(drawer, /@closed="closeJobOrderDetail"/);
  assert.match(drawer, /name="overview"/);
  assert.match(drawer, /name="files"/);
  assert.match(drawer, /name="logs"/);
  assert.match(drawer, /<JobFileTable/);
  assert.match(drawer, /<ExecutionLogPanel/);
  assert.match(drawer, /<el-collapse/);
  assert.match(drawer, /orderBusinessJson/);
  assert.match(drawer, /selectedJobOrder\??\.degradedBackupEligible/);
  assert.match(drawer, /@click="degradedBackup\(selectedJobOrder\)"/);
});

test('task order table launches the drawer and no longer expands business JSON inline', () => {
  const detail = read('../src/components/jobs/JobDetail.vue');
  assert.match(detail, /import JobOrderDetailDrawer/);
  assert.match(detail, /<JobOrderDetailDrawer/);
  assert.match(detail, /@click="openJobOrderDetail\(row\)"/);
  assert.doesNotMatch(detail, /type="expand"/);
});
