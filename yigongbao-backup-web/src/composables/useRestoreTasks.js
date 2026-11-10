import { computed, onUnmounted, ref } from 'vue';
import { api } from '../api.js';
import { isTerminalJob, jobProgress, normalizePage, replaceJobById } from '../taskCenter.js';

/**
 * 线上恢复任务的独立页面状态。
 *
 * 恢复历史不与订单备份任务共享筛选或分页状态，防止管理员误将两类长任务混淆。
 */
export function useRestoreTasks() {
  const tasks = ref([]);
  const current = ref(1);
  const size = ref(20);
  const total = ref(0);
  const selected = ref(null);
  const orders = ref([]);
  const orderCurrent = ref(1);
  const orderSize = ref(20);
  const orderTotal = ref(0);
  const orderFiles = ref({});
  const orderFileLoading = ref({});
  const logDialogVisible = ref(false);
  const logTitle = ref('');
  const logRecords = ref([]);
  const logCurrent = ref(1);
  const logSize = ref(50);
  const logTotal = ref(0);
  const logLoading = ref(false);
  const logScope = ref(null);
  const loading = ref(false);
  const message = ref('');
  let pollTimer = null;

  const selectedProgress = computed(() => jobProgress(selected.value));

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  async function loadTasks(page = current.value) {
    const result = await api(`/admin/restores/jobs?current=${encodeURIComponent(page)}&size=${encodeURIComponent(size.value)}`);
    const data = normalizePage(result.data);
    tasks.value = data.records;
    current.value = data.current;
    size.value = data.size;
    total.value = data.total;
    if (selected.value) {
      const refreshed = tasks.value.find(item => String(item.id) === String(selected.value.id));
      if (refreshed) selected.value = refreshed;
    }
  }

  async function loadDetail(id = selected.value?.id, silent = false) {
    if (!id) return;
    loading.value = true;
    try {
      const [taskResult, orderResult] = await Promise.all([
        api(`/admin/restores/jobs/${encodeURIComponent(id)}`),
        api(`/admin/restores/jobs/${encodeURIComponent(id)}/orders?current=${encodeURIComponent(orderCurrent.value)}&size=${encodeURIComponent(orderSize.value)}`)
      ]);
      selected.value = taskResult.data || null;
      tasks.value = replaceJobById(tasks.value, selected.value);
      const page = normalizePage(orderResult.data);
      orders.value = page.records;
      orderCurrent.value = page.current;
      orderSize.value = page.size;
      orderTotal.value = page.total;
      if (isTerminalJob(selected.value)) stopPolling();
    } catch (error) {
      if (!silent) message.value = error.message || '恢复任务详情加载失败';
      stopPolling();
    } finally {
      loading.value = false;
    }
  }

  async function selectTask(task) {
    selected.value = task;
    orderCurrent.value = 1;
    orderFiles.value = {};
    orderFileLoading.value = {};
    await loadDetail(task.id);
    if (!isTerminalJob(selected.value)) {
      stopPolling();
      pollTimer = setInterval(() => loadDetail(task.id, true), 3000);
    }
  }

  async function refresh() {
    try {
      await loadTasks();
      if (selected.value) await loadDetail(selected.value.id, true);
    } catch (error) {
      message.value = error.message || '恢复任务列表加载失败';
    }
  }

  async function cancel(task = selected.value) {
    if (!task || isTerminalJob(task) || !window.confirm('确认取消该线上恢复任务？')) return;
    try {
      await api(`/admin/jobs/${encodeURIComponent(task.id)}/cancel`, { method: 'POST', body: JSON.stringify({ reason: '管理员取消线上恢复' }) });
      await refresh();
      message.value = '取消请求已提交，执行器将在当前订单安全点停止。';
    } catch (error) { message.value = error.message || '恢复任务取消失败'; }
  }

  async function retry(task = selected.value) {
    if (!task || !['FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(String(task.state || '').toUpperCase())) return;
    try {
      const result = await api(`/admin/restores/jobs/${encodeURIComponent(task.id)}/retry-failed`, { method: 'POST', body: JSON.stringify({ reason: '管理员重试失败订单' }) });
      await refresh();
      const nextId = result.data?.id;
      if (nextId) await selectTask({ id: nextId });
      message.value = '已创建仅包含失败订单的恢复补偿任务。';
    } catch (error) { message.value = error.message || '恢复任务重试失败'; }
  }

  async function changePage(value) { current.value = value; await refresh(); }
  async function changeSize(value) { size.value = value; current.value = 1; await refresh(); }
  async function changeOrderPage(value) { orderCurrent.value = value; await loadDetail(); }
  async function changeOrderSize(value) { orderSize.value = value; orderCurrent.value = 1; await loadDetail(); }

  /** 展开订单时才读取其 ZIP 内文件证据，避免详情页一次装载大量历史资产。 */
  async function loadOrderFiles(sourceOrderId, currentPage = 1, pageSize) {
    if (!selected.value?.id || !sourceOrderId) return;
    const key = String(sourceOrderId);
    const previous = orderFiles.value[key];
    const requestedSize = pageSize || previous?.size || 20;
    orderFileLoading.value = { ...orderFileLoading.value, [key]: true };
    try {
      const result = await api(`/admin/restores/jobs/${encodeURIComponent(selected.value.id)}/orders/${encodeURIComponent(sourceOrderId)}/files?current=${encodeURIComponent(currentPage)}&size=${encodeURIComponent(requestedSize)}`);
      orderFiles.value = { ...orderFiles.value, [key]: normalizePage(result.data) };
    } catch (error) {
      message.value = error.message || '订单文件明细加载失败';
    } finally {
      orderFileLoading.value = { ...orderFileLoading.value, [key]: false };
    }
  }

  async function expandOrder(row, expandedRows) {
    const expanded = (expandedRows || []).some(item => String(item.sourceOrderId) === String(row.sourceOrderId));
    if (expanded) await loadOrderFiles(row.sourceOrderId);
  }

  async function changeOrderFilePage(sourceOrderId, page) {
    await loadOrderFiles(sourceOrderId, page);
  }

  async function changeOrderFileSize(sourceOrderId, fileSize) {
    await loadOrderFiles(sourceOrderId, 1, fileSize);
  }

  /** 打开任务或订单的持久化执行日志；服务端默认已过滤敏感连接信息。 */
  async function loadExecutionLogs(scope, page = logCurrent.value) {
    if (!selected.value?.id || !scope) return;
    logLoading.value = true;
    try {
      const base = `/admin/restores/jobs/${encodeURIComponent(selected.value.id)}`;
      const url = scope.type === 'order'
        ? `${base}/orders/${encodeURIComponent(scope.sourceOrderId)}/logs?current=${encodeURIComponent(page)}&size=${encodeURIComponent(logSize.value)}`
        : `${base}/logs?current=${encodeURIComponent(page)}&size=${encodeURIComponent(logSize.value)}`;
      const result = await api(url);
      const data = normalizePage(result.data);
      logRecords.value = data.records;
      logCurrent.value = data.current;
      logSize.value = data.size;
      logTotal.value = data.total;
    } catch (error) {
      message.value = error.message || '执行日志加载失败';
    } finally {
      logLoading.value = false;
    }
  }

  async function openTaskLogs() {
    logScope.value = { type: 'task' };
    logTitle.value = `恢复任务 ${selected.value?.id || ''} 执行日志`;
    logCurrent.value = 1;
    logDialogVisible.value = true;
    await loadExecutionLogs(logScope.value, 1);
  }

  async function openOrderLogs(row) {
    logScope.value = { type: 'order', sourceOrderId: row.sourceOrderId };
    logTitle.value = `订单 ${row.orderCode || row.sourceOrderId} 执行日志`;
    logCurrent.value = 1;
    logDialogVisible.value = true;
    await loadExecutionLogs(logScope.value, 1);
  }

  async function changeLogPage(page) { await loadExecutionLogs(logScope.value, page); }
  async function changeLogSize(value) { logSize.value = value; await loadExecutionLogs(logScope.value, 1); }

  onUnmounted(stopPolling);
  return { tasks, current, size, total, selected, orders, orderCurrent, orderSize, orderTotal, orderFiles, orderFileLoading, loading, message,
    logDialogVisible, logTitle, logRecords, logCurrent, logSize, logTotal, logLoading,
    selectedProgress, loadTasks, loadDetail, selectTask, refresh, cancel, retry, changePage, changeSize, changeOrderPage, changeOrderSize,
    expandOrder, changeOrderFilePage, changeOrderFileSize, openTaskLogs, openOrderLogs, changeLogPage, changeLogSize };
}
