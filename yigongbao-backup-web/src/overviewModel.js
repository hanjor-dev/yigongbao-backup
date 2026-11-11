const HEALTH = Object.freeze({
  CRITICAL: { label: '需要立即处理', tone: 'danger' },
  WARNING: { label: '存在待处理项', tone: 'warning' },
  DEGRADED: { label: '部分数据不可用', tone: 'warning' },
  SETUP_REQUIRED: { label: '待完成存储初始化', tone: 'warning' },
  NORMAL: { label: '运行正常', tone: 'success' },
  UNKNOWN: { label: '状态未知', tone: 'info' }
});

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
    CLEANUP_EXECUTE: '清理执行', AUTO_CLEANUP: '自动清理'
  };
  const type = String(activity?.activityType || 'UNKNOWN').toUpperCase();
  return { ...activity, typeLabel: types[type] || '其他系统活动', interactive: Boolean(types[type] && activity?.actionRoute) };
}

export function overviewDrilldown(item) {
  if (!item || typeof item.actionRoute !== 'string') return null;
  const allowed = new Set(['orders', 'backup', 'restore', 'cold-storage', 'storage-volumes', 'accounts', 'cleanup', 'configs']);
  return allowed.has(item.actionRoute) ? { target: item.actionRoute, query: item.actionQuery || {} } : null;
}
