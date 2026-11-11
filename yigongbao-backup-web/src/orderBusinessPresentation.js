const PHASE_LABELS = {
  10: '订单阶段', 20: '设计阶段', 30: '打印阶段', 40: '后处理阶段',
  50: '质检阶段', 60: '仓储阶段', 70: '确认阶段', 80: '已完成',
};

const STATUS_LABELS = {
  1010: '草稿', 1020: '数据待审核', 1030: '数据审核通过', 1040: '数据审核不通过',
  2010: '待设计', 2020: '设计中', 2030: '设计完成',
  3010: '待打印', 3020: '打印中', 3030: '打印完成', 3040: '打印失败',
  4010: '后处理中',
  5010: '质检中', 5020: '质检合格', 5030: '质检不合格', 5040: '返工', 5050: '包装中',
  6010: '待入库', 6020: '已入库', 6030: '已出库',
  8010: '已完成', 9010: '已取消',
};

const BUSINESS_TYPE_LABELS = { '11.1': '业务', '11.2': '测试', '11.3': '试用', '11.4': '代理' };
const ORDER_TYPE_LABELS = { 1: '医疗器械', 2: '非医疗器械' };
const DELIVERY_LABELS = { 0: '不需要实体交付', 1: '需要实体交付', 2: '异地打印' };
const GENDER_LABELS = { '12.1': '男', '12.2': '女' };
const AUDIT_LABELS = { 0: '待审核', 1: '已通过', 2: '已驳回' };

const hasValue = value => value !== null && value !== undefined
  && !(typeof value === 'string' && value.trim() === '');

const parseSnapshot = value => {
  if (!value) return {};
  if (typeof value === 'object' && !Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch (_) {
    return {};
  }
};

const formatRawJson = value => {
  if (!hasValue(value)) return '';
  if (typeof value === 'string') {
    const text = value.trim();
    if (!text) return '';
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch (_) {
      return text;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (_) {
    return String(value);
  }
};

const translated = (snapshot, nameKey, rawKey, labels) => {
  if (hasValue(snapshot[nameKey])) return String(snapshot[nameKey]).trim();
  if (!hasValue(snapshot[rawKey])) return '';
  return labels[String(snapshot[rawKey])] || '未识别';
};

const flag = (value, positive, negative) => {
  if (!hasValue(value)) return '';
  if (value === true || String(value) === '1') return positive;
  if (value === false || String(value) === '0') return negative;
  return '未识别';
};

const formatTime = value => {
  if (!hasValue(value)) return '';
  const text = String(value).trim();
  const localIso = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/);
  if (localIso) return `${localIso[1]} ${localIso[2]}`;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return text;
  const pad = part => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

const formatMoney = value => {
  if (!hasValue(value)) return '';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '';
  return `¥${new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)}`;
};

const item = (label, value, options = {}) => {
  if (!hasValue(value)) return null;
  return { label, value: String(value), ...options };
};

const compact = values => values.filter(Boolean);

const buildSections = snapshot => {
  const sections = [
    {
      key: 'overview',
      title: '订单概况',
      items: compact([
        item('订单号', snapshot.orderCode),
        item('虚拟单号', snapshot.publicOrderCode),
        item('当前阶段', translated(snapshot, 'phaseName', 'phase', PHASE_LABELS), { kind: 'tag' }),
        item('业务状态', translated(snapshot, 'statusName', 'status', STATUS_LABELS), { kind: 'tag' }),
        item('业务类型', translated(snapshot, 'businessTypeName', 'businessType', BUSINESS_TYPE_LABELS)),
        item('订单类型', translated(snapshot, 'orderTypeName', 'orderType', ORDER_TYPE_LABELS)),
        item('实体交付', translated(snapshot, 'needsPhysicalDeliveryName', 'needsPhysicalDelivery', DELIVERY_LABELS)),
        item('加急状态', flag(snapshot.isUrgent, '加急', '普通'), { kind: 'tag' }),
        item('配送方式', flag(snapshot.isPostal, '邮寄', '不邮寄')),
        item('经典案例', flag(snapshot.isClassicCase ?? snapshot.classicCase, '是', '否')),
        item('创建时间', formatTime(snapshot.createTime)),
      ]),
    },
    {
      key: 'medical',
      title: '患者与医疗信息',
      items: compact([
        item('患者姓名', snapshot.patientName),
        item('患者年龄', hasValue(snapshot.patientAge) ? `${snapshot.patientAge}岁` : ''),
        item('患者性别', translated(snapshot, 'patientGenderName', 'patientGender', GENDER_LABELS)),
        item('医院', snapshot.hospitalName),
        item('科室', snapshot.hospitalDeptName),
        item('所在地区', snapshot.fullAreaName || snapshot.areaName),
        item('医生姓名', snapshot.doctorName),
        item('医生电话', snapshot.doctorPhone),
      ]),
    },
    {
      key: 'submission',
      title: '提单与负责人',
      items: compact([
        item('提单机构', snapshot.orgName),
        item('提单部门', snapshot.operatorDeptName),
        item('提单人', snapshot.operatorName),
        item('提单人电话', snapshot.operatorPhone),
        item('设计师', snapshot.designerName),
      ]),
    },
    {
      key: 'delivery',
      title: '交付与费用',
      items: compact([
        item('期望交付时间', formatTime(snapshot.expectedDeliveryDate)),
        item('邮寄地址', String(snapshot.isPostal) === '1' ? snapshot.postalAddress : ''),
        item('预估费用', formatMoney(snapshot.estimatedCost)),
      ]),
    },
    {
      key: 'timeline',
      title: '流程时间',
      items: [
        item('开始设计时间', formatTime(snapshot.designStartTime) || '-'),
        item('设计提交时间', formatTime(snapshot.designSubmitTime) || '-'),
        item('生产开始时间', formatTime(snapshot.productionStartTime) || '-'),
        item('生产结束时间', formatTime(snapshot.productionEndTime) || '-'),
        item('实际完成时间', formatTime(snapshot.actualCompleteTime) || '-'),
      ],
    },
    {
      key: 'notes',
      title: '业务说明',
      items: compact([
        item('影像评估意见', snapshot.dataEvaluationOpinion, { full: true }),
        item('设计师备注', snapshot.designerRemark, { full: true }),
      ]),
    },
  ];
  return sections.filter(section => section.items.length > 0);
};

const buildProjects = snapshot => {
  if (!Array.isArray(snapshot.rebuildProjectList)) return [];
  return snapshot.rebuildProjectList
    .filter(project => project && typeof project === 'object')
    .map((project, index) => ({
      title: compact([project.bodyPartName, project.projectName]).join(' · ') || `重建项目 ${index + 1}`,
      items: compact([
        item('项目分类', project.categoryName),
        item('数量', project.count),
        item('项目说明', project.projectDesc, { full: true }),
        item('成形要求', project.formingRequirement, { full: true }),
        item('其他要求', project.otherRequirement, { full: true }),
      ]),
    }))
    .filter(project => project.items.length > 0);
};

const buildAudit = (title, audit) => {
  if (!audit || typeof audit !== 'object' || Array.isArray(audit)) return null;
  const items = compact([
    item('审核结果', hasValue(audit.statusDesc)
      ? audit.statusDesc
      : (hasValue(audit.status) ? AUDIT_LABELS[String(audit.status)] || '未识别' : ''), { kind: 'tag' }),
    item('审核人', audit.auditorName),
    item('审核时间', formatTime(audit.auditTime)),
    item('审核备注', audit.remark, { full: true }),
  ]);
  return items.length ? { title, items } : null;
};

const buildLegacyAudit = (snapshot, prefix) => ({
  status: snapshot[`${prefix}Status`],
  auditTime: snapshot[`${prefix}Time`],
  remark: snapshot[`${prefix}Remark`],
});

export function buildOrderBusinessPresentation(value) {
  const snapshot = parseSnapshot(value);
  const sections = buildSections(snapshot);
  const rebuildProjects = buildProjects(snapshot);
  const audits = compact([
    buildAudit('设计审核', snapshot.designAudit)
      || buildAudit('设计审核', buildLegacyAudit(snapshot, 'designAudit')),
    buildAudit('历史区域审核', snapshot.regionalAudit)
      || buildAudit('历史区域审核', buildLegacyAudit(snapshot, 'regionalAudit')),
  ]);
  return {
    sections,
    rebuildProjects,
    audits,
    rawJson: formatRawJson(value),
    hasContent: sections.length > 0 || rebuildProjects.length > 0 || audits.length > 0,
  };
}
