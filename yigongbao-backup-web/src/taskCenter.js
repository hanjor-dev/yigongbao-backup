import { statusLabel } from './components/StatusBadge.js';
export { buildOrderBusinessPresentation } from './orderBusinessPresentation.js';

export function extractJobId(result) {
  const data = result?.data ?? result;
  return data?.jobId || data?.job?.id || data?.task?.jobId || data?.id || null;
}

export function jobProgress(job) {
  if (!job) return 0;
  const orderCompleted = Number(job.orderCompleted ?? 0);
  const orderFailed = Number(job.orderFailed ?? 0);
  const orderSkipped = Number(job.orderSkipped ?? 0);
  const orderTotal = Number(job.orderTotal ?? 0);
  if (orderTotal > 0) return Math.min(100, Math.max(0, Math.round(((orderCompleted + orderFailed + orderSkipped) / orderTotal) * 100)));
  const completed = Number(job.fileCompleted ?? job.completed ?? 0);
  const total = Number(job.fileTotal ?? job.total ?? 0);
  if (total > 0) return Math.min(100, Math.max(0, Math.round((completed / total) * 100)));
  return 0;
}

const jobCount = value => Number(value ?? 0) || 0;

const jobBytes = value => {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1048576) return `${(size / 1024).toFixed(2)} KB`;
  if (size < 1073741824) return `${(size / 1048576).toFixed(2)} MB`;
  return `${(size / 1073741824).toFixed(2)} GB`;
};

/** 为任务列表生成逐行订单统计，避免紧凑列中使用斜杠拼接。 */
export function jobOrderSummaryRows(job) {
  const completed = jobCount(job?.orderCompleted);
  const failed = jobCount(job?.orderFailed);
  const total = jobCount(job?.orderTotal);
  return [
    { text: `成功${completed}`, tone: 'success' },
    { text: `失败${failed}`, tone: 'danger' },
    { text: `待处理${Math.max(0, total - completed - failed)}`, tone: 'default' },
    { text: `共${total}`, tone: 'default' },
  ];
}

/** 为任务列表生成逐行文件统计，并保留元数据模拟任务的 Manifest 口径。 */
export function jobFileSummaryRows(job) {
  if (String(job?.executionMode || '').toUpperCase() === 'METADATA_SIMULATION') {
    return [
      { text: `Manifest成功${jobCount(job?.manifestSucceeded)}`, tone: 'success' },
      { text: `Manifest失败${jobCount(job?.manifestFailed)}`, tone: 'danger' },
      { text: `预计文件${jobCount(job?.expectedFileTotal)}`, tone: 'default' },
    ];
  }
  const total = jobCount(job?.fileTotal);
  if (total <= 0) return [{ text: '未获取文件清单', tone: 'muted' }];
  return [
    { text: `成功${jobCount(job?.fileCompleted)}`, tone: 'success' },
    { text: `失败${jobCount(job?.fileFailed)}`, tone: 'danger' },
    { text: `共${total}`, tone: 'default' },
  ];
}

/** 为任务列表生成源文件与 ZIP 两行容量摘要。 */
export function jobCapacitySummaryRows(job) {
  return [
    `源文件 ${jobBytes(job?.expectedBytes)}`,
    `ZIP ${jobBytes(job?.packageBytes)}`,
  ];
}

/** 将数据库中的完整订单业务 JSON 转成可安全迭代的键值对象。 */
export function orderBusinessFields(value) {
  if (!value) return {};
  if (typeof value === 'object' && !Array.isArray(value)) return value;
  try { const parsed = JSON.parse(value); return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}; }
  catch (_) { return {}; }
}

/** 任务订单明细使用稳定的秒级时间格式，去除 ISO 分隔符和多余毫秒。 */
export function formatJobOrderCreatedAt(value) {
  if (!value) return '-';
  const text = String(value).trim();
  const localIso = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/);
  if (localIso) return `${localIso[1]} ${localIso[2]}`;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return text || '-';
  const pad = part => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 任务订单明细优先展示业务快照中的患者姓名，并兼容旧的平铺字段。 */
export function jobOrderPatientName(row) {
  return orderBusinessFields(row?.orderBusinessJson).patientName || row?.patientName || '-';
}

export function isTerminalJob(job) {
  return ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'].includes(String(job?.state || job?.status || '').toUpperCase());
}

/** 严格失败重试只适用于正常备份任务；降级备份必须始终从订单行显式创建。 */
export function canRetryFailedOrders(job) {
  const type = String(job?.jobType || '').toUpperCase();
  const state = String(job?.state || job?.status || '').toUpperCase();
  return ['ORDER_BACKUP', 'MANUAL_BACKUP'].includes(type)
    && ['FAILED', 'PARTIAL_FAILED'].includes(state)
    && Number(job?.orderFailed || 0) > 0;
}

/** 订单列表和详情共用的不完整备份说明。 */
export function incompleteBackupLabel(order) {
  if (String(order?.backupCompleteness || '').toUpperCase() !== 'INCOMPLETE') return '';
  return `文件可能不完整 · 缺失关系 ${Number(order?.relationGapCount || 0)}`;
}

/** 将详情轮询结果同步回列表，避免取消等终态只在详情中更新。 */
export function replaceJobById(items, refreshed) {
  if (!Array.isArray(items) || !refreshed?.id) return Array.isArray(items) ? items : [];
  const index = items.findIndex(item => String(item?.id) === String(refreshed.id));
  if (index < 0) return items;
  return items.map((item, current) => current === index ? refreshed : item);
}

/** 将浏览器上传阶段和服务器后台任务阶段统一为可展示状态。 */
export function supplementTaskState(job, uploadPercent = 100) {
  const percent = Math.min(100, Math.max(0, Number(uploadPercent) || 0));
  if (!job || percent < 100) return { status: 'UPLOADING', percent, message: '正在上传文件' };
  const state = String(job.state || job.status || '').toUpperCase();
  if (state === 'SUCCEEDED') return { status: 'SUCCEEDED', percent: 100, message: '补传完成' };
  if (['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'].includes(state)) {
    return { status: state, percent: 100, message: taskFailureSummary(job) || '补传任务失败' };
  }
  return { status: 'PROCESSING', percent: jobProgress(job), message: '文件已上传，后台正在处理' };
}


/** 将后端分页响应归一化，避免前端因空响应或字段缺失而丢失分页状态。 */
export function normalizePage(value) {
  const page = value && typeof value === 'object' ? value : {};
  const records = Array.isArray(page.records) ? page.records : [];
  const current = Number.isFinite(Number(page.current)) && Number(page.current) > 0 ? Number(page.current) : 1;
  const size = Number.isFinite(Number(page.size)) && Number(page.size) > 0 ? Number(page.size) : 20;
  const total = Number.isFinite(Number(page.total)) && Number(page.total) >= 0 ? Number(page.total) : 0;
  const pages = Number.isFinite(Number(page.pages)) && Number(page.pages) >= 0
    ? Number(page.pages)
    : (total > 0 ? Math.ceil(total / size) : 0);
  return { records, current, size, total, pages };
}

/**
 * 订单表横向溢出且原生横向滚动条仍在视口下方时，显示屏幕底部的同步滚动条。
 * 原生滚动条进入视口后立即让位，避免同一位置出现两条滚动条。
 */
export function stickyTableScrollbarState({
  scrollWidth = 0,
  clientWidth = 0,
  tableTop = 0,
  tableBottom = 0,
  viewportHeight = 0,
} = {}) {
  const normalizedScrollWidth = Math.max(0, Number(scrollWidth) || 0);
  const normalizedClientWidth = Math.max(0, Number(clientWidth) || 0);
  const hasHorizontalOverflow = normalizedScrollWidth > normalizedClientWidth + 1;
  const tableIntersectsViewport = Number(tableTop) < Number(viewportHeight) && Number(tableBottom) > 0;
  const nativeScrollbarBelowViewport = Number(tableBottom) > Number(viewportHeight) + 1;
  return {
    visible: hasHorizontalOverflow && tableIntersectsViewport && nativeScrollbarBelowViewport,
    trackWidth: Math.max(normalizedScrollWidth, normalizedClientWidth),
  };
}

/** 计算订单热备是否可下载，并给出冷备缺失时的运维提示。 */
export function storageState(order) {
  const location = order?.storageLocation;
  if (location) return location.accessible
    ? { canDownload: true, message: '' }
    : { canDownload: false, message: unavailableStorageMessage(location) };
  if (typeof order?.storageAccessible === 'boolean') return order.storageAccessible
    ? { canDownload: true, message: '' }
    : { canDownload: false, message: unavailableStorageMessage(order) };
  const hot = String(order?.hotRollup || '').toUpperCase();
  const cold = String(order?.coldRollup || '').toUpperCase();
  const canDownload = ['PRESENT', 'AVAILABLE', 'EXISTS'].includes(hot);
  if (canDownload) return { canDownload: true, message: '' };
  if (['PRESENT', 'AVAILABLE', 'EXISTS'].includes(cold)) {
    return { canDownload: false, message: '文件位于离线冷备，请联系管理员调取' };
  }
  return { canDownload: false, message: hot === 'UNKNOWN' ? '热备状态未知，请联系管理员检查存储' : '热备文件不可用，请先完成冷备回迁' };
}

/** 离线冷备的线下查找摘要；字段同时兼容订单平铺数据与 package-location DTO。 */
export function offlineStorageSummary(value) {
  return [
    value?.storageOfflineLabelNote ?? value?.offlineLabelNote,
    value?.storagePhysicalLocation ?? value?.physicalLocation,
    value?.storagePeriodLabel ?? value?.periodLabel,
  ].filter(Boolean).join(' · ');
}

/** 挂载状态与文件可读性是两个维度；在线但损坏/占用的副本不能伪装成 OFFLINE。 */
export function storageMountState(value) {
  return value?.storageMountStatus ?? value?.mountStatus
    ?? (value?.storageAccessible === false || value?.accessible === false ? 'UNAVAILABLE' : value?.backupStatus || 'UNKNOWN');
}

function unavailableStorageMessage(value) {
  const reason = value?.storageUnavailableReason ?? value?.unavailableReason
    ?? `订单包所在${value?.storageTier ? statusLabel(value.storageTier) : ''}卷未挂载`;
  if (String(value?.storageTier || '').toUpperCase() !== 'COLD') return reason;
  const lookup = offlineStorageSummary(value);
  return [reason, lookup, '请插入对应冷备盘并由管理员确认挂载'].filter(Boolean).join('；');
}

/** 同时表达当前卷事实和任务发生时审计快照，避免历史 HOT 覆盖当前 COLD/OFFLINE。 */
export function storageVolumeView(row, fallbackId = null) {
  const blockedBeforeTargetAssignment = String(row?.failureCode || '').toUpperCase() === 'STORAGE_VOLUME_UNAVAILABLE'
    && !fallbackId && !row?.storageVolumeId && !row?.storageVolumeName
    && !row?.currentStorageVolumeName;
  if (blockedBeforeTargetAssignment) {
    return {
      name: '未分配',
      current: '创建任务时没有可用的热备存储卷',
      historical: '',
    };
  }
  const historicalParts = [row?.storageVolumeName, row?.storageTier ? statusLabel(row.storageTier) : null].filter(Boolean);
  if (row?.currentStorageResolved === false) {
    const resolvedName = row?.currentStorageVolumeName;
    const resolvedState = [row?.currentStorageTier, row?.currentMountStatus, row?.currentOperationStatus]
      .map(value => value ? statusLabel(value) : value)
      .filter(Boolean).join(' / ');
    const unavailableReason = row?.currentStorageUnavailableReason || '存储卷台账不存在或已删除';
    if (resolvedName) {
      return {
        name: `${resolvedName}（部分台账不可用）`,
        current: [resolvedState, unavailableReason].filter(Boolean).join('；'),
        historical: historicalParts.length ? `任务时：${historicalParts.join(' / ')}` : '',
      };
    }
    const reference = fallbackId || row?.storageVolumeId;
    const label = reference == null || reference === ''
      ? '当前卷台账不可用'
      : `${String(reference).startsWith('卷 #') ? reference : `卷 #${reference}`}（当前台账不可用）`;
    return {
      name: label,
      current: unavailableReason,
      historical: historicalParts.length ? `任务时：${historicalParts.join(' / ')}` : '',
    };
  }
  const hasCurrentProjection = row?.currentStorageResolved === true
    || [row?.currentStorageVolumeName, row?.currentStorageTier, row?.currentMountStatus, row?.currentOperationStatus]
      .some(value => value != null && value !== '');
  if (!hasCurrentProjection) {
    return {
      name: '当前卷状态未知',
      current: historicalParts.length || row?.storageVolumeId || fallbackId
        ? '历史记录未保存存储卷 ID，无法解析当前卷'
        : '任务未记录可解析的存储卷信息',
      historical: historicalParts.length ? `任务时：${historicalParts.join(' / ')}` : '',
    };
  }
  const currentName = row?.currentStorageVolumeName || row?.storageVolumeId
    || fallbackId || '当前卷状态未知';
  const current = [row?.currentStorageTier, row?.currentMountStatus, row?.currentOperationStatus]
    .map(value => value ? statusLabel(value) : value)
    .filter(Boolean).join(' / ');
  return {
    name: String(currentName),
    current,
    historical: historicalParts.length ? `任务时：${historicalParts.join(' / ')}` : '',
  };
}

/** 汇总任务级与结构化结果中的失败原因，供列表和详情统一展示。 */
export function taskFailureSummary(job) {
  if (!job) return '';
  let message = job.failureEvidence || job.failureMessage || '';
  if (!message && job.resultJson) {
    try {
      const result = typeof job.resultJson === 'string' ? JSON.parse(job.resultJson) : job.resultJson;
      message = result?.failureMessage || result?.message || result?.reason || '';
    } catch (_) {
      message = '';
    }
  }
  const code = job.failureCode || '';
  return code && message ? `${code}：${message}` : code || message;
}

/** 冷备批次的管理能力矩阵，避免页面只依赖按钮显隐而误操作。 */
export function coldBatchCapabilities(batch) {
  const status = String(batch?.status || '').toUpperCase();
  const cleanupStatus = String(batch?.hotCleanupStatus || '').toUpperCase();
  return {
    canVerify: status === 'COMPLETED',
    canCleanup: status === 'VERIFIED' && cleanupStatus !== 'CLEANED',
    isCleaned: cleanupStatus === 'CLEANED',
    status
  };
}

/** 将冷备导入区扫描结果转换为可操作的诊断提示。 */
export function coldImportDiagnostic(archives) {
  const count = Array.isArray(archives) ? archives.length : 0;
  return count > 0
    ? `发现 ${count} 个冷备压缩包。请选择压缩包后按订单号筛选恢复；留空表示恢复该包全部订单。`
    : '导入区未发现 .zip 文件。请运维人员先将冷备硬盘中的批次压缩包手动复制到配置的导入区，再重新扫描。';
}
