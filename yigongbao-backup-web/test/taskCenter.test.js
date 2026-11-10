import test from 'node:test';
import assert from 'node:assert/strict';
import { coldBatchCapabilities, coldImportDiagnostic, extractJobId, isTerminalJob, jobProgress, normalizePage, offlineStorageSummary, replaceJobById, storageMountState, storageState, storageVolumeView, supplementTaskState, taskFailureSummary } from '../src/taskCenter.js';
import { statusLabel, statusTone, workflowLabel } from '../src/components/StatusBadge.js';

test('extractJobId handles command response envelope', () => {
  assert.equal(extractJobId({ data: { jobId: 'job-1' } }), 'job-1');
  assert.equal(extractJobId({ data: { id: 'job-2' } }), 'job-2');
  assert.equal(extractJobId({ data: { task: { jobId: 'job-3' } } }), 'job-3');
  assert.equal(extractJobId({ data: {} }), null);
});

test('jobProgress derives bounded file or order progress', () => {
  assert.equal(jobProgress({ fileCompleted: 25, fileTotal: 100 }), 25);
  assert.equal(jobProgress({ orderCompleted: 1, orderTotal: 2 }), 50);
  assert.equal(jobProgress({ fileCompleted: 3, fileTotal: 0, orderCompleted: 0, orderTotal: 0 }), 0);
  assert.equal(jobProgress({ fileCompleted: 120, fileTotal: 100 }), 100);
  assert.equal(jobProgress({ state: 'RUNNING', fileCompleted: 12, fileTotal: 12,
    orderCompleted: 2, orderFailed: 0, orderTotal: 5 }), 40);
});

test('isTerminalJob recognizes terminal states', () => {
  assert.equal(isTerminalJob({ state: 'SUCCEEDED' }), true);
  assert.equal(isTerminalJob({ status: 'SUCCEEDED' }), true);
  assert.equal(isTerminalJob({ state: 'CANCELLED' }), true);
  assert.equal(isTerminalJob({ state: 'PARTIAL_FAILED' }), true);
  assert.equal(isTerminalJob({ state: 'RUNNING' }), false);
});

test('replaceJobById synchronizes refreshed cancellation state into task lists', () => {
  const original = [{ id: '1', state: 'RUNNING', cancelRequested: false }, { id: '2', state: 'CREATED' }];
  const refreshed = { id: 1, state: 'CANCELLED', cancelRequested: true };
  assert.deepEqual(replaceJobById(original, refreshed), [refreshed, original[1]]);
  assert.deepEqual(replaceJobById(original, { id: 3, state: 'RUNNING' }), original);
});

test('supplementTaskState separates browser upload and background processing states', () => {
  assert.deepEqual(supplementTaskState(null, 42), { status: 'UPLOADING', percent: 42, message: '正在上传文件' });
  assert.deepEqual(supplementTaskState({ state: 'RUNNING', fileCompleted: 0, fileTotal: 1 }, 100), { status: 'PROCESSING', percent: 0, message: '文件已上传，后台正在处理' });
  assert.deepEqual(supplementTaskState({ state: 'SUCCEEDED' }, 100), { status: 'SUCCEEDED', percent: 100, message: '补传完成' });
  assert.deepEqual(supplementTaskState({ state: 'FAILED', failureEvidence: '格式不允许' }, 100), { status: 'FAILED', percent: 100, message: '格式不允许' });
});

test('normalizePage preserves server pagination metadata and safe defaults', () => {
  assert.deepEqual(normalizePage({ records: [{ id: 1 }], current: 2, size: 20, total: 41, pages: 3 }), {
    records: [{ id: 1 }], current: 2, size: 20, total: 41, pages: 3
  });
  assert.deepEqual(normalizePage(undefined), { records: [], current: 1, size: 20, total: 0, pages: 0 });
});

test('storageState distinguishes hot backup availability from missing cold backup', () => {
  assert.equal(storageState({ hotRollup: 'PRESENT', coldRollup: 'ABSENT' }).canDownload, true);
  assert.equal(storageState({ hotRollup: 'ABSENT', coldRollup: 'PRESENT' }).message, '文件位于离线冷备，请联系管理员调取');
  assert.equal(storageState({ hotRollup: 'UNKNOWN' }).canDownload, false);
  assert.deepEqual(storageState({
    hotRollup: 'PRESENT', storageVolumeId: 7, storageTier: 'COLD', storageMountStatus: 'OFFLINE', storageAccessible: false,
  }), { canDownload: false, message: '订单包所在冷备卷未挂载；请插入对应冷备盘并由管理员确认挂载' });
});

test('offline cold storage guidance includes the physical lookup information', () => {
  const order = {
    storageAccessible: false,
    storageTier: 'COLD',
    storageUnavailableReason: 'STORAGE_VOLUME_OFFLINE：冷备介质未挂载',
    storageOfflineLabelNote: '标签：2026-Q1',
    storagePhysicalLocation: '档案室 A-03',
    storagePeriodLabel: '20260101-20260315',
  };
  assert.equal(offlineStorageSummary(order), '标签：2026-Q1 · 档案室 A-03 · 20260101-20260315');
  assert.match(storageState(order).message, /标签：2026-Q1/);
  assert.match(storageState(order).message, /档案室 A-03/);
  assert.match(storageState(order).message, /20260101-20260315/);
  assert.match(storageState(order).message, /插入对应冷备盘并由管理员确认挂载/);
});

test('storageMountState does not mislabel an online but unreadable copy as offline', () => {
  assert.equal(storageMountState({ storageMountStatus: 'ONLINE', storageAccessible: false }), 'ONLINE');
  assert.equal(storageMountState({ storageMountStatus: 'OFFLINE', storageAccessible: false }), 'OFFLINE');
  assert.equal(storageMountState({ storageAccessible: false }), 'UNAVAILABLE');
});

test('storageVolumeView separates immutable task snapshot from current volume state', () => {
  assert.deepEqual(storageVolumeView({
    storageVolumeId: 7,
    storageVolumeName: 'D盘',
    storageTier: 'HOT',
    currentStorageVolumeName: '归档盘-A',
    currentStorageTier: 'COLD',
    currentMountStatus: 'OFFLINE',
  }), {
    name: '归档盘-A',
    current: '冷备 / 离线',
    historical: '任务时：D盘 / 热备',
  });
});

test('storageVolumeView never presents a task snapshot as the current volume when lookup failed', () => {
  assert.deepEqual(storageVolumeView({
    storageVolumeId: 7,
    storageVolumeName: '旧归档盘',
    storageTier: 'HOT',
    currentStorageResolved: false,
    currentStorageUnavailableReason: 'STORAGE_VOLUME_MISSING：存储卷台账不存在',
  }), {
    name: '卷 #7（当前台账不可用）',
    current: 'STORAGE_VOLUME_MISSING：存储卷台账不存在',
    historical: '任务时：旧归档盘 / 热备',
  });
});

test('storageVolumeView treats a legacy snapshot without volume id as historical only', () => {
  assert.deepEqual(storageVolumeView({
    storageVolumeName: '旧盘',
    storageTier: 'HOT',
  }), {
    name: '当前卷状态未知',
    current: '历史记录未保存存储卷 ID，无法解析当前卷',
    historical: '任务时：旧盘 / 热备',
  });
});

test('storageVolumeView keeps resolved current volumes visible when only part of a multi-volume task is unresolved', () => {
  assert.deepEqual(storageVolumeView({
    storageVolumeId: 7,
    storageVolumeName: '任务时热备盘',
    storageTier: 'HOT',
    currentStorageVolumeName: '归档盘-A',
    currentStorageTier: 'COLD',
    currentMountStatus: 'ONLINE',
    currentStorageResolved: false,
    currentStorageUnavailableReason: '一个或多个关联存储卷台账不存在',
  }), {
    name: '归档盘-A（部分台账不可用）',
    current: '冷备 / 在线；一个或多个关联存储卷台账不存在',
    historical: '任务时：任务时热备盘 / 热备',
  });
});

test('taskFailureSummary extracts structured result and evidence failure details', () => {
  assert.equal(taskFailureSummary({ failureCode: 'UPLOAD_FAILED', failureEvidence: 'COS timeout' }), 'UPLOAD_FAILED：COS timeout');
  assert.equal(taskFailureSummary({ failureCode: 'RESTORE_FAILED', resultJson: '{"failureMessage":"线上订单冲突"}' }), 'RESTORE_FAILED：线上订单冲突');
  assert.equal(taskFailureSummary({}), '');
});

test('cold batch capabilities only allow verified whole-batch cleanup', () => {
  assert.deepEqual(coldBatchCapabilities({ status: 'COMPLETED', hotCleanupStatus: 'PENDING' }), {
    canVerify: true, canCleanup: false, isCleaned: false, status: 'COMPLETED'
  });
  assert.equal(coldBatchCapabilities({ status: 'VERIFIED', hotCleanupStatus: 'READY' }).canCleanup, true);
  assert.equal(coldBatchCapabilities({ status: 'VERIFIED', hotCleanupStatus: 'CLEANED' }).canCleanup, false);
});

test('cold import diagnostic explains manual disk copy when scan is empty', () => {
  assert.match(coldImportDiagnostic([]), /手动复制/);
  assert.match(coldImportDiagnostic(['CB-1.zip']), /发现 1 个/);
});

test('statusTone maps common operational states to stable visual tones', () => {
  assert.equal(statusTone('COMPLETED'), 'success');
  assert.equal(statusTone('RUNNING'), 'progress');
  assert.equal(statusTone('FAILED'), 'danger');
  assert.equal(statusTone('PARTIAL_FAILED'), 'danger');
  assert.equal(statusTone('RETRYING'), 'progress');
  assert.equal(statusTone('UNKNOWN'), 'neutral');
  assert.equal(statusTone('HOT'), 'warm');
  assert.equal(statusTone('COLD'), 'cold');
  assert.equal(statusTone('ONLINE'), 'success');
  assert.equal(statusTone('OFFLINE'), 'neutral');
  assert.equal(statusTone('VERIFYING'), 'progress');
  assert.equal(statusTone('REPAIR_REQUIRED'), 'danger');
  assert.equal(statusTone('PARTIAL'), 'warning');
});

test('statusLabel localizes storage archive verification and workflow values', () => {
  const cases = {
    HOT: '热备', COLD: '冷备', IMPORT: '导入区',
    ONLINE: '在线', OFFLINE: '离线', UNAVAILABLE: '不可用',
    ACTIVE: '活动', STANDBY: '备用', READ_ONLY: '只读', DISABLED: '已停用',
    ENABLED: '已启用', RETIRED: '已退役', IDLE: '空闲', SEALING: '封存中',
    ARCHIVE_GUARD: '封存保护中', GOING_OFFLINE: '准备离线', MOUNTING: '挂载确认中',
    REPAIR_REQUIRED: '需要修复', REQUESTED: '已申请', SWITCHED: '已切换活动卷',
    MANIFEST_FROZEN: '清单已冻结', VERIFYING: '校验中', COMMITTING: '提交中',
    PREPARE_FAILED: '准备失败', VERIFY_FAILED: '校验失败', COMMIT_FAILED: '提交失败',
    ABANDONED: '已放弃', VERIFIED: '已校验', PENDING: '待处理',
    ORDER_PACKAGE: '订单备份包', SUPPLEMENT_FILE: '补传文件',
    READY: '待清理', CLEANED: '已清理', FORCE_OVERWRITE: '强制覆盖', REJECT: '拒绝冲突',
    METADATA_SIMULATION: '元数据模拟', REAL: '真实执行', LOCAL: '本地磁盘', HOT_NAS: '热备网络存储',
    LOGIN_SUCCESS: '登录成功', ACCOUNT_STATUS_CHANGE: '变更账号状态', STORAGE_VOLUME_UNAVAILABLE: '存储卷不可用',
    STORAGE_VOLUME_PREPARE_OFFLINE: '存储卷准备离线', ARTIFACT_CHECKSUM_FAILED: '文件校验和失败', OK: '成功', AUTH_FAILED: '认证失败',
  };
  for (const [value, label] of Object.entries(cases)) assert.equal(statusLabel(value), label, value);
  assert.equal(statusLabel('FUTURE_STATE'), 'FUTURE_STATE');
  assert.equal(statusLabel(0), '0');
});

test('workflowLabel localizes file steps and persisted log stages', () => {
  const cases = {
    SUPPLEMENT: '补传文件', HOT_CLEANUP: '热备清理', COLD_WRITE: '冷备写出', COLD_IMPORT: '冷备导入',
    COS_CLEANUP: 'COS 清理', BACKUP_SIMULATION: '备份模拟', BACKUP_ROLLBACK: '备份回滚', STARTED: '已开始',
    FINAL_VERIFY: '最终校验', CLEANUP_EVALUATE: '清理评估', SUPPLEMENT_COMMITTED: '补传提交完成',
  };
  for (const [value, label] of Object.entries(cases)) assert.equal(workflowLabel(value), label, value);
  assert.equal(workflowLabel('BACKUP_ORDER_75'), '备份订单 75');
  assert.equal(workflowLabel('FUTURE_STAGE'), 'FUTURE_STAGE');
});
