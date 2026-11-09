import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('storage volume page exposes lifecycle actions and detail context', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'views', 'StorageView.vue'), 'utf8');
  for (const action of ['confirm-mounted', 'set-standby', 'to-cold', 'to-hot', 'disable', '卷详情', '运行任务']) {
    assert.match(source, new RegExp(action));
  }
});
