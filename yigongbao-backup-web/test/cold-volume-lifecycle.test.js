import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('cold storage has an independent navigation route and archive data source', () => {
  const layout = read('../src/layouts/BackupLayout.vue');
  const app = read('../src/App.vue');
  const routes = read('../src/stores/routeStore.js');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  assert.match(layout, /key: 'cold-storage'.*label: '冷备管理'/s);
  assert.match(app, /ColdStorageView/);
  assert.match(app, /'cold-storage': ColdStorageView/);
  assert.match(routes, /'cold-storage'/);
  assert.doesNotMatch(routes, /'cold'\s*,/);
  assert.match(consoleSource, /api\('\/admin\/storage-volumes\/archives'\)/);
  assert.match(consoleSource, /route\.navigate\('cold-storage'\)/);
  const startArchive = consoleSource.match(/async function startStorageArchive[\s\S]*?\n  }/s)?.[0] || '';
  assert.doesNotMatch(startArchive, /route\.navigate\('backup'\)|openJob\(/);
  assert.doesNotMatch(consoleSource, /target === 'cold'|case 'cold'/);
});

test('cold storage page shows archive attempts media inventory and file-level failures', () => {
  assert.equal(existsSync(new URL('../src/views/ColdStorageView.vue', import.meta.url)), true);
  assert.equal(existsSync(new URL('../src/components/storage/VolumeArchiveDetail.vue', import.meta.url)), true);
  const page = read('../src/views/ColdStorageView.vue');
  const detail = read('../src/components/storage/VolumeArchiveDetail.vue');
  for (const field of ['封存任务', '冷备介质', 'periodLabel', 'managedFileCount', 'verifiedFileCount', 'failureMessage', 'physicalLocation', 'offlineLabelNote']) {
    assert.match(page + detail, new RegExp(field));
  }
  for (const field of ['storageKey', 'verificationStatus', 'expectedSize', 'actualSize', 'failureCode']) {
    assert.match(detail, new RegExp(field));
  }
  assert.match(page, /PREPARE_FAILED/);
  assert.match(detail, /PREPARE_FAILED/);
  assert.match(page, /PREPARE_FAILED[^\]]*VERIFY_FAILED[^\]]*COMMIT_FAILED[^\]]*CANCELLED/s);
});

test('archive file verification uses server pagination and opens only after an explicit detail action', () => {
  const page = read('../src/views/ColdStorageView.vue');
  const detail = read('../src/components/storage/VolumeArchiveDetail.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');

  assert.match(consoleSource, /archives\/\$\{encodeURIComponent\(archiveId\)\}\?current=\$\{current\}&size=\$\{size\}/);
  assert.doesNotMatch(consoleSource, /cold-storage-archive-focus/);
  assert.doesNotMatch(consoleSource.match(/async function loadColdStorage[\s\S]*?\n  }/s)?.[0] || '', /openColdStorageArchive/);
  assert.match(page, /closeColdStorageArchive\(\)/);
  assert.match(detail, /detail\.items\.records/);
  assert.match(detail, /el-pagination/);
  assert.match(detail, /label="订单编号"/);
  assert.match(detail, /row\.orderCode/);
  assert.doesNotMatch(detail, /label="订单 ID"/);
});

test('cold media remount selects a scanned candidate instead of accepting a free-form path', () => {
  assert.equal(existsSync(new URL('../src/components/storage/ColdVolumeMountDialog.vue', import.meta.url)), true);
  const dialog = read('../src/components/storage/ColdVolumeMountDialog.vue');
  assert.match(dialog, /storageCandidates/);
  assert.match(dialog, /candidate\.mountPath/);
  assert.match(dialog, /confirm-mounted/);
  assert.doesNotMatch(dialog, /prompt\(/);
});

test('storage page keeps the archive entry but no longer owns archive history details', () => {
  const storage = read('../src/views/StorageView.vue');
  assert.match(storage, /previewStorageArchive/);
  assert.match(storage, /navigate\('cold-storage'\)/);
  assert.doesNotMatch(storage, /封存文件明细/);
});
