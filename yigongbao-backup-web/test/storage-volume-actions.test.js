import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const actions = await import('../src/storageVolumeActions.js').catch(() => ({}));

test('只读热备卷整盘封存时自动使用当前活动热备卷作为保障卷', () => {
  const volumes = [
    { id: 1, tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'ACTIVE' },
    { id: 2, tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'STANDBY' },
  ];
  assert.equal(typeof actions.archiveGuaranteeVolumeId, 'function');
  assert.equal(actions.archiveGuaranteeVolumeId?.({ id: 3, writeStatus: 'READ_ONLY' }, volumes), 1);
  assert.equal(actions.archiveGuaranteeVolumeId?.({ id: 1, writeStatus: 'ACTIVE' }, volumes), 2);
  assert.equal(actions.archiveGuaranteeVolumeId?.(
    { id: 3, writeStatus: 'READ_ONLY' },
    [{ id: 1, tier: 'HOT', mountStatus: 'UNAVAILABLE', writeStatus: 'ACTIVE' }],
  ), null);
});

test('只读热备卷整盘封存弹窗明确展示自动选中的活动热备保障卷', () => {
  const view = readFileSync(new URL('../src/views/StorageView.vue', import.meta.url), 'utf8');
  assert.match(view, /当前活动热备保障卷/);
  assert.match(view, /archiveGuaranteeVolumeId/);
});

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

test('只有空闲备用热备卷可以取消备用并转为只读', () => {
  assert.equal(typeof actions.canCancelStandbyVolume, 'function');
  assert.equal(actions.canCancelStandbyVolume?.({
    tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'STANDBY', operationStatus: 'IDLE', adminStatus: 'ENABLED',
  }), true);
  assert.equal(actions.canCancelStandbyVolume?.({
    tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'STANDBY', operationStatus: 'ARCHIVE_GUARD', adminStatus: 'ENABLED',
  }), false);
  assert.equal(actions.canCancelStandbyVolume?.({
    tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'READ_ONLY', operationStatus: 'IDLE', adminStatus: 'ENABLED',
  }), false);
});

test('整盘封存入口只对在线活动卷或只读热备卷开放', () => {
  assert.equal(typeof actions.canArchiveVolume, 'function');
  assert.equal(actions.canArchiveVolume?.({ tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'ACTIVE' }), true);
  assert.equal(actions.canArchiveVolume?.({ tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'READ_ONLY' }), true);
  assert.equal(actions.canArchiveVolume?.({ tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'STANDBY' }), false);
  assert.equal(actions.canArchiveVolume?.({ tier: 'HOT', mountStatus: 'UNAVAILABLE', writeStatus: 'READ_ONLY' }), false);
});

test('只有在线只读且需要修复的热备卷显示修复异常状态', () => {
  assert.equal(typeof actions.canRepairAbnormalState, 'function');
  assert.equal(actions.canRepairAbnormalState?.({
    tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'READ_ONLY', adminStatus: 'ENABLED', operationStatus: 'REPAIR_REQUIRED',
  }), true);
  assert.equal(actions.canRepairAbnormalState?.({
    tier: 'HOT', mountStatus: 'ONLINE', writeStatus: 'READ_ONLY', adminStatus: 'ENABLED', operationStatus: 'IDLE',
  }), false);
  assert.equal(actions.canRepairAbnormalState?.({
    tier: 'HOT', mountStatus: 'UNAVAILABLE', writeStatus: 'READ_ONLY', adminStatus: 'ENABLED', operationStatus: 'REPAIR_REQUIRED',
  }), false);
});

test('设为备用成功后提供明确反馈', () => {
  assert.equal(typeof actions.storageVolumeActionSuccessMessage, 'function');
  assert.equal(actions.storageVolumeActionSuccessMessage?.('set-standby'), '存储卷已设为备用卷');
  assert.equal(actions.storageVolumeActionSuccessMessage?.('cancel-standby'), '已取消备用，存储卷现为只读');
  assert.equal(actions.storageVolumeActionSuccessMessage?.('repair-abnormal-state'), '异常状态已修复，存储卷已恢复为只读空闲状态');
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

test('活动热备卷只有在线时才显示为健康', () => {
  assert.deepEqual(actions.activeVolumePresentation?.({
    displayName: 'D盘',
    volumeCode: 'volume-d',
    tier: 'HOT',
    mountStatus: 'ONLINE',
    writeStatus: 'ACTIVE',
  }), {
    type: 'success',
    message: '当前活动热备卷：D盘（volume-d）',
  });
  assert.deepEqual(actions.activeVolumePresentation?.({
    displayName: 'D盘',
    volumeCode: 'volume-d',
    tier: 'HOT',
    mountStatus: 'UNAVAILABLE',
    writeStatus: 'ACTIVE',
    lastErrorMessage: '卷标记校验失败',
  }), {
    type: 'error',
    message: '活动热备卷当前不可用：D盘（卷标记校验失败），新的备份与补传任务会被安全拒绝。',
  });
  assert.deepEqual(actions.activeVolumePresentation?.(null), {
    type: 'warning',
    message: '尚未配置活动热备卷；新的备份与补传任务会被安全拒绝。',
  });
});
