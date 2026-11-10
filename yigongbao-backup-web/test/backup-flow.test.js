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
  assert.match(table, /未获取/);
  assert.match(table, /取消中/);
  assert.match(table, /元数据模拟/);
  assert.match(table, /预计文件/);
  assert.match(table, /:label="row\.cancelRequested/);
  assert.match(table, /label="存储卷"/);
  assert.match(table, /row\.storageVolumeName/);
  assert.match(table, /row\.storageTier/);
  assert.match(table, /row\.targetStorageVolumeId/);
  assert.doesNotMatch(view, /createBackupJob|backupCandidates|type="selection"/);
  assert.match(consoleSource, /\/admin\/backup\/jobs/);
  assert.match(consoleSource, /\/admin\/backup\/schedule\/run-now/);
  assert.match(view, /立即执行扫描/);
  assert.match(consoleSource, /retry-failed/);
  assert.match(consoleSource, /await loadJobDetails\(job\.id\);\s*if \(!isTerminalJob\(selectedJob\.value\)\) startJobPolling\(\);/);
  assert.doesNotMatch(consoleSource, /\/admin\/backup\/candidates/);
  const detail = read('../src/components/jobs/JobDetail.vue');
  const orderTable = read('../src/components/orders/OrderTable.vue');
  assert.match(detail, /orderCode/);
  assert.match(detail, /orderBusinessJson/);
  assert.match(detail, /simulationPath/);
  assert.match(detail, /源文件总容量/);
  assert.match(detail, /模拟完成/);
  assert.match(orderTable, /displayJson/);
  assert.match(read('../src/components/orders/OrderBusinessFields.vue'), /全部业务字段/);
});

test('order file page uses a unified keyword and fixed filter option catalogs', () => {
  const filters = read('../src/components/orders/OrderFilters.vue');
  const table = read('../src/components/orders/OrderTable.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  assert.match(filters, /keyword/);
  assert.match(filters, /orderFilterOptions/);
  assert.match(filters, /needsPhysicalDelivery/);
  assert.match(consoleSource, /params\.set\('keyword'/);
  assert.match(consoleSource, /params\.set\('needsPhysicalDelivery'/);
  assert.match(consoleSource, /\/orders\/filter-options/);
  for (const field of ['snapshotStatus', 'sourceStatus', 'orgName', 'hospitalName', 'patientName', 'doctorName', 'phase']) {
    assert.doesNotMatch(filters, new RegExp(`v-model[^\\n]*${field}`));
  }
  for (const field of ['orgName', 'hospitalName', 'patientName', 'doctorName', 'businessTypeName', 'statusName']) {
    assert.match(table, new RegExp(`row\\.${field}`));
  }
});
