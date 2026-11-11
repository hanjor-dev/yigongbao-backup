import test from 'node:test';
import assert from 'node:assert/strict';
import * as taskCenter from '../src/taskCenter.js';

const build = value => taskCenter.buildOrderBusinessPresentation(value);
const itemsByLabel = presentation => Object.fromEntries(
  presentation.sections.flatMap(section => section.items).map(item => [item.label, item.value]),
);
const structuredJson = presentation => JSON.stringify({
  sections: presentation.sections,
  rebuildProjects: presentation.rebuildProjects,
  audits: presentation.audits,
});

test('业务快照只输出中文业务字段并隐藏内部ID和技术字段', () => {
  const presentation = build({
    id: 19,
    orderCode: '20260707000009',
    publicOrderCode: 'YG-00009',
    phase: 80,
    phaseName: '已完成',
    status: 8010,
    statusName: '已完成',
    businessType: '11.3',
    businessTypeName: '试用',
    orderType: 2,
    orderTypeName: '非医疗器械',
    needsPhysicalDelivery: 1,
    needsPhysicalDeliveryName: '需要',
    orgId: 562,
    orgName: '市场部试用组',
    patientName: '张三',
    patientAge: 71,
    patientGender: '12.1',
    patientGenderName: '男',
    isUrgent: 1,
    isPostal: 0,
    isClassicCase: 0,
    createTime: '2026-07-07T22:04:35.527+08:00',
    expectedDeliveryDate: '2026-07-10T09:30:00',
    actualCompleteTime: '2026-07-09T16:20:30',
    estimatedCost: '1280',
    version: 0,
    statusColor: { backgroundColor: '#fff' },
  });

  const items = itemsByLabel(presentation);
  assert.equal(items['订单号'], '20260707000009');
  assert.equal(items['当前阶段'], '已完成');
  assert.equal(items['业务状态'], '已完成');
  assert.equal(items['加急状态'], '加急');
  assert.equal(items['配送方式'], '不邮寄');
  assert.equal(items['经典案例'], '否');
  assert.equal(items['患者年龄'], '71岁');
  assert.equal(items['创建时间'], '2026-07-07 22:04:35');
  assert.equal(items['期望交付时间'], '2026-07-10 09:30:00');
  assert.equal(items['实际完成时间'], '2026-07-09 16:20:30');
  assert.equal(items['预估费用'], '¥1,280.00');

  const rendered = structuredJson(presentation);
  for (const hidden of ['orgId', 'statusColor', 'version', '562', '8010', '11.3', '12.1']) {
    assert.doesNotMatch(rendered, new RegExp(hidden.replace('.', '\\.')));
  }
});

test('流程时间固定展示全部时间项并用横线占位空值', () => {
  const presentation = build({
    orderCode: 'ORD-TIMELINE',
    designStartTime: '2026-07-07T10:11:12',
  });
  const timeline = presentation.sections.find(section => section.key === 'timeline');

  assert.deepEqual(timeline, {
    key: 'timeline',
    title: '流程时间',
    items: [
      { label: '开始设计时间', value: '2026-07-07 10:11:12' },
      { label: '设计提交时间', value: '-' },
      { label: '生产开始时间', value: '-' },
      { label: '生产结束时间', value: '-' },
      { label: '实际完成时间', value: '-' },
    ],
  });
});

test('原始JSON保留所有原始字段并格式化为代码文本', () => {
  const source = {
    id: 19,
    orderCode: 'ORD-RAW',
    status: 8010,
    nested: { enabled: 1 },
  };
  const presentation = build(source);

  assert.equal(presentation.rawJson, JSON.stringify(source, null, 2));
  assert.match(presentation.rawJson, /"id": 19/);
  assert.match(presentation.rawJson, /"enabled": 1/);
});

test('旧快照缺少名称字段时转译稳定枚举和字典值', () => {
  const items = itemsByLabel(build({
    phase: 20,
    status: 2020,
    businessType: '11.2',
    orderType: 1,
    needsPhysicalDelivery: 2,
    patientGender: '12.2',
    isUrgent: 0,
    isPostal: 1,
    classicCase: 1,
  }));

  assert.deepEqual(items, {
    当前阶段: '设计阶段',
    业务状态: '设计中',
    业务类型: '测试',
    订单类型: '医疗器械',
    实体交付: '异地打印',
    加急状态: '普通',
    配送方式: '邮寄',
    经典案例: '是',
    患者性别: '女',
    开始设计时间: '-',
    设计提交时间: '-',
    生产开始时间: '-',
    生产结束时间: '-',
    实际完成时间: '-',
  });
});

test('旧快照平铺审核字段转换为中文审核记录并隐藏审核人ID', () => {
  const presentation = build({
    orderCode: 'ORD-LEGACY',
    needsPhysicalDelivery: 1,
    designAuditStatus: 2,
    designAuditBy: 9001,
    designAuditTime: '2026-07-08T08:09:10',
    designAuditRemark: '资料不完整',
  });

  assert.equal(itemsByLabel(presentation)['实体交付'], '需要实体交付');
  assert.deepEqual(presentation.audits[0], {
    title: '设计审核',
    items: [
      { label: '审核结果', value: '已驳回', kind: 'tag' },
      { label: '审核时间', value: '2026-07-08 08:09:10' },
      { label: '审核备注', value: '资料不完整', full: true },
    ],
  });
  assert.doesNotMatch(structuredJson(presentation), /designAuditBy|9001/);
});

test('业务快照过滤空值并在不邮寄时隐藏邮寄地址', () => {
  const presentation = build({
    orderCode: 'ORD-1',
    hospitalName: '',
    patientName: null,
    isPostal: 0,
    postalAddress: '不应展示的历史地址',
    rebuildProjectList: [],
    designAudit: {},
  });

  assert.deepEqual(presentation.sections.map(section => section.title), ['订单概况', '流程时间']);
  assert.equal(itemsByLabel(presentation)['邮寄地址'], undefined);
  assert.deepEqual(presentation.rebuildProjects, []);
  assert.deepEqual(presentation.audits, []);
});

test('重建项目和审核记录转换为结构化中文内容', () => {
  const presentation = build({
    rebuildProjectList: [{
      categoryCode: '13.1',
      categoryName: '骨科',
      bodyPartName: '下颌骨',
      projectName: '重建导板',
      count: 1,
      projectDesc: '术前规划',
      formingRequirement: '树脂打印',
      otherRequirement: '消毒包装',
    }],
    designAudit: {
      status: 2,
      statusDesc: '已驳回',
      auditorId: 99,
      auditorName: '审核员',
      auditTime: '2026-07-08T08:09:10',
      remark: '资料不完整',
    },
  });

  assert.deepEqual(presentation.rebuildProjects[0], {
    title: '下颌骨 · 重建导板',
    items: [
      { label: '项目分类', value: '骨科' },
      { label: '数量', value: '1' },
      { label: '项目说明', value: '术前规划', full: true },
      { label: '成形要求', value: '树脂打印', full: true },
      { label: '其他要求', value: '消毒包装', full: true },
    ],
  });
  assert.deepEqual(presentation.audits[0], {
    title: '设计审核',
    items: [
      { label: '审核结果', value: '已驳回', kind: 'tag' },
      { label: '审核人', value: '审核员' },
      { label: '审核时间', value: '2026-07-08 08:09:10' },
      { label: '审核备注', value: '资料不完整', full: true },
    ],
  });
  assert.doesNotMatch(structuredJson(presentation), /auditorId|99|categoryCode|13\.1/);
});
