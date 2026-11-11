import test from 'node:test';
import assert from 'node:assert/strict';
import * as taskCenter from '../src/taskCenter.js';

test('订单统计按成功、失败、待处理和总数生成独立展示行', () => {
  assert.equal(typeof taskCenter.jobOrderSummaryRows, 'function');
  assert.deepEqual(taskCenter.jobOrderSummaryRows?.({
    orderCompleted: 10,
    orderFailed: 2,
    orderTotal: 15,
  }), [
    { text: '成功10', tone: 'success' },
    { text: '失败2', tone: 'danger' },
    { text: '待处理3', tone: 'default' },
    { text: '共15', tone: 'default' },
  ]);
});

test('文件统计为普通任务和模拟任务生成各自的独立展示行', () => {
  assert.equal(typeof taskCenter.jobFileSummaryRows, 'function');
  assert.deepEqual(taskCenter.jobFileSummaryRows?.({
    executionMode: 'NORMAL',
    fileCompleted: 259,
    fileFailed: 1,
    fileTotal: 260,
  }), [
    { text: '成功259', tone: 'success' },
    { text: '失败1', tone: 'danger' },
    { text: '共260', tone: 'default' },
  ]);
  assert.deepEqual(taskCenter.jobFileSummaryRows?.({
    executionMode: 'METADATA_SIMULATION',
    manifestSucceeded: 8,
    manifestFailed: 2,
    expectedFileTotal: 36,
  }), [
    { text: 'Manifest成功8', tone: 'success' },
    { text: 'Manifest失败2', tone: 'danger' },
    { text: '预计文件36', tone: 'default' },
  ]);
});

test('没有文件清单时保留明确的单行空状态', () => {
  assert.equal(typeof taskCenter.jobFileSummaryRows, 'function');
  assert.deepEqual(taskCenter.jobFileSummaryRows?.({ fileTotal: 0 }), [
    { text: '未获取文件清单', tone: 'muted' },
  ]);
});

test('容量统计按源文件和 ZIP 生成两条独立展示行', () => {
  assert.equal(typeof taskCenter.jobCapacitySummaryRows, 'function');
  assert.deepEqual(taskCenter.jobCapacitySummaryRows?.({
    expectedBytes: 552547123,
    packageBytes: 284300411,
  }), ['源文件 526.95 MB', 'ZIP 271.13 MB']);
});

test('订单明细时间以秒级本地格式展示', () => {
  assert.equal(typeof taskCenter.formatJobOrderCreatedAt, 'function');
  assert.equal(taskCenter.formatJobOrderCreatedAt?.('2026-07-07T22:04:35.987'), '2026-07-07 22:04:35');
  assert.equal(taskCenter.formatJobOrderCreatedAt?.(null), '-');
});

test('订单明细患者姓名优先读取业务快照并兼容平铺字段', () => {
  assert.equal(typeof taskCenter.jobOrderPatientName, 'function');
  assert.equal(taskCenter.jobOrderPatientName?.({
    patientName: '旧姓名',
    orderBusinessJson: '{"patientName":"张三"}',
  }), '张三');
  assert.equal(taskCenter.jobOrderPatientName?.({ patientName: '李四' }), '李四');
  assert.equal(taskCenter.jobOrderPatientName?.({}), '-');
});
