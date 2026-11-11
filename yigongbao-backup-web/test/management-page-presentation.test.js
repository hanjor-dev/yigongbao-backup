import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

function readView(name) {
  return fs.readFileSync(path.join(root, 'src', 'views', name), 'utf8');
}

test('system config list keeps the description column compact', () => {
  const view = readView('ConfigsView.vue');
  assert.match(view, /<el-table-column label="说明" width="100">/);
});

test('storage volume details are opened from the fixed action column', () => {
  const view = readView('StorageView.vue');
  assert.doesNotMatch(view, /<el-table-column label="详情"/);
  assert.match(view, /<el-table-column label="操作"[^>]*fixed="right"/);
  assert.match(view, /<el-button[^>]*@click="openDetail\(row\)"[^>]*>详情<\/el-button>/);
});

test('storage volume standby action exposes pending feedback', () => {
  const view = readView('StorageView.vue');
  assert.match(view, /@click="setStandby\(row\)"/);
  assert.match(view, /:loading="standbyLoadingId === row\.id"/);
});

test('local account password action is labeled as changing the password', () => {
  const view = readView('AccountsView.vue');
  assert.match(view, />修改密码<\/el-button>/);
  assert.doesNotMatch(view, />重置密码<\/el-button>/);
});
