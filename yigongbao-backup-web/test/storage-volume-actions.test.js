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

test('只有尚未处于活动或备用状态的在线热备卷可以设为备用', () => {
  assert.equal(typeof actions.canSetAsStandbyVolume, 'function');
  const hotVolume = {
    tier: 'HOT',
    mountStatus: 'ONLINE',
    operationStatus: 'IDLE',
    adminStatus: 'ENABLED',
  };
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, writeStatus: 'READ_ONLY' }), true);
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, writeStatus: 'DISABLED' }), true);
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, writeStatus: 'STANDBY' }), false);
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, writeStatus: 'ACTIVE' }), false);
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, mountStatus: 'OFFLINE', writeStatus: 'READ_ONLY' }), false);
  assert.equal(actions.canSetAsStandbyVolume?.({ ...hotVolume, operationStatus: 'SEALING', writeStatus: 'READ_ONLY' }), false);
});

test('设为备用成功后提供明确反馈', () => {
  assert.equal(typeof actions.storageVolumeActionSuccessMessage, 'function');
  assert.equal(actions.storageVolumeActionSuccessMessage?.('set-standby'), '存储卷已设为备用卷');
  assert.equal(actions.storageVolumeActionSuccessMessage?.('probe'), '');
});

test('冷备卷激活确认信息明确说明两侧状态和历史文件处理方式', () => {
  assert.equal(typeof actions.activeVolumeConfirmation, 'function');
  assert.equal(actions.activeVolumeConfirmation?.(
    { displayName: '原冷备盘', tier: 'COLD' },
    { displayName: '当前热备盘' },
  ), '确认将“原冷备盘”转为热备活动卷？\n\n当前热备活动卷“当前热备盘”将调整为热备只读；“原冷备盘”将调整为热备活动卷，后续新备份任务写入该卷。\n\n盘内已有历史冷备文件不会被移动或删除，相关订单和任务的当前存储卷状态将同步更新。');
});

test('存储卷容量展示区分正常、告警和容量未知', () => {
  assert.deepEqual(actions.storageCapacityPresentation?.({ usedPercent: 84.9, capacityWarning: false }), {
    percent: '84.9%',
    status: '容量正常',
    tone: 'success',
  });
  assert.deepEqual(actions.storageCapacityPresentation?.({ usedPercent: 90, capacityWarning: true }), {
    percent: '90.0%',
    status: '容量告警',
    tone: 'danger',
  });
  assert.deepEqual(actions.storageCapacityPresentation?.({ usedPercent: null, capacityWarning: null }), {
    percent: '不可用',
    status: '容量未知',
    tone: 'info',
  });
});
