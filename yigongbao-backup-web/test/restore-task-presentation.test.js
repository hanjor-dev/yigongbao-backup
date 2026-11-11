import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const readComponent = name => fs.readFileSync(path.join(root, 'src', 'components', 'restores', name), 'utf8');
const readView = name => fs.readFileSync(path.join(root, 'src', 'views', name), 'utf8');

test('线上恢复任务列表将源存储卷信息逐行展示', () => {
  const source = readComponent('RestoreTaskTable.vue');
  assert.match(source, /class="restore-volume-stack"/);
  assert.match(source, /class="restore-volume-name"/);
  assert.match(source, /class="restore-volume-line">当前：/);
  assert.match(source, /class="restore-volume-line">\{\{ volume\(row\)\.historical \}\}/);
});

test('恢复订单明细展示患者并格式化创建时间', () => {
  const source = readComponent('RestoreTaskDetail.vue');
  assert.match(source, /formatJobOrderCreatedAt/);
  assert.match(source, /jobOrderPatientName/);
  assert.match(source, /label="创建时间"[^>]*>\s*<template[^>]*>\{\{ formatJobOrderCreatedAt\(row\.orderCreatedAt\) \}\}/);
  assert.match(source, /label="患者"[^>]*>\s*<template[^>]*>\{\{ jobOrderPatientName\(row\) \}\}/);
  assert.doesNotMatch(source, /<el-table-column label="所属机构"/);
});

test('线上恢复任务详情使用弹窗和任务级多标签而不是页面内联卡片', () => {
  const view = readView('RestoreView.vue');
  const detail = readComponent('RestoreTaskDetail.vue');

  assert.match(view, /<RestoreTaskDetail[^>]*v-model="detailVisible"/);
  assert.match(detail, /<el-dialog/);
  assert.match(detail, /class="job-detail-dialog"/);
  assert.match(detail, /<el-tabs[^>]*detailTab/);
  assert.match(detail, /label="订单明细" name="orders"/);
  assert.match(detail, /label="任务日志" name="logs"/);
  assert.match(detail, /@click="emit\('open-order-detail', row\)"/);
  assert.doesNotMatch(detail, /<el-card v-if="task"/);
  assert.doesNotMatch(detail, /type="expand"/);
});

test('恢复订单详情使用右侧抽屉并按概览文件和日志分标签', () => {
  const drawer = readComponent('RestoreOrderDetailDrawer.vue');

  assert.match(drawer, /<el-drawer/);
  assert.match(drawer, /name="overview"/);
  assert.match(drawer, /name="files"/);
  assert.match(drawer, /name="logs"/);
  assert.match(drawer, /<OrderBusinessFields/);
  assert.match(drawer, /<ExecutionLogPanel/);
});
