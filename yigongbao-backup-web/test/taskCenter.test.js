import test from 'node:test';
import assert from 'node:assert/strict';
import { canRetryFailedOrders, coldBatchCapabilities, coldImportDiagnostic, extractJobId, incompleteBackupLabel, isTerminalJob, jobProgress, normalizePage, replaceJobById, storageState, supplementTaskState, taskFailureSummary } from '../src/taskCenter.js';
import { statusTone } from '../src/components/StatusBadge.js';

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
});

test('degraded backup remains separate from strict retry and exposes completeness label', () => {
  assert.equal(canRetryFailedOrders({ jobType: 'ORDER_BACKUP', state: 'PARTIAL_FAILED', orderFailed: 1 }), true);
  assert.equal(canRetryFailedOrders({ jobType: 'DEGRADED_BACKUP', state: 'FAILED', orderFailed: 1 }), false);
  assert.equal(incompleteBackupLabel({ backupCompleteness: 'INCOMPLETE', relationGapCount: 2 }), '文件可能不完整 · 缺失关系 2');
  assert.equal(incompleteBackupLabel({ backupCompleteness: 'COMPLETE' }), '');
});
