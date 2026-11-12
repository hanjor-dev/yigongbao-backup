import { statusTone } from './components/StatusBadge.js';

const HEALTH = Object.freeze({
  CRITICAL: { label: '需要立即处理', tone: 'danger' },
  WARNING: { label: '存在待处理项', tone: 'warning' },
  DEGRADED: { label: '部分数据不可用', tone: 'warning' },
  SETUP_REQUIRED: { label: '待完成存储初始化', tone: 'warning' },
  NORMAL: { label: '运行正常', tone: 'success' },
  UNKNOWN: { label: '状态未知', tone: 'info' }
});

export function overviewAlertDismissUrl(alertKey) {
  const key = String(alertKey || '').trim();
  return key ? `/admin/overview/alerts/${encodeURIComponent(key)}/dismiss` : null;
}

export function formatCount(value) {
  return value == null || Number.isNaN(Number(value)) ? '—' : Number(value).toLocaleString('zh-CN');
}

export function formatBytes(value) {
  if (value == null || Number.isNaN(Number(value))) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let index = 0; let scaled = Number(value);
  while (Math.abs(scaled) >= 1024 && index < units.length - 1) { scaled /= 1024; index += 1; }
  return `${scaled.toLocaleString('zh-CN', { maximumFractionDigits: index ? 2 : 0 })} ${units[index]}`;
}

export function formatPercent(value) {
  return value == null || Number.isNaN(Number(value)) ? '—' : `${Number(value).toFixed(1)}%`;
}

export function formatTime(value) {
  if (!value) return '—';
  const text = String(value);
  const explicit = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text) && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(text)
    ? `${text}+08:00` : text;
  const date = new Date(explicit);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', {
    hour12: false, timeZone: 'Asia/Shanghai'
  });
}

export function executionModeLabel(value) {
  const labels = { REAL: '正式备份', SIMULATION: '模拟备份', DEGRADED: '降级备份' };
  const key = String(value || '').toUpperCase();
  return labels[key] || (value ? String(value) : '—');
}

export function formatInterval(value, unit) {
  if (value == null || !unit) return '—';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '—';
  const normalized = String(unit).toUpperCase();
  if (amount === 1 && ['DAY', 'DAYS'].includes(normalized)) return '每天';
  if (amount === 1 && ['HOUR', 'HOURS'].includes(normalized)) return '每小时';
  const units = { MINUTE: '分钟', MINUTES: '分钟', HOUR: '小时', HOURS: '小时', DAY: '天', DAYS: '天' };
  return units[normalized] ? `每 ${amount} ${units[normalized]}` : `每 ${amount} ${unit}`;
}

export function remainingCapacity(totalBytes, usedBytes) {
  if (totalBytes == null || usedBytes == null) return null;
  const total = Number(totalBytes); const used = Number(usedBytes);
  if (!Number.isFinite(total) || !Number.isFinite(used)) return null;
  return Math.max(0, total - used);
}

const AUTOMATION_DEFINITIONS = Object.freeze([
  { key:'backup', label:'自动备份', note:'错过计划时间不补跑' },
  { key:'accountSync', label:'账号同步', note:'按计划拉取线上账号' },
  { key:'cleanup', label:'云端清理', note:'受许可与保留策略保护' }
]);

export function automationRows(value) {
  const runStates = new Set(['CREATED','RUNNING','PROCESSING','RETRYING','RETRY_PENDING','SUCCESS','SUCCEEDED','COMPLETED','SIMULATED','FAILED','PARTIAL_FAILED','CANCELLED','CANCELED','ERROR']);
  const successStates = new Set(['SUCCESS','SUCCEEDED','COMPLETED','SIMULATED']);
  return AUTOMATION_DEFINITIONS.map(definition => {
    const item = value?.[definition.key] || {};
    const rawState = String(item.state || 'UNKNOWN').toUpperCase();
    const inferredRun = item.lastError ? 'FAILED' : item.lastSuccessAt ? 'SUCCEEDED' : runStates.has(rawState) ? rawState : 'NOT_RUN';
    const lastRunStatus = String(item.lastRunStatus || inferredRun).toUpperCase();
    let state = item.enabled === false ? 'DISABLED' : rawState;
    if (item.enabled !== false && successStates.has(state)) state = 'NORMAL';
    if (item.enabled !== false && (state === 'READY' || state === 'IDLE')) state = successStates.has(lastRunStatus) ? 'NORMAL' : 'WAITING';
    return { ...definition, ...item, state, lastRunStatus };
  });
}

export function healthPresentation(level) {
  return HEALTH[String(level || 'UNKNOWN').toUpperCase()] || HEALTH.UNKNOWN;
}

export function orderedAlerts(alerts) {
  const rank = { CRITICAL: 0, WARNING: 1, INFO: 2 };
  return [...(Array.isArray(alerts) ? alerts : [])].sort((a, b) =>
    (rank[a?.severity] ?? 9) - (rank[b?.severity] ?? 9)
    || String(b?.evidenceAt || '').localeCompare(String(a?.evidenceAt || '')));
}

export function dateKeyOffset(value, offset) {
  const match = String(value || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
  const base = match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
    : Date.UTC(value.getFullYear(), value.getMonth(), value.getDate());
  return new Date(base + Number(offset || 0) * 86400000).toISOString().slice(0, 10);
}

const TREND_PERIODS = Object.freeze({
  WEEK: { label:'本周', title:'本周备份走势' },
  MONTH: { label:'本月', title:'本月备份走势' },
  YEAR: { label:'本年', title:'本年备份走势' }
});

export function trendPeriodPresentation(value) {
  return TREND_PERIODS[String(value || 'WEEK').toUpperCase()] || TREND_PERIODS.WEEK;
}

export function overviewTrendUrl(value) {
  const period = TREND_PERIODS[String(value || '').toUpperCase()] ? String(value).toUpperCase() : 'WEEK';
  return `/admin/overview/trend?period=${period}`;
}

export function trendPointEnd(value, period) {
  const match = String(value || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!match) return dateKeyOffset(value, 1);
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (String(period || '').toUpperCase() === 'YEAR') date.setUTCMonth(date.getUTCMonth() + 1);
  else date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

export function trendTickLabel(value, period, index, length) {
  const text = String(value || '');
  if (String(period || '').toUpperCase() === 'YEAR') return `${Number(text.slice(5, 7)) || ''}月`;
  if (String(period || '').toUpperCase() !== 'MONTH') return text.slice(5);
  const day = Number(text.slice(8, 10));
  return index === 0 || index === length - 1 || day % 5 === 0 ? text.slice(5) : '';
}

export function elementTagType(value) {
  const tones = {
    success: 'success', progress: 'primary', info: 'primary', cold: 'primary',
    warning: 'warning', warm: 'warning', danger: 'danger', neutral: 'info'
  };
  return tones[statusTone(value)] || 'info';
}

export function elementResultIcon(tone) {
  return tone === 'danger' ? 'error' : ['success', 'warning', 'info', 'error'].includes(tone) ? tone : 'info';
}

export function overviewTrendChartOption(points, period) {
  const rows = Array.isArray(points) ? points : [];
  const selected = String(period || 'WEEK').toUpperCase();
  const labels = rows.map((item, index) => selected === 'YEAR'
    ? trendTickLabel(item.date, selected, index, rows.length)
    : String(item.date || '').slice(5));
  const axisLabelFormatter = (value, index) => selected === 'MONTH'
    ? trendTickLabel(rows[index]?.date, selected, index, rows.length)
    : value;
  const tooltipFormatter = params => {
    const items = Array.isArray(params) ? params : [params];
    const title = items[0]?.axisValueLabel || items[0]?.axisValue || '';
    return [title, ...items.map(item => {
      const value = item.seriesName === '新增订单包容量' ? formatBytes(item.value) : formatCount(item.value);
      return `${item.marker || ''}${item.seriesName}：${value}`;
    })].filter(Boolean).join('<br/>');
  };
  const lineSeries = (name, field, color) => ({
    name, type: 'line', xAxisIndex: 0, yAxisIndex: 0, smooth: false,
    showSymbol: rows.length <= 12, symbolSize: 7, data: rows.map(item => Number(item?.[field] || 0)),
    lineStyle: { width: 2, color }, itemStyle: { color }, emphasis: { focus: 'series' }
  });
  return {
    animationDuration: 300,
    color: ['#67c23a', '#e6a23c', '#f56c6c', '#909399', '#409eff'],
    aria: { enabled: true, decal: { show: true }, label: { description: `${trendPeriodPresentation(selected).title}，包含订单结果与新增订单包容量。` } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: tooltipFormatter },
    axisPointer: { link: [{ xAxisIndex: [0, 1] }] },
    legend: { type: 'scroll', top: 0, data: ['完整成功', '降级成功', '失败', '跳过', '新增订单包容量'] },
    title: [
      { text: '订单结果', left: 8, top: 34, textStyle: { fontSize: 14, fontWeight: 600 } },
      { text: '新增订单包容量', left: 8, top: 218, textStyle: { fontSize: 14, fontWeight: 600 } }
    ],
    grid: [
      { left: 52, right: 24, top: 72, height: 112, containLabel: false },
      { left: 52, right: 24, top: 258, height: 96, containLabel: false }
    ],
    xAxis: [0, 1].map(gridIndex => ({
      type: 'category', gridIndex, boundaryGap: gridIndex === 1, data: labels,
      axisLabel: { hideOverlap: true, interval: 0, formatter: axisLabelFormatter },
      axisTick: { alignWithLabel: true }
    })),
    yAxis: [
      { type: 'value', gridIndex: 0, minInterval: 1, min: 0, splitLine: { show: true } },
      { type: 'value', gridIndex: 1, min: 0, axisLabel: { formatter: value => formatBytes(value) }, splitLine: { show: true } }
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], filterMode: 'none', start: 0, end: 100 }],
    series: [
      lineSeries('完整成功', 'completeSucceeded', '#67c23a'),
      lineSeries('降级成功', 'incompleteSucceeded', '#e6a23c'),
      lineSeries('失败', 'failed', '#f56c6c'),
      lineSeries('跳过', 'skipped', '#909399'),
      {
        name: '新增订单包容量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1,
        data: rows.map(item => Number(item?.addedPackageBytes || 0)),
        itemStyle: { color: '#409eff' }, barMaxWidth: rows.length > 12 ? 12 : 34,
        emphasis: { focus: 'series' }
      }
    ]
  };
}

export function normalizeTrend(points, endDate = new Date()) {
  const source = new Map((Array.isArray(points) ? points : []).map(item => [item.date, item]));
  return Array.from({ length: 7 }, (_, index) => {
    const key = dateKeyOffset(endDate, -6 + index);
    return { date: key, completeSucceeded: 0, incompleteSucceeded: 0, failed: 0,
      skipped: 0, addedPackageBytes: 0, unknownPackageCount: 0, ...(source.get(key) || {}) };
  });
}

export function activityPresentation(activity) {
  const types = {
    ORDER_BACKUP: '订单备份', MANUAL_BACKUP: '手动备份', DEGRADED_BACKUP: '降级备份',
    RESTORE: '线上恢复', VOLUME_ARCHIVE: '整盘封存', CLEANUP_DRY_RUN: '清理预览',
    CLEANUP_EXECUTE: '清理执行', AUTO_CLEANUP: '云端清理'
  };
  const type = String(activity?.activityType || 'UNKNOWN').toUpperCase();
  return { ...activity, typeLabel: types[type] || '其他系统活动', interactive: Boolean(types[type] && activity?.actionRoute) };
}

export function overviewDrilldown(item) {
  if (!item || typeof item.actionRoute !== 'string') return null;
  const allowed = new Set(['orders', 'backup', 'restore', 'cold-storage', 'storage-volumes', 'accounts', 'cleanup', 'configs']);
  return allowed.has(item.actionRoute) ? { target: item.actionRoute, query: item.actionQuery || {} } : null;
}
