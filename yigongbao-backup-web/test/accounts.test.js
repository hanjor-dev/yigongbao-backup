import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('status badge renders its value without runtime template compilation', async () => {
  const { StatusBadge } = await import('../src/components/StatusBadge.js');
  const render = StatusBadge.setup({ value: 'ENABLED', label: '' });
  assert.equal(typeof render, 'function');
  assert.equal(render().children, '已启用');
});

test('accounts page uses server pagination and exposes local status labels', () => {
  const view = fs.readFileSync(path.join(root, 'src', 'views', 'AccountsView.vue'), 'utf8');
  assert.match(view, /el-pagination/);
  assert.match(view, /accountTotal/);
  assert.match(view, /已启用/);
  assert.match(view, /已禁用/);
});

test('application refresh loads data for the current accounts route', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'composables', 'useBackupConsole.js'), 'utf8');
  assert.match(source, /loadCurrentPage/);
  assert.match(source, /case ['"]accounts['"]:\s*await loadAccounts/);
});
