import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('admin backup page renders automatic backup tasks without candidate selection or task-center menu', () => {
  const view = read('../src/views/BackupView.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  const layout = read('../src/layouts/BackupLayout.vue');
  assert.match(layout, /key: 'backup'/);
  assert.doesNotMatch(layout, /key: 'jobs'/);
  assert.match(view, /JobTable/);
  assert.match(view, /JobDetail/);
  const table = read('../src/components/jobs/JobTable.vue');
  assert.match(table, /取消中/);
  assert.match(table, /元数据模拟/);
  assert.match(table, /:label="row\.cancelRequested/);
  assert.match(table, /label="存储卷"/);
  assert.match(table, /storageVolumeView/);
  assert.match(table, /volume\(row\)\.current/);
  assert.match(table, /volume\(row\)\.historical/);
  assert.match(table, /row\.targetStorageVolumeId/);
  assert.doesNotMatch(view, /createBackupJob|backupCandidates|type="selection"/);
  assert.match(consoleSource, /\/admin\/backup\/jobs/);
  assert.match(consoleSource, /\/admin\/backup\/schedule\/run-now/);
  assert.match(view, /立即执行扫描/);
  assert.match(consoleSource, /retry-failed/);
  assert.match(consoleSource, /degraded-backup/);
  assert.match(consoleSource, /await loadJobDetails\(job\.id\);\s*if \(!isTerminalJob\(selectedJob\.value\)\) startJobPolling\(\);/);
  assert.doesNotMatch(consoleSource, /\/admin\/backup\/candidates/);
  const detail = read('../src/components/jobs/JobDetail.vue');
  const orderDrawer = read('../src/components/jobs/JobOrderDetailDrawer.vue');
  const orderTable = read('../src/components/orders/OrderTable.vue');
  assert.match(detail, /orderCode/);
  assert.match(orderDrawer, /orderBusinessJson/);
  assert.match(detail, /simulationPath/);
  assert.match(detail, /源文件容量/);
  assert.match(detail, /模拟完成/);
  assert.match(orderTable, /displayJson/);
  assert.match(orderTable, /文件可能不完整/);
  assert.match(orderDrawer, /degradedBackupEligible/);
  assert.match(orderDrawer, /降级备份/);
  assert.match(detail, /canRetryFailedOrders/);
  assert.match(read('../src/components/orders/OrderDetail.vue'), /文件可能不完整/);
  assert.match(read('../src/components/jobs/JobTable.vue'), /canRetryFailedOrders/);
  assert.match(read('../src/components/orders/OrderBusinessFields.vue'), /全部业务字段/);
});

test('order file page uses a unified keyword and fixed filter option catalogs', () => {
  const filters = read('../src/components/orders/OrderFilters.vue');
  const table = read('../src/components/orders/OrderTable.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  assert.match(filters, /keyword/);
  assert.match(filters, /orderFilterOptions/);
  assert.match(filters, /orderFilterOptions\.localReplicaStates/);
  assert.match(filters, /needsPhysicalDelivery/);
  assert.match(consoleSource, /params\.set\('keyword'/);
  assert.match(consoleSource, /params\.set\('needsPhysicalDelivery'/);
  assert.match(consoleSource, /\/orders\/filter-options/);
  for (const field of ['snapshotStatus', 'sourceStatus', 'orgName', 'hospitalName', 'patientName', 'doctorName', 'phase']) {
    assert.doesNotMatch(filters, new RegExp(`v-model[^\\n]*${field}`));
  }
  for (const field of ['orgName', 'hospitalName', 'patientName', 'doctorName']) {
    assert.match(table, new RegExp(`row\\.${field}`));
  }
});

test('order file table keeps organization and patient columns compact and omits business columns', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /label="机构 \/ 医院" width="170"/);
  assert.match(table, /label="患者 \/ 医生" width="120"/);
  assert.doesNotMatch(table, /label="业务类型"/);
  assert.doesNotMatch(table, /row\.businessTypeName|row\.businessType/);
  assert.doesNotMatch(table, /label="业务状态"/);
  assert.doesNotMatch(table, /row\.statusName|row\.sourceStatus|row\.phaseName/);
});

test('order file table formats order creation time to seconds', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /formatJobOrderCreatedAt\(row\.orderCreatedAt\)/);
  assert.doesNotMatch(table, /prop="orderCreatedAt"/);
});

test('order file table shows the volume state inside the storage location column', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /label="存储卷 \/ 位置"[^\n]*storageMountState\(row\)/);
  assert.doesNotMatch(table, /label="卷状态"/);
});

test('order filters allocate more grid width to storage states than physical delivery', () => {
  const filters = read('../src/components/orders/OrderFilters.vue');
  const styles = read('../src/style-production.css');
  assert.match(filters, /class="physical-filter"/);
  assert.match(filters, /class="storage-filter"/);
  assert.match(styles, /\.order-filter-grid\s*\{[^}]*repeat\(12,minmax\(0,1fr\)\)/);
  assert.match(styles, /\.order-filter \.physical-filter\s*\{\s*grid-column:span 2;\s*\}/);
  assert.match(styles, /\.order-filter \.storage-filter\s*\{\s*grid-column:span 6;\s*\}/);
});
