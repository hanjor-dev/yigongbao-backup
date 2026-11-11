import test from 'node:test';
import assert from 'node:assert/strict';

const actions = await import('../src/storageVolumeActions.js').catch(() => ({}));

test('重新接入的在线空闲冷备卷可以选择为热备活动卷', () => {
  assert.equal(typeof actions.canSetAsActiveVolume, 'function');
  assert.equal(actions.canSetAsActiveVolume?.({
    tier: 'COLD',
    mountStatus: 'ONLINE',
    writeStatus: 'READ_ONLY',
    operationStatus: 'IDLE',
    adminStatus: 'ENABLED',
  }), true);
  assert.equal(actions.canSetAsActiveVolume?.({
    tier: 'COLD',
    mountStatus: 'ONLINE',
    writeStatus: 'READ_ONLY',
    operationStatus: 'SEALING',
    adminStatus: 'ENABLED',
  }), false);
});

test('冷备卷激活确认信息明确说明两侧状态和历史文件处理方式', () => {
  assert.equal(typeof actions.activeVolumeConfirmation, 'function');
  assert.equal(actions.activeVolumeConfirmation?.(
    { displayName: '原冷备盘', tier: 'COLD' },
    { displayName: '当前热备盘' },
  ), '确认将“原冷备盘”转为热备活动卷？\n\n当前热备活动卷“当前热备盘”将调整为热备只读；“原冷备盘”将调整为热备活动卷，后续新备份任务写入该卷。\n\n盘内已有历史冷备文件不会被移动或删除，相关订单和任务的当前存储卷状态将同步更新。');
});
