import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('frontend uses a componentized Vue application entrypoint', () => {
  const entry = fs.readFileSync(path.join(root, 'src', 'main.js'), 'utf8');
  assert.match(entry, /import App from ['"]\.\/App\.vue['"]/);
  assert.ok(fs.existsSync(path.join(root, 'src', 'App.vue')));
});

test('application shell exposes refs as top-level template bindings', () => {
  const app = fs.readFileSync(path.join(root, 'src', 'App.vue'), 'utf8');
  assert.match(app, /const \{ token, notice, page \} = backup/);
  assert.match(app, /v-if="!token"/);
});

test('frontend keeps page views separate from the application shell', () => {
  for (const view of ['LoginView', 'OverviewView', 'OrdersView', 'BackupView', 'ColdView', 'ConfigsView']) {
    assert.ok(fs.existsSync(path.join(root, 'src', 'views', `${view}.vue`)), `${view}.vue should exist`);
  }
});

test('complex business pages expose reusable domain components', () => {
  for (const component of [
    'orders/OrderFilters', 'orders/OrderTable', 'orders/OrderDetail',
    'cold/ColdBatchTable', 'cold/ColdBatchDetail',
    'jobs/JobTable', 'jobs/JobDetail'
  ]) {
    assert.ok(fs.existsSync(path.join(root, 'src', 'components', `${component}.vue`)), `${component}.vue should exist`);
  }
});

test('domain actions do not pass DOM events where business arguments are optional', () => {
  const orderDetail = fs.readFileSync(path.join(root, 'src', 'components', 'orders', 'OrderDetail.vue'), 'utf8');
  const coldDetail = fs.readFileSync(path.join(root, 'src', 'components', 'cold', 'ColdBatchDetail.vue'), 'utf8');
  assert.match(orderDetail, /@click="downloadOrder\(\)"/);
  assert.match(coldDetail, /@click="cleanupColdBatch\(\)"/);
});

test('admin navigation has a runtime guard and job status normalization', () => {
  const consoleSource = fs.readFileSync(path.join(root, 'src', 'composables', 'useBackupConsole.js'), 'utf8');
  const jobTable = fs.readFileSync(path.join(root, 'src', 'components', 'jobs', 'JobTable.vue'), 'utf8');
  assert.match(consoleSource, /!isAdmin\.value/);
  assert.match(jobTable, /job\.state \|\| job\.status/);
});

test('restore task page passes unwrapped arrays to Element Plus tables', () => {
  const restoreView = fs.readFileSync(path.join(root, 'src', 'views', 'RestoreView.vue'), 'utf8');
  assert.match(restoreView, /const \{[\s\S]*tasks,[\s\S]*\} = useRestoreTasks\(\)/);
  assert.match(restoreView, /:tasks="tasks"/);
  assert.doesNotMatch(restoreView, /:tasks="restore\.tasks"/);
});

test('restore task detail embeds task and order execution logs in their detail tabs', () => {
  const detail = fs.readFileSync(path.join(root, 'src', 'components', 'restores', 'RestoreTaskDetail.vue'), 'utf8');
  const drawer = fs.readFileSync(path.join(root, 'src', 'components', 'restores', 'RestoreOrderDetailDrawer.vue'), 'utf8');
  const composable = fs.readFileSync(path.join(root, 'src', 'composables', 'useRestoreTasks.js'), 'utf8');
  assert.match(detail, /label="任务日志" name="logs"/);
  assert.match(detail, /<ExecutionLogPanel/);
  assert.match(drawer, /label="执行日志" name="logs"/);
  assert.match(drawer, /<ExecutionLogPanel/);
  assert.match(composable, /\/logs\?current=\$\{encodeURIComponent\(page\)\}/);
  assert.match(composable, /\/orders\/\$\{encodeURIComponent\(selectedOrder\.value\.sourceOrderId\)\}\/logs/);
});
