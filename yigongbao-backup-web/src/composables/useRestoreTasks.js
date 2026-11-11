import { computed, onUnmounted, ref } from 'vue';
import { api } from '../api.js';
import { isTerminalJob, jobProgress, normalizePage, replaceJobById } from '../taskCenter.js';
import { confirmAction } from '../uiFeedback.js';

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
  const detailVisible = ref(false);
  const detailTab = ref('orders');
  const orders = ref([]);
  const orderCurrent = ref(1);
  const orderSize = ref(20);
  const orderTotal = ref(0);

  const selectedOrder = ref(null);
  const orderDetailVisible = ref(false);
  const orderDetailTab = ref('overview');
  const orderFiles = ref([]);
  const orderFileCurrent = ref(1);
  const orderFileSize = ref(20);
  const orderFileTotal = ref(0);
  const orderFileLoading = ref(false);

  const taskLogRecords = ref([]);
  const taskLogCurrent = ref(1);
  const taskLogSize = ref(50);
  const taskLogTotal = ref(0);
  const taskLogLoading = ref(false);
  const orderLogRecords = ref([]);
  const orderLogCurrent = ref(1);
  const orderLogSize = ref(50);
  const orderLogTotal = ref(0);
  const orderLogLoading = ref(false);

  const loading = ref(false);
  const message = ref('');
  let pollTimer = null;

  const selectedProgress = computed(() => jobProgress(selected.value));

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  function resetOrderDetail() {
    selectedOrder.value = null;
    orderDetailVisible.value = false;
    orderDetailTab.value = 'overview';
    orderFiles.value = [];
    orderFileCurrent.value = 1;
    orderFileTotal.value = 0;
    orderFileLoading.value = false;
    orderLogRecords.value = [];
    orderLogCurrent.value = 1;
    orderLogTotal.value = 0;
    orderLogLoading.value = false;
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
    stopPolling();
    selected.value = task;
    detailVisible.value = true;
    detailTab.value = 'orders';
    orderCurrent.value = 1;
    taskLogRecords.value = [];
    taskLogCurrent.value = 1;
    taskLogTotal.value = 0;
    resetOrderDetail();
    await loadDetail(task.id);
    if (!isTerminalJob(selected.value)) {
      pollTimer = setInterval(() => loadDetail(task.id, true), 3000);
    }
  }

  function closeTaskDetail() {
    stopPolling();
    detailVisible.value = false;
    detailTab.value = 'orders';
    selected.value = null;
    orders.value = [];
    orderCurrent.value = 1;
    orderTotal.value = 0;
    taskLogRecords.value = [];
    taskLogCurrent.value = 1;
    taskLogTotal.value = 0;
    resetOrderDetail();
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
    if (!task || isTerminalJob(task) || !await confirmAction('确认取消该线上恢复任务？', { title: '取消恢复任务' })) return;
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

  async function loadTaskLogs(page = taskLogCurrent.value) {
    if (!selected.value?.id) return;
    taskLogLoading.value = true;
    try {
      const result = await api(`/admin/restores/jobs/${encodeURIComponent(selected.value.id)}/logs?current=${encodeURIComponent(page)}&size=${encodeURIComponent(taskLogSize.value)}`);
      const data = normalizePage(result.data);
      taskLogRecords.value = data.records;
      taskLogCurrent.value = data.current;
      taskLogSize.value = data.size;
      taskLogTotal.value = data.total;
    } catch (error) {
      message.value = error.message || '任务执行日志加载失败';
    } finally {
      taskLogLoading.value = false;
    }
  }

  async function changeDetailTab(tab) {
    detailTab.value = tab;
    if (tab === 'logs') await loadTaskLogs(1);
  }

  function openOrderDetail(row) {
    resetOrderDetail();
    selectedOrder.value = row;
    orderDetailVisible.value = true;
  }

  function closeOrderDetail() {
    resetOrderDetail();
  }

  async function loadOrderFiles(page = orderFileCurrent.value) {
    if (!selected.value?.id || !selectedOrder.value?.sourceOrderId) return;
    orderFileLoading.value = true;
    try {
      const result = await api(`/admin/restores/jobs/${encodeURIComponent(selected.value.id)}/orders/${encodeURIComponent(selectedOrder.value.sourceOrderId)}/files?current=${encodeURIComponent(page)}&size=${encodeURIComponent(orderFileSize.value)}`);
      const data = normalizePage(result.data);
      orderFiles.value = data.records;
      orderFileCurrent.value = data.current;
      orderFileSize.value = data.size;
      orderFileTotal.value = data.total;
    } catch (error) {
      message.value = error.message || '订单文件明细加载失败';
    } finally {
      orderFileLoading.value = false;
    }
  }

  async function loadOrderLogs(page = orderLogCurrent.value) {
    if (!selected.value?.id || !selectedOrder.value?.sourceOrderId) return;
    orderLogLoading.value = true;
    try {
      const result = await api(`/admin/restores/jobs/${encodeURIComponent(selected.value.id)}/orders/${encodeURIComponent(selectedOrder.value.sourceOrderId)}/logs?current=${encodeURIComponent(page)}&size=${encodeURIComponent(orderLogSize.value)}`);
      const data = normalizePage(result.data);
      orderLogRecords.value = data.records;
      orderLogCurrent.value = data.current;
      orderLogSize.value = data.size;
      orderLogTotal.value = data.total;
    } catch (error) {
      message.value = error.message || '订单执行日志加载失败';
    } finally {
      orderLogLoading.value = false;
    }
  }

  async function changeOrderDetailTab(tab) {
    orderDetailTab.value = tab;
    if (tab === 'files') await loadOrderFiles(1);
    if (tab === 'logs') await loadOrderLogs(1);
  }

  async function changeOrderFilePage(page) { await loadOrderFiles(page); }
  async function changeOrderFileSize(value) { orderFileSize.value = value; await loadOrderFiles(1); }
  async function changeTaskLogPage(page) { await loadTaskLogs(page); }
  async function changeTaskLogSize(value) { taskLogSize.value = value; await loadTaskLogs(1); }
  async function changeOrderLogPage(page) { await loadOrderLogs(page); }
  async function changeOrderLogSize(value) { orderLogSize.value = value; await loadOrderLogs(1); }

  onUnmounted(stopPolling);
  return {
    tasks, current, size, total, selected, detailVisible, detailTab, orders, orderCurrent, orderSize, orderTotal,
    selectedOrder, orderDetailVisible, orderDetailTab, orderFiles, orderFileCurrent, orderFileSize, orderFileTotal, orderFileLoading,
    taskLogRecords, taskLogCurrent, taskLogSize, taskLogTotal, taskLogLoading,
    orderLogRecords, orderLogCurrent, orderLogSize, orderLogTotal, orderLogLoading,
    loading, message, selectedProgress,
    loadTasks, loadDetail, selectTask, closeTaskDetail, refresh, cancel, retry, changePage, changeSize, changeOrderPage, changeOrderSize,
    changeDetailTab, openOrderDetail, closeOrderDetail, changeOrderDetailTab,
    changeOrderFilePage, changeOrderFileSize, changeTaskLogPage, changeTaskLogSize, changeOrderLogPage, changeOrderLogSize
  };
}
