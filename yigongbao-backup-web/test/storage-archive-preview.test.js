import test from 'node:test';
import assert from 'node:assert/strict';

import { storageArchivePreviewSummary } from '../src/storageArchivePreview.js';

test('整盘封存预览分别展示当前卷中的订单包和补传文件数量', () => {
  assert.deepEqual(storageArchivePreviewSummary({
    orderCount: 2,
    orderPackageCount: 2,
    supplementFileCount: 0,
    managedFileCount: 2,
    totalBytes: 1789895119,
    periodLabel: '20260824-20260824',
  }), {
    periodLabel: '20260824-20260824',
    orderCount: 2,
    orderPackageCount: 2,
    supplementFileCount: 0,
    managedFileCount: 2,
    totalBytes: 1789895119,
  });
});
