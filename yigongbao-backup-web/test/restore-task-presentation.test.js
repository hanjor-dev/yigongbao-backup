import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const readComponent = name => fs.readFileSync(path.join(root, 'src', 'components', 'restores', name), 'utf8');

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
