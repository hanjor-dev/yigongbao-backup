import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('storage volume page exposes lifecycle actions and detail context', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  for (const action of ['confirm-mounted', 'set-standby', 'previewStorageArchive', 'prepare-offline', '卷详情', '运行任务']) {
    assert.match(source, new RegExp(action));
  }
});

test('discovered volume candidates display used available and total capacity', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  assert.match(source, /candidate\.usedBytes/);
  assert.match(source, /candidate\.usableBytes/);
  assert.match(source, /candidate\.totalBytes/);
  assert.match(source, /容量（已用 \/ 可用 \/ 总）/);
});

test('order and job views expose persisted volume and relative location at every level', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  const jobDetail = fs.readFileSync(path.join(root, 'src', 'components', 'jobs', 'JobDetail.vue'), 'utf8');
  assert.match(orderTable, /存储卷 \/ 位置/);
  assert.match(orderTable, /storageVolumeName/);
  assert.match(orderDetail, /row\.storageKey/);
  assert.match(orderDetail, /file\.storageVolumeName/);
  assert.match(jobDetail, /row\.storageVolumeName/);
  assert.match(jobDetail, /row\.storageKey/);
});

test('offline orders and supplements cannot start unavailable operations', () => {
  const orderTable = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderTable.vue'), 'utf8');
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  const ordersView = fs.readFileSync(path.join(root, 'src', 'views', 'OrdersView.vue'), 'utf8');
  assert.match(orderTable, /storageAccessible === false/);
  assert.match(orderDetail, /file\.storageAccessible === false/);
  assert.match(ordersView, /hasUnavailableSelection/);
});

test('storage volume detail displays identity references tasks and audit history', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  for (const field of ['archiveNote', 'markerJson', 'activeCopyCount', 'runningTaskCount', 'recentAudits']) {
    assert.match(source, new RegExp(field));
  }
});
