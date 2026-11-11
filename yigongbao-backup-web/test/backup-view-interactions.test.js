import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { compile } from '@vue/compiler-dom';
import { parse } from '@vue/compiler-sfc';
import * as Vue from 'vue';

function findVNode(node, predicate) {
  if (!node || typeof node !== 'object') return null;
  if (predicate(node)) return node;
  const children = Array.isArray(node.children) ? node.children : [];
  for (const child of children) {
    const found = findVNode(child, predicate);
    if (found) return found;
  }
  return null;
}

function containsText(node, text) {
  if (!node || typeof node !== 'object') return false;
  if (node.children === text) return true;
  return Array.isArray(node.children) && node.children.some((child) => containsText(child, text));
}

test('backup task refresh does not pass the pointer event as the page number', () => {
  const source = readFileSync(new URL('../src/views/BackupView.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const { code } = compile(descriptor.template.content, { mode: 'function' });
  const render = new Function('Vue', code)({ ...Vue, resolveComponent: (name) => name });
  const calls = [];
  const vnode = render({
    loadJobs: (...args) => calls.push(args),
    runScheduledBackupNow: () => {},
    scheduledBackupRunning: false,
    backupJobFilters: {},
    clearBackupJobFilters: () => {},
  });
  const refreshButton = findVNode(vnode, (item) => item.type === 'el-button' && containsText(item, '刷新任务'));

  assert.ok(refreshButton, 'refresh button should render');
  refreshButton.props.onClick({ type: 'pointerup', toString: () => '[object PointerEvent]' });
  assert.deepEqual(calls, [[]]);
});
