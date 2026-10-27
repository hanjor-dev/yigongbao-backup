export function extractJobId(result) {
  const data = result?.data ?? result;
  return data?.jobId || data?.job?.id || data?.task?.jobId || data?.id || null;
}

export function jobProgress(job) {
  if (!job) return 0;
  const completed = Number(job.fileCompleted ?? job.completed ?? 0);
  const total = Number(job.fileTotal ?? job.total ?? 0);
  if (total > 0) return Math.min(100, Math.max(0, Math.round((completed / total) * 100)));
  const orderCompleted = Number(job.orderCompleted ?? 0);
  const orderTotal = Number(job.orderTotal ?? 0);
  return orderTotal > 0 ? Math.min(100, Math.max(0, Math.round((orderCompleted / orderTotal) * 100))) : 0;
}

export function isTerminalJob(job) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(String(job?.state || job?.status || '').toUpperCase());
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

/** 计算订单热备是否可下载，并给出冷备缺失时的运维提示。 */
export function storageState(order) {
  const hot = String(order?.hotRollup || '').toUpperCase();
  const cold = String(order?.coldRollup || '').toUpperCase();
  const canDownload = ['PRESENT', 'AVAILABLE', 'EXISTS'].includes(hot);
  if (canDownload) return { canDownload: true, message: '' };
  if (['PRESENT', 'AVAILABLE', 'EXISTS'].includes(cold)) {
    return { canDownload: false, message: '文件位于离线冷备，请联系管理员调取' };
  }
  return { canDownload: false, message: hot === 'UNKNOWN' ? '热备状态未知，请联系管理员检查存储' : '热备文件不可用，请先完成冷备回迁' };
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
