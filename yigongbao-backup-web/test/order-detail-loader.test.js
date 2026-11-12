import test from 'node:test';
import assert from 'node:assert/strict';
import { loadOrderDetailSections } from '../src/orderDetailLoader.js';

test('supplement history remains available when another order detail section fails', async () => {
  const request = async (path) => {
    if (path.endsWith('/assets')) return { data: [{ id: 'asset-1' }] };
    if (path.endsWith('/supplements')) return {
      data: [{ fileId: 'file-1', fileName: 'history.step' }],
    };
    throw new Error('存储位置暂不可用');
  };

  const result = await loadOrderDetailSections('2120690864371527682', request);

  assert.deepEqual(result.assets, [{ id: 'asset-1' }]);
  assert.deepEqual(result.supplements, [{ fileId: 'file-1', fileName: 'history.step' }]);
  assert.equal(result.storageLocation, null);
  assert.deepEqual(result.errors, [{ section: '存储位置', message: '存储位置暂不可用' }]);
});
