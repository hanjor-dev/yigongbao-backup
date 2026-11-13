import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('storage volume page exposes lifecycle actions and detail context', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  for (const action of ['confirm-mounted', 'set-standby', 'cancel-standby', 'repair-abnormal-state', 'previewStorageArchive', 'prepare-offline', '取消备用', '修复异常状态', '卷详情', '运行任务']) {
    assert.match(source, new RegExp(action));
  }
  assert.match(source, /canArchiveVolume\(row\)/);
  assert.match(source, /canCancelStandbyVolume\(row\)/);
  assert.match(source, /canRepairAbnormalState\(row\)/);
  assert.match(source, /reason/);
});

test('discovered volume candidates display used available and total capacity', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  assert.match(source, /candidate\.usedBytes/);
  assert.match(source, /candidate\.usableBytes/);
  assert.match(source, /candidate\.totalBytes/);
  assert.match(source, /容量（已用 \/ 可用 \/ 总）/);
});

test('managed volume capacity displays used percentage and warning status', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  assert.match(source, /storageCapacityPresentation\(row\)/);
  assert.match(source, /已用占比/);
  assert.match(source, /capacity\(row\)\.status/);
});

test('order and job views expose persisted volume and relative location at every level', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  const jobDetail = fs.readFileSync(path.join(root, 'src', 'components', 'jobs', 'JobDetail.vue'), 'utf8');
  assert.match(orderTable, /存储卷 \/ 位置/);
  assert.match(orderTable, /storageVolumeName/);
  assert.match(orderDetail, /row\.storageKey/);
  assert.match(orderDetail, /file\.storageVolumeName/);
  assert.match(jobDetail, /storageVolumeView/);
  assert.match(jobDetail, /row\.storageKey/);
});

test('offline orders and supplements cannot start unavailable operations', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  const ordersView = fs.readFileSync(path.join(root, 'src', 'views', 'OrdersView.vue'), 'utf8');
  assert.match(orderTable, /storageAccessible === false/);
  assert.match(orderDetail, /!supplementState\(file\)\.canDownload/);
  assert.match(ordersView, /hasUnavailableSelection/);
});

test('storage volume detail displays identity references tasks and audit history', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  for (const field of ['archiveNote', 'markerJson', 'activeCopyCount', 'runningTaskCount', 'recentAudits']) {
    assert.match(source, new RegExp(field));
  }
});

test('backup and restore task views separate current volume from task-time snapshot', () => {
  const backupTable = fs.readFileSync(path.join(root, 'src', 'components', 'jobs', 'JobTable.vue'), 'utf8');
  const backupDetail = fs.readFileSync(path.join(root, 'src', 'components', 'jobs', 'JobDetail.vue'), 'utf8');
  const restoreTable = fs.readFileSync(path.join(root, 'src', 'components', 'restores', 'RestoreTaskTable.vue'), 'utf8');
  const restoreDetail = fs.readFileSync(path.join(root, 'src', 'components', 'restores', 'RestoreTaskDetail.vue'), 'utf8');
  for (const source of [backupTable, backupDetail, restoreTable, restoreDetail]) {
    assert.match(source, /storageVolumeView/);
    assert.match(source, /historical/);
    assert.match(source, /current/);
  }
  for (const source of [backupDetail, restoreDetail]) {
    assert.doesNotMatch(source, /v-if="[^\"]*currentStorageTier \|\|[^\"]*currentMountStatus/);
  }
});

test('order views use current storage semantics instead of legacy hot and cold-batch labels', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  assert.doesNotMatch(orderTable, /冷备 \/ 批次/);
  assert.doesNotMatch(orderDetail, /热备状态/);
  assert.match(orderTable, /冷备副本/);
  assert.match(orderDetail, /存储状态/);
});

test('order list renders offline cold lookup metadata instead of only an offline badge', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  assert.match(orderTable, /offlineStorageSummary/);
  assert.match(orderTable, /storageAccessible === false/);
  assert.match(orderTable, /row\.storageDiagnosticReason/);
  assert.match(orderDetail, /offlineStorageSummary/);
  assert.match(orderDetail, /row\.storageUnavailableReason/);
  assert.match(orderDetail, /storageDiagnosticReason/);
});

test('order list distinguishes a present hot copy from a currently readable hot copy', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  assert.match(orderTable, /hotReplicaPresentation/);
  assert.match(orderTable, /不可读/);
});
