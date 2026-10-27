import test from 'node:test';
import assert from 'node:assert/strict';
import { coldBatchCapabilities, coldImportDiagnostic, extractJobId, isTerminalJob, jobProgress, normalizePage, storageState, taskFailureSummary } from '../src/taskCenter.js';
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
});

test('isTerminalJob recognizes terminal states', () => {
  assert.equal(isTerminalJob({ state: 'SUCCEEDED' }), true);
  assert.equal(isTerminalJob({ status: 'SUCCEEDED' }), true);
  assert.equal(isTerminalJob({ state: 'CANCELLED' }), true);
  assert.equal(isTerminalJob({ state: 'RUNNING' }), false);
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
  assert.equal(statusTone('UNKNOWN'), 'neutral');
});
