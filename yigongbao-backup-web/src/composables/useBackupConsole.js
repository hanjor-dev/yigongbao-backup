import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { canRetryFailedOrders, coldImportDiagnostic, extractJobId, isTerminalJob, jobProgress, normalizePage, replaceJobById, storageState, supplementTaskState, taskFailureSummary } from '../taskCenter.js';
import { api, binary, saveBlob, uploadWithProgress } from '../api.js';
import { buildJobFilePageUrl, createDetailRequestGuard, createSectionLoadTracker } from '../jobDetailState.js';
import { createAuthStore } from '../stores/authStore.js';
import { createRouteStore } from '../stores/routeStore.js';

/**
 * 备份控制台领域状态与服务动作。
 *
 * 页面只负责展示和事件绑定，所有跨页面状态、接口调用、长任务轮询
 * 统一在此管理，确保切换页面不会丢失任务上下文。
 */
export function useBackupConsole() {
  const auth = createAuthStore();
  const { token, role, isAdmin } = auth;
  const route = createRouteStore();
  const { page } = route;
  // 兼容现有模板中的 page 直接赋值，同时将导航状态持久化并同步到 hash。
  watch(page, (value) => route.navigate(value));
  const username = ref('');
  const password = ref('');
  const selfPassword = ref('');
  const orders = ref([]);
  const orderPage = ref(1);
  const orderSize = ref(20);
  const orderTotal = ref(0);
  const orderPages = ref(0);
  const selectedOrder = ref(null);
  const selectedIds = ref([]);
  const hasUnavailableSelection = computed(() => orders.value.some((order) => selectedIds.value.includes(order.id) && order.storageAccessible === false));
  const assets = ref([]);
  const supplements = ref([]);
  const supplementUpload = ref({
    status: 'IDLE',
    percent: 0,
    message: '',
    fileName: '',
    jobId: null,
  });
  const notice = ref('');
  const keyword = ref('');
  const createdFrom = ref('');
  const createdTo = ref('');
  const businessType = ref('');
  const status = ref('');
  const needsPhysicalDelivery = ref('');
  const cloudRollup = ref('');
  const hotRollup = ref('');
  const coldRollup = ref('');
  const orderFilterOptions = ref({
    orderStatuses: [],
    businessTypes: [],
    physicalDeliveries: [],
    storageStates: [],
    localReplicaStates: [],
  });
  const storagePath = ref('');
  const storage = ref(null);
  const storageVolumes = ref([]);
  const storageCandidates = ref([]);
  const coldStorageArchives = ref([]);
  const selectedStorageArchive = ref(null);
  const coldStorageLoading = ref(false);
  const days = ref(180);
  const cleanupResult = ref(null);
  const jobs = ref([]);
  const jobPage = ref(1);
  const jobSize = ref(20);
  const jobTotal = ref(0);
  const selectedJobId = ref(null);
  const selectedJob = ref(null);
  const jobDetailVisible = ref(false);
  const jobDetailTab = ref('orders');
  const jobOrders = ref([]);
  const jobOrderPage = ref(1);
  const jobOrderSize = ref(20);
  const jobOrderTotal = ref(0);
  const jobFiles = ref([]);
  const jobFilePage = ref(1);
  const jobFileSize = ref(20);
  const jobFileTotal = ref(0);
  const jobFileLoading = ref(false);
  const jobLoading = ref(false);
  const selectedJobOrder = ref(null);
  const jobOrderDetailVisible = ref(false);
  const jobOrderDetailTab = ref('overview');
  const jobOrderFiles = ref([]);
  const jobOrderFilePage = ref(1);
  const jobOrderFileSize = ref(20);
  const jobOrderFileTotal = ref(0);
  const jobOrderFileLoading = ref(false);
  const jobLogRecords = ref([]);
  const jobLogCurrent = ref(1);
  const jobLogSize = ref(50);
  const jobLogTotal = ref(0);
  const jobLogLoading = ref(false);
  const orderLogRecords = ref([]);
  const orderLogCurrent = ref(1);
  const orderLogSize = ref(50);
  const orderLogTotal = ref(0);
  const orderLogLoading = ref(false);
  const scheduledBackupRunning = ref(false);
  const coldBatches = ref([]);
  const coldDisks = ref([]);
  const coldDiskId = ref('');
  const coldFrom = ref('');
  const coldTo = ref('');
  const diskCode = ref('');
  const diskLabel = ref('');
  const coldArchives = ref([]);
  const coldArchive = ref('');
  const coldOrderIds = ref('');
  const coldRoot = ref('');
  const writePackages = ref('');
  const hotCleanupPackages = ref('');
  const selectedBatchId = ref('');
  const selectedColdBatch = ref(null);
  const coldBusy = ref(false);
  const coldDiagnostics = ref([]);
  const configs = ref([]);
  const configCategories = ref([]);
  const selectedConfigCategory = ref('');
  const overview = ref(null);
  const accounts = ref([]);
  const accountPage = ref(1);
  const accountSize = ref(20);
  const accountTotal = ref(0);
  const accountPages = ref(0);
  const accountPassword = ref({});
  const accountSyncStatus = ref(null);
  const accountSyncRunning = ref(false);
  const configKey = ref('backup.schedule.interval-days');
  const configValue = ref('30');
  const configVersion = ref(0);
  let jobPollTimer = null;
  let supplementPollTimer = null;
  let jobLoadingCount = 0;
  const detailRequestGuard = createDetailRequestGuard();
  const detailSectionTracker = createSectionLoadTracker();

  function stopJobPolling() {
    if (jobPollTimer) {
      clearInterval(jobPollTimer);
      jobPollTimer = null;
    }
  }

  function beginJobLoading() {
    jobLoadingCount += 1;
    jobLoading.value = true;
  }

  function endJobLoading() {
    jobLoadingCount = Math.max(0, jobLoadingCount - 1);
    jobLoading.value = jobLoadingCount > 0;
  }

  function stopSupplementPolling() {
    if (supplementPollTimer) {
      clearInterval(supplementPollTimer);
      supplementPollTimer = null;
    }
  }

  async function pollSupplementTask(jobId, orderKey) {
    try {
      const result = await api('/orders/' + encodeURIComponent(orderKey) + '/supplements/tasks/' + encodeURIComponent(jobId));
      const job = result.data || null;
      const state = supplementTaskState(job, 100);
      supplementUpload.value = { ...supplementUpload.value, ...state, jobId };
      if (isTerminalJob(job)) {
        stopSupplementPolling();
        if (state.status === 'SUCCEEDED') await loadSelectedOrder();
      }
    } catch (error) {
      stopSupplementPolling();
      supplementUpload.value = {
        ...supplementUpload.value,
        status: 'FAILED',
        message: error.message || '补传任务状态查询失败',
      };
    }
  }

  function startSupplementPolling(jobId, orderKey) {
    stopSupplementPolling();
    pollSupplementTask(jobId, orderKey);
    supplementPollTimer = setInterval(() => pollSupplementTask(jobId, orderKey), 2000);
  }

  function startJobPolling() {
    stopJobPolling();
    jobPollTimer = setInterval(async () => {
      if (page.value !== 'backup' || !jobDetailVisible.value || !selectedJobId.value) return;
      await Promise.all([
        loadJobSummary(selectedJobId.value, true),
        loadJobOrders(selectedJobId.value, true),
      ]);
    }, 3000);
  }

  async function openJob(jobId, message = '任务已提交') {
    if (!jobId) {
      notice.value = '操作已提交，但服务端未返回任务编号';
      return;
    }
    prepareJobDetail(jobId);
    jobDetailVisible.value = page.value === 'backup';
    notice.value = message;
    await loadJobDetails(selectedJobId.value);
    if (page.value === 'backup' && selectedJob.value && !isTerminalJob(selectedJob.value)) startJobPolling();
  }

  async function login() {
    try {
      await auth.login(username.value, password.value);
      notice.value = '登录成功';
      await loadOrders();
    } catch (error) {
      notice.value = error.message || '登录失败';
    }
  }

  async function changeSelfPassword() {
    try {
      await auth.changePassword(selfPassword.value);
      selfPassword.value = '';
      notice.value = '本地密码修改成功，不会影响线上账号';
    } catch (error) {
      notice.value = error.message || '本地密码修改失败';
    }
  }

  function selectedOrderKey() {
    // 补传接口的 orderId 使用本地快照 ID，由后端解析到稳定订单号，避免订单号恰好为纯数字时歧义。
    return selectedOrder.value?.id || '';
  }

  async function loadOrders(pageNumber = orderPage.value) {
    try {
      const params = new URLSearchParams({
        current: String(pageNumber),
        size: String(orderSize.value),
      });
      const normalizedKeyword = String(keyword.value || '').trim();
      const normalizedBusinessType = String(businessType.value || '').trim();
      if (normalizedKeyword) params.set('keyword', normalizedKeyword);
      if (createdFrom.value) params.set('createdFrom', createdFrom.value);
      if (createdTo.value) params.set('createdTo', createdTo.value);
      if (normalizedBusinessType) params.set('businessType', normalizedBusinessType);
      if (status.value !== '' && status.value != null) params.set('status', status.value);
      if (needsPhysicalDelivery.value !== '' && needsPhysicalDelivery.value != null) params.set('needsPhysicalDelivery', needsPhysicalDelivery.value);
      if (cloudRollup.value) params.set('cloudRollup', cloudRollup.value);
      if (hotRollup.value) params.set('hotRollup', hotRollup.value);
      if (coldRollup.value) params.set('coldRollup', coldRollup.value);
      const result = await api('/orders?' + params.toString());
      const page = normalizePage(result.data);
      orderPage.value = page.current;
      orderSize.value = page.size;
      orderTotal.value = page.total;
      orderPages.value = page.pages;
      orders.value = page.records;
      selectedIds.value = selectedIds.value.filter((id) => orders.value.some((item) => item.id === id));
      if (selectedOrder.value) {
        selectedOrder.value = orders.value.find((item) => item.id === selectedOrder.value.id) || null;
        if (selectedOrder.value) await loadSelectedOrder();
      }
    } catch (error) {
      notice.value = error.message || '订单查询失败';
    }
  }

  function changeOrderPage(pageNumber) {
    if (pageNumber < 1 || (orderPages.value > 0 && pageNumber > orderPages.value)) return;
    loadOrders(pageNumber);
  }

  function changeOrderSize() {
    orderPage.value = 1;
    loadOrders(1);
  }

  async function selectOrder(order) {
    selectedOrder.value = order;
    await loadSelectedOrder();
  }

  function toggleSelected(order) {
    if (order.storageAccessible === false) {
      notice.value = order.storageUnavailableReason || '订单所在存储卷不可访问，不能发起线上恢复';
      return;
    }
    selectedIds.value = selectedIds.value.includes(order.id) ? selectedIds.value.filter((id) => id !== order.id) : [...selectedIds.value, order.id];
    selectOrder(order);
  }

  async function loadSelectedOrder() {
    if (!selectedOrder.value) return;
    const id = encodeURIComponent(selectedOrder.value.id);
    const key = encodeURIComponent(selectedOrderKey());
    try {
      const [assetResult, supplementResult, locationResult] = await Promise.all([api('/orders/' + id + '/assets'), api('/orders/' + key + '/supplements'), api('/orders/' + id + '/package-location')]);
      assets.value = assetResult.data || [];
      supplements.value = supplementResult.data || [];
      selectedOrder.value = {
        ...selectedOrder.value,
        storageLocation: locationResult.data || null,
      };
    } catch (error) {
      notice.value = error.message || '订单文件加载失败';
    }
  }

  async function downloadOrder(order = selectedOrder.value) {
    if (!order) {
      notice.value = '请先选择订单';
      return;
    }
    try {
      const response = await binary('/orders/' + encodeURIComponent(order.id) + '/package/download');
      const blob = await response.blob();
      saveBlob(blob, (order.orderCode || order.sourceOrderId || order.id) + '.zip');
    } catch (error) {
      notice.value = error.message || '订单包下载失败';
    }
  }

  async function uploadSupplement(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!selectedOrder.value) {
      notice.value = '请先选择目标订单，再补传文件';
      return;
    }
    const form = new FormData();
    form.append('file', file);
    try {
      supplementUpload.value = {
        status: 'UPLOADING',
        percent: 0,
        message: '正在上传文件',
        fileName: file.name || '',
        jobId: null,
      };
      const result = await uploadWithProgress('/orders/' + encodeURIComponent(selectedOrderKey()) + '/supplements', form, (percent) => {
        supplementUpload.value = {
          ...supplementUpload.value,
          ...supplementTaskState(null, percent),
        };
      });
      const jobId = extractJobId(result);
      if (jobId) {
        supplementUpload.value = {
          ...supplementUpload.value,
          status: 'PROCESSING',
          percent: 100,
          message: '文件已上传，后台正在处理',
          jobId,
        };
        startSupplementPolling(jobId, selectedOrderKey());
      } else {
        supplementUpload.value = {
          ...supplementUpload.value,
          status: 'FAILED',
          message: '服务端未返回补传任务编号',
        };
      }
    } catch (error) {
      supplementUpload.value = {
        ...supplementUpload.value,
        status: 'FAILED',
        message: error.message || '补传文件失败',
      };
      notice.value = error.message || '补传文件失败';
    }
  }

  async function downloadSupplement(file) {
    if (!selectedOrder.value) return;
    if (file.storageAccessible === false) {
      notice.value = file.storageUnavailableReason || '补传文件所在存储卷不可访问';
      return;
    }
    try {
      const response = await binary('/orders/' + encodeURIComponent(selectedOrderKey()) + '/supplements/' + encodeURIComponent(file.fileId) + '/download');
      saveBlob(await response.blob(), file.fileName || file.fileId);
    } catch (error) {
      notice.value = error.message || '补传文件下载失败';
    }
  }

  async function restoreSelectedOrder() {
    if (!selectedOrder.value || !isAdmin.value) return;
    if (selectedOrder.value.backupMode === 'METADATA_SIMULATION') {
      notice.value = '模拟备份包仅供流程验证和下载查看，禁止线上恢复';
      return;
    }
    try {
      const result = await api('/admin/restores', {
        method: 'POST',
        headers: { 'Idempotency-Key': `restore-${crypto.randomUUID()}` },
        body: JSON.stringify({ orderSnapshotId: selectedOrder.value.id }),
      });
      const jobId = extractJobId(result);
      if (jobId) sessionStorage.setItem('backup-restore-task-focus', String(jobId));
      route.navigate('restore');
      notice.value = '线上恢复任务已提交，已跳转至恢复任务页面。';
    } catch (error) {
      notice.value = error.message || '线上恢复失败';
    }
  }

  async function restoreBatch() {
    if (!isAdmin.value || (!selectedIds.value.length && !createdFrom.value && !createdTo.value)) {
      notice.value = '请选择多个订单或填写创建时间范围';
      return;
    }
    if (hasUnavailableSelection.value) {
      notice.value = '所选订单中包含存储卷不可访问的订单，请取消选择后重试';
      return;
    }
    if (!confirm('确认批量线上恢复？线上已删除订单将自动跳过。')) return;
    try {
      const result = await api('/admin/restores/batch', {
        method: 'POST',
        headers: { 'Idempotency-Key': `restore-${crypto.randomUUID()}` },
        body: JSON.stringify({
          orderSnapshotIds: selectedIds.value.length ? selectedIds.value : [],
          createdFrom: selectedIds.value.length ? null : createdFrom.value || null,
          createdTo: selectedIds.value.length ? null : createdTo.value || null,
        }),
      });
      const jobId = extractJobId(result);
      if (jobId) sessionStorage.setItem('backup-restore-task-focus', String(jobId));
      route.navigate('restore');
      notice.value = '批量线上恢复任务已提交，已跳转至恢复任务页面。';
    } catch (error) {
      notice.value = error.message || '批量恢复失败';
    }
  }

  async function probeStorage() {
    try {
      const result = await api('/admin/storage/probe', {
        method: 'POST',
        body: JSON.stringify({ path: storagePath.value }),
      });
      storage.value = result.data;
    } catch (error) {
      notice.value = error.message;
    }
  }

  async function dryCleanup() {
    try {
      const result = await api('/admin/cleanup/dry-runs', {
        method: 'POST',
        body: JSON.stringify({ olderThanDays: Number(days.value) }),
      });
      cleanupResult.value = result.data;
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, 'COS 清理试运行任务已提交');
    } catch (error) {
      notice.value = error.message;
    }
  }

  async function executeCleanup() {
    const taskId = cleanupResult.value?.taskId;
    if (!taskId || !confirm('确认提交 COS 清理授权执行？')) return;
    try {
      const result = await api('/admin/cleanup/permit-poll', {
        method: 'POST',
        body: JSON.stringify({ taskId }),
      });
      cleanupResult.value = result.data;
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, 'COS 清理任务已提交');
      else notice.value = 'COS 清理执行结果已返回';
    } catch (error) {
      notice.value = error.message;
    }
  }

  async function loadJobs(pageNumber = jobPage.value) {
    try {
      const result = await api(`/admin/backup/jobs?current=${encodeURIComponent(pageNumber)}&size=${encodeURIComponent(jobSize.value)}`);
      const pageData = normalizePage(result.data);
      jobs.value = pageData.records;
      jobPage.value = pageData.current;
      jobSize.value = pageData.size;
      jobTotal.value = pageData.total;
      if (selectedJobId.value) {
        const matched = jobs.value.find((job) => String(job.id) === String(selectedJobId.value));
        if (matched) {
          selectedJob.value = matched;
        }
      }
    } catch (error) {
      notice.value = error.message;
    }
  }

  function changeJobPage(pageNumber) {
    jobPage.value = pageNumber;
    loadJobs(pageNumber);
  }
  function changeJobSize(size) {
    jobSize.value = size;
    jobPage.value = 1;
    loadJobs(1);
  }

  function resetJobDetailCollections() {
    jobDetailTab.value = 'orders';
    jobOrders.value = [];
    jobOrderPage.value = 1;
    jobOrderTotal.value = 0;
    jobFiles.value = [];
    jobFilePage.value = 1;
    jobFileTotal.value = 0;
    jobFileLoading.value = false;
    jobLogRecords.value = [];
    jobLogCurrent.value = 1;
    jobLogTotal.value = 0;
    jobLogLoading.value = false;
    selectedJobOrder.value = null;
    jobOrderDetailVisible.value = false;
    jobOrderDetailTab.value = 'overview';
    jobOrderFiles.value = [];
    jobOrderFilePage.value = 1;
    jobOrderFileTotal.value = 0;
    jobOrderFileLoading.value = false;
    orderLogRecords.value = [];
    orderLogCurrent.value = 1;
    orderLogTotal.value = 0;
    orderLogLoading.value = false;
  }

  function prepareJobDetail(jobId) {
    stopJobPolling();
    selectedJobId.value = String(jobId);
    detailRequestGuard.openTask(jobId);
    detailSectionTracker.resetAll();
    resetJobDetailCollections();
  }

  function closeJobOrderDetail() {
    detailRequestGuard.closeOrder();
    detailSectionTracker.reset('order-files');
    detailSectionTracker.reset('order-logs');
    jobOrderDetailVisible.value = false;
    selectedJobOrder.value = null;
    jobOrderDetailTab.value = 'overview';
    jobOrderFiles.value = [];
    jobOrderFilePage.value = 1;
    jobOrderFileTotal.value = 0;
    jobOrderFileLoading.value = false;
    orderLogRecords.value = [];
    orderLogCurrent.value = 1;
    orderLogTotal.value = 0;
    orderLogLoading.value = false;
  }

  function closeJobDetail() {
    stopJobPolling();
    detailRequestGuard.closeTask();
    detailSectionTracker.resetAll();
    jobDetailVisible.value = false;
    selectedJobId.value = null;
    selectedJob.value = null;
    resetJobDetailCollections();
  }

  function handleJobDetailError(error, silent, token) {
    if (!detailRequestGuard.isCurrentTaskRequest(token)) return;
    if (error?.message?.includes('任务不存在')) {
      closeJobDetail();
      notice.value = '任务已不存在，已关闭任务详情';
    } else if (!silent) {
      notice.value = error?.message || '任务详情加载失败';
    }
  }

  async function loadJobSummary(jobId = selectedJobId.value, silent = false) {
    if (!jobId) return;
    const token = detailRequestGuard.beginTaskRequest('summary');
    beginJobLoading();
    try {
      const result = await api('/admin/jobs/' + encodeURIComponent(jobId));
      if (!detailRequestGuard.isCurrentTaskRequest(token)) return;
      selectedJob.value = result.data || null;
      jobs.value = replaceJobById(jobs.value, selectedJob.value);
      if (selectedJob.value?.jobType === 'CLEANUP_DRY_RUN' && selectedJob.value?.resultJson) {
        try {
          cleanupResult.value = JSON.parse(selectedJob.value.resultJson);
        } catch (_) {
          notice.value = 'COS 清理试运行结果格式异常';
        }
      }
      if (selectedJob.value && isTerminalJob(selectedJob.value)) stopJobPolling();
    } catch (error) {
      handleJobDetailError(error, silent, token);
    } finally {
      endJobLoading();
    }
  }

  async function loadJobOrders(jobId = selectedJobId.value, silent = false) {
    if (!jobId) return;
    const token = detailRequestGuard.beginTaskRequest('orders');
    beginJobLoading();
    try {
      const result = await api('/admin/jobs/' + encodeURIComponent(jobId)
        + `/orders?current=${encodeURIComponent(jobOrderPage.value)}&size=${encodeURIComponent(jobOrderSize.value)}`);
      if (!detailRequestGuard.isCurrentTaskRequest(token)) return;
      const pageData = normalizePage(result.data);
      jobOrders.value = pageData.records;
      jobOrderPage.value = pageData.current;
      jobOrderSize.value = pageData.size;
      jobOrderTotal.value = pageData.total;
    } catch (error) {
      handleJobDetailError(error, silent, token);
    } finally {
      endJobLoading();
    }
  }

  async function loadJobDetails(jobId = selectedJobId.value, silent = false) {
    if (!jobId) return;
    await Promise.all([loadJobSummary(jobId, silent), loadJobOrders(jobId, silent)]);
  }

  async function loadJobFiles(current = jobFilePage.value) {
    if (!selectedJobId.value) return;
    const token = detailRequestGuard.beginTaskRequest('files');
    jobFileLoading.value = true;
    try {
      const result = await api(buildJobFilePageUrl(
        selectedJobId.value, current, jobFileSize.value,
      ));
      if (!detailRequestGuard.isCurrentTaskRequest(token)) return;
      const pageData = normalizePage(result.data);
      jobFiles.value = pageData.records;
      jobFilePage.value = pageData.current;
      jobFileSize.value = pageData.size;
      jobFileTotal.value = pageData.total;
      detailSectionTracker.markLoaded('task-files', selectedJobId.value);
    } catch (error) {
      if (detailRequestGuard.isCurrentTaskRequest(token)) notice.value = error.message || '任务文件明细加载失败';
    } finally {
      if (detailRequestGuard.isCurrentTaskRequest(token)) jobFileLoading.value = false;
    }
  }

  async function loadOrderFiles(current = jobOrderFilePage.value) {
    const sourceOrderId = selectedJobOrder.value?.sourceOrderId;
    if (!selectedJobId.value || !sourceOrderId) return;
    const token = detailRequestGuard.beginOrderRequest('files');
    jobOrderFileLoading.value = true;
    try {
      const result = await api(buildJobFilePageUrl(
        selectedJobId.value, current, jobOrderFileSize.value, sourceOrderId,
      ));
      if (!detailRequestGuard.isCurrentOrderRequest(token)) return;
      const pageData = normalizePage(result.data);
      jobOrderFiles.value = pageData.records;
      jobOrderFilePage.value = pageData.current;
      jobOrderFileSize.value = pageData.size;
      jobOrderFileTotal.value = pageData.total;
      detailSectionTracker.markLoaded('order-files', `${selectedJobId.value}:${sourceOrderId}`);
    } catch (error) {
      if (detailRequestGuard.isCurrentOrderRequest(token)) notice.value = error.message || '订单文件明细加载失败';
    } finally {
      if (detailRequestGuard.isCurrentOrderRequest(token)) jobOrderFileLoading.value = false;
    }
  }

  /** 获取已纳管卷；页面不得再由浏览器提交任意服务端物理路径。 */
  async function loadStorageVolumes() {
    try {
      storageVolumes.value = (await api('/admin/storage-volumes')).data || [];
    } catch (error) {
      notice.value = error.message || '加载存储卷失败';
    }
  }
  async function loadStorageVolumeDetail(id) {
    try {
      const [detail, history] = await Promise.all([api(`/admin/storage-volumes/${encodeURIComponent(id)}`), api(`/admin/storage-volumes/${encodeURIComponent(id)}/archives`)]);
      return { ...(detail.data || {}), archives: history.data || [] };
    } catch (error) {
      notice.value = error.message || '加载存储卷详情失败';
      return null;
    }
  }
  async function scanStorageVolumes() {
    try {
      storageCandidates.value =
        (
          await api('/admin/storage-volumes/scan', {
            method: 'POST',
            body: '{}',
          })
        ).data || [];
    } catch (error) {
      notice.value = error.message || '扫描存储卷失败';
    }
  }
  async function registerStorageVolume(candidate, displayName = candidate?.mountPath) {
    try {
      await api('/admin/storage-volumes', {
        method: 'POST',
        body: JSON.stringify({
          mountPath: candidate?.mountPath,
          displayName,
          tier: 'HOT',
          priority: 0,
        }),
      });
      await loadStorageVolumes();
    } catch (error) {
      notice.value = error.message || '纳管存储卷失败';
    }
  }
  async function changeStorageVolumeState(id, action, body = null) {
    try {
      const knownAction = ['set-active', 'set-standby', 'to-hot', 'disable', 'probe', 'confirm-mounted', 'prepare-offline', 'failover'];
      const suffix = knownAction.includes(action) ? `/${action}` : '/state';
      await api(`/admin/storage-volumes/${id}${suffix}`, {
        method: 'POST',
        body: body == null ? (suffix === '/state' ? JSON.stringify(body) : undefined) : JSON.stringify(body),
      });
      await loadStorageVolumes();
    } catch (error) {
      notice.value = error.message || '更新存储卷状态失败';
    }
  }
  async function previewStorageArchive(id, successorVolumeId = null) {
    const query = successorVolumeId ? `?successorVolumeId=${encodeURIComponent(successorVolumeId)}` : '';
    try {
      return (await api(`/admin/storage-volumes/${id}/archive-preview${query}`)).data || null;
    } catch (error) {
      notice.value = error.message || '封存预检失败';
      return null;
    }
  }
  async function startStorageArchive(id, payload) {
    try {
      const result = await api(`/admin/storage-volumes/${id}/archive`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      notice.value = '整盘封存任务已提交；校验完成前请勿拔盘';
      route.navigate('cold-storage');
      await loadColdStorage();
      if (result.data?.id) {
        const attempt = coldStorageArchives.value.find((item) => String(item.archive?.jobId) === String(result.data.id));
        if (attempt?.archive?.id) {
          sessionStorage.setItem('cold-storage-archive-focus', String(attempt.archive.id));
          await openColdStorageArchive(attempt.archive.id);
        }
      }
      return result.data || null;
    } catch (error) {
      notice.value = error.message || '整盘封存启动失败';
      return null;
    }
  }
  async function loadStorageArchiveDetail(archiveId) {
    try {
      return (await api(`/admin/storage-volumes/archives/${encodeURIComponent(archiveId)}`)).data || null;
    } catch (error) {
      notice.value = error.message || '加载封存明细失败';
      return null;
    }
  }
  async function loadColdStorage() {
    coldStorageLoading.value = true;
    try {
      const [archiveResult, volumeResult] = await Promise.all([
        api('/admin/storage-volumes/archives'),
        api('/admin/storage-volumes'),
      ]);
      coldStorageArchives.value = archiveResult.data || [];
      storageVolumes.value = volumeResult.data || [];
      const focused = sessionStorage.getItem('cold-storage-archive-focus');
      if (focused && !selectedStorageArchive.value) await openColdStorageArchive(focused, true);
    } catch (error) {
      notice.value = error.message || '加载冷备管理信息失败';
    } finally {
      coldStorageLoading.value = false;
    }
  }
  async function openColdStorageArchive(archiveId, silent = false) {
    try {
      selectedStorageArchive.value = await loadStorageArchiveDetail(archiveId);
      if (selectedStorageArchive.value) sessionStorage.setItem('cold-storage-archive-focus', String(archiveId));
      return selectedStorageArchive.value;
    } catch (error) {
      if (!silent) notice.value = error.message || '加载封存任务详情失败';
      return null;
    }
  }
  async function scanColdMountCandidates() {
    await scanStorageVolumes();
    return storageCandidates.value;
  }
  async function confirmColdVolumeMounted(volumeId, candidate) {
    if (!candidate?.mountPath) {
      notice.value = '请先选择扫描到的候选挂载点';
      return false;
    }
    try {
      await api(`/admin/storage-volumes/${encodeURIComponent(volumeId)}/confirm-mounted`, {
        method: 'POST',
        body: JSON.stringify({ mountPath: candidate.mountPath }),
      });
      notice.value = '冷备介质身份校验通过，已确认挂载';
      await loadColdStorage();
      return true;
    } catch (error) {
      notice.value = error.message || '冷备介质确认挂载失败';
      return false;
    }
  }
  async function prepareColdVolumeOffline(volumeId) {
    try {
      await api(`/admin/storage-volumes/${encodeURIComponent(volumeId)}/prepare-offline`, { method: 'POST' });
      notice.value = '冷备卷已准备离线，请在操作系统中安全弹出后再拔盘';
      await loadColdStorage();
      return true;
    } catch (error) {
      notice.value = error.message || '冷备卷准备离线失败';
      return false;
    }
  }
  async function abandonStorageArchive(archiveId, reason) {
    try {
      await api(`/admin/storage-volumes/archives/${encodeURIComponent(archiveId)}/abandon`, { method: 'POST', body: JSON.stringify({ reason }) });
      notice.value = '已放弃本次封存尝试，源卷保持只读待检修';
      return true;
    } catch (error) {
      notice.value = error.message || '放弃封存尝试失败';
      return false;
    }
  }

  /** 管理员主动执行一次候选扫描；服务端仍复用自动备份的筛选、排重与任务幂等规则。 */
  async function runScheduledBackupNow() {
    if (scheduledBackupRunning.value) return;
    if (!confirm('确认立即执行一次自动备份扫描？该操作会跳过今日计划时间限制，但仍仅处理符合备份条件且尚未完成备份的订单。')) return;
    scheduledBackupRunning.value = true;
    try {
      await api('/admin/backup/schedule/run-now', {
        method: 'POST',
        body: '{}',
      });
      await loadJobs(1);
      notice.value = '自动备份扫描已完成，请在任务列表查看本次创建的任务；如无新任务，表示当前没有待备份订单。';
    } catch (error) {
      notice.value = error.message || '立即执行自动备份扫描失败';
    } finally {
      scheduledBackupRunning.value = false;
    }
  }

  /** 加载本地固定订单筛选项；接口不可用时不阻断订单列表查询。 */
  async function loadOrderFilterOptions() {
    try {
      const result = await api('/orders/filter-options');
      const data = result.data || {};
      orderFilterOptions.value = {
        orderStatuses: Array.isArray(data.orderStatuses) ? data.orderStatuses : [],
        businessTypes: Array.isArray(data.businessTypes) ? data.businessTypes : [],
        physicalDeliveries: Array.isArray(data.physicalDeliveries) ? data.physicalDeliveries : [],
        storageStates: Array.isArray(data.storageStates) ? data.storageStates : [],
        localReplicaStates: Array.isArray(data.localReplicaStates)
          ? data.localReplicaStates
          : (Array.isArray(data.storageStates) ? data.storageStates.filter(item => ['PRESENT', 'ABSENT'].includes(item.value)) : []),
      };
    } catch (error) {
      notice.value = error.message || '订单筛选项加载失败';
    }
  }

  /** 清空订单文件页筛选条件，并重新从第一页查询。 */
  function resetOrderFilters() {
    keyword.value = '';
    createdFrom.value = '';
    createdTo.value = '';
    status.value = '';
    businessType.value = '';
    needsPhysicalDelivery.value = '';
    cloudRollup.value = '';
    hotRollup.value = '';
    coldRollup.value = '';
    loadOrders(1);
  }

  function changeJobOrderPage(pageNumber) {
    jobOrderPage.value = pageNumber;
    loadJobOrders(selectedJobId.value);
  }

  function changeJobOrderSize(size) {
    jobOrderSize.value = size;
    jobOrderPage.value = 1;
    loadJobOrders(selectedJobId.value);
  }

  function changeJobFilePage(pageNumber) {
    jobFilePage.value = pageNumber;
    loadJobFiles(pageNumber);
  }

  function changeJobFileSize(size) {
    jobFileSize.value = size;
    jobFilePage.value = 1;
    loadJobFiles(1);
  }

  function changeJobOrderFilePage(pageNumber) {
    jobOrderFilePage.value = pageNumber;
    loadOrderFiles(pageNumber);
  }

  function changeJobOrderFileSize(size) {
    jobOrderFileSize.value = size;
    jobOrderFilePage.value = 1;
    loadOrderFiles(1);
  }

  /** 读取任务级持久化日志；与订单日志维护独立分页状态。 */
  async function loadTaskLogs(current = jobLogCurrent.value) {
    if (!selectedJobId.value) return;
    const token = detailRequestGuard.beginTaskRequest('logs');
    jobLogLoading.value = true;
    try {
      const base = `/admin/backup/jobs/${encodeURIComponent(selectedJobId.value)}`;
      const result = await api(`${base}/logs?current=${encodeURIComponent(current)}&size=${encodeURIComponent(jobLogSize.value)}`);
      if (!detailRequestGuard.isCurrentTaskRequest(token)) return;
      const pageData = normalizePage(result.data);
      jobLogRecords.value = pageData.records;
      jobLogCurrent.value = pageData.current;
      jobLogSize.value = pageData.size;
      jobLogTotal.value = pageData.total;
      detailSectionTracker.markLoaded('task-logs', selectedJobId.value);
    } catch (error) {
      if (detailRequestGuard.isCurrentTaskRequest(token)) notice.value = error.message || '任务执行日志加载失败';
    } finally {
      if (detailRequestGuard.isCurrentTaskRequest(token)) jobLogLoading.value = false;
    }
  }

  /** 读取订单级持久化日志；抽屉切换订单后旧响应不会回写。 */
  async function loadOrderLogs(current = orderLogCurrent.value) {
    const sourceOrderId = selectedJobOrder.value?.sourceOrderId;
    if (!selectedJobId.value || !sourceOrderId) return;
    const token = detailRequestGuard.beginOrderRequest('logs');
    orderLogLoading.value = true;
    try {
      const base = `/admin/backup/jobs/${encodeURIComponent(selectedJobId.value)}`;
      const result = await api(`${base}/orders/${encodeURIComponent(sourceOrderId)}/logs?current=${encodeURIComponent(current)}&size=${encodeURIComponent(orderLogSize.value)}`);
      if (!detailRequestGuard.isCurrentOrderRequest(token)) return;
      const pageData = normalizePage(result.data);
      orderLogRecords.value = pageData.records;
      orderLogCurrent.value = pageData.current;
      orderLogSize.value = pageData.size;
      orderLogTotal.value = pageData.total;
      detailSectionTracker.markLoaded('order-logs', `${selectedJobId.value}:${sourceOrderId}`);
    } catch (error) {
      if (detailRequestGuard.isCurrentOrderRequest(token)) notice.value = error.message || '订单执行日志加载失败';
    } finally {
      if (detailRequestGuard.isCurrentOrderRequest(token)) orderLogLoading.value = false;
    }
  }

  async function changeJobDetailTab(value) {
    jobDetailTab.value = value;
    if (value === 'files' && detailSectionTracker.shouldLoad('task-files', selectedJobId.value)) {
      await loadJobFiles(1);
    }
    if (value === 'logs' && detailSectionTracker.shouldLoad('task-logs', selectedJobId.value)) {
      await loadTaskLogs(1);
    }
  }

  async function openJobOrderDetail(order, tab = 'overview') {
    if (!order?.sourceOrderId) return;
    closeJobOrderDetail();
    selectedJobOrder.value = order;
    jobOrderDetailVisible.value = true;
    jobOrderDetailTab.value = tab;
    detailRequestGuard.openOrder(order.sourceOrderId);
    if (tab !== 'overview') await changeJobOrderDetailTab(tab);
  }

  async function changeJobOrderDetailTab(value) {
    jobOrderDetailTab.value = value;
    const key = `${selectedJobId.value}:${selectedJobOrder.value?.sourceOrderId || ''}`;
    if (value === 'files' && detailSectionTracker.shouldLoad('order-files', key)) await loadOrderFiles(1);
    if (value === 'logs' && detailSectionTracker.shouldLoad('order-logs', key)) await loadOrderLogs(1);
  }

  async function openBackupTaskLogs() {
    await changeJobDetailTab('logs');
  }

  async function openBackupOrderLogs(order) {
    await openJobOrderDetail(order, 'logs');
  }

  async function changeBackupLogPage(value) {
    await loadTaskLogs(value);
  }
  async function changeBackupLogSize(value) {
    jobLogSize.value = value;
    await loadTaskLogs(1);
  }
  async function changeOrderLogPage(value) {
    await loadOrderLogs(value);
  }
  async function changeOrderLogSize(value) {
    orderLogSize.value = value;
    await loadOrderLogs(1);
  }

  async function selectJob(job) {
    prepareJobDetail(job.id);
    selectedJob.value = job;
    jobDetailVisible.value = true;
    await loadJobDetails(job.id);
    if (selectedJob.value && !isTerminalJob(selectedJob.value)) startJobPolling();
  }

  async function cancelJob(job = selectedJob.value) {
    if (!job || isTerminalJob(job) || !confirm('确认取消该任务？')) return;
    try {
      await api('/admin/jobs/' + encodeURIComponent(job.id) + '/cancel', {
        method: 'POST',
        body: JSON.stringify({ reason: '管理员取消' }),
      });
      await loadJobDetails(job.id);
      if (!isTerminalJob(selectedJob.value)) startJobPolling();
      notice.value = '任务取消请求已提交';
    } catch (error) {
      notice.value = error.message || '任务取消失败';
    }
  }

  async function retryJob(job = selectedJob.value) {
    if (!job || !['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'].includes(String(job.state || '').toUpperCase())) return;
    try {
      const isBackupJob = ['ORDER_BACKUP', 'MANUAL_BACKUP'].includes(String(job.jobType || '').toUpperCase());
      const endpoint = isBackupJob ? '/admin/backup/jobs/' + encodeURIComponent(job.id) + '/retry-failed' : '/admin/jobs/' + encodeURIComponent(job.id) + '/retry';
      const result = await api(endpoint, {
        method: 'POST',
        headers: { 'Idempotency-Key': `job-retry-${job.id}` },
        body: JSON.stringify({ reason: '管理员重试' }),
      });
      await openJob(extractJobId(result) || job.id, '任务重试已提交');
    } catch (error) {
      notice.value = error.message || '任务重试失败';
    }
  }

  async function degradedBackup(row) {
    const job = selectedJob.value;
    if (!job || !row?.degradedBackupEligible || !row?.sourceOrderId) return;
    try {
      const result = await api('/admin/backup/jobs/' + encodeURIComponent(job.id)
        + '/orders/' + encodeURIComponent(row.sourceOrderId) + '/degraded-backup', {
        method: 'POST',
        headers: { 'Idempotency-Key': `degraded-backup-${job.id}-${row.sourceOrderId}` },
      });
      await openJob(extractJobId(result), '降级备份任务已提交');
    } catch (error) {
      notice.value = error.message || '降级备份任务创建失败';
    }
  }

  async function loadCold() {
    try {
      const [result, disks] = await Promise.all([api('/admin/cold-batches'), api('/admin/cold-batches/disks')]);
      coldBatches.value = result.data || [];
      coldDisks.value = disks.data || [];
      if (selectedBatchId.value) {
        selectedColdBatch.value = coldBatches.value.find((batch) => String(batch.id) === String(selectedBatchId.value)) || selectedColdBatch.value;
      }
    } catch (error) {
      notice.value = error.message || '冷备批次加载失败';
      coldDiagnostics.value = ['批次或冷备盘列表加载失败：' + (error.message || '未知错误'), '请确认本地服务和数据库连接正常后重试。'];
    }
  }

  function selectColdBatch(batch) {
    selectedColdBatch.value = batch;
    selectedBatchId.value = String(batch.id);
    if (batch.status === 'VERIFIED') coldDiagnostics.value = ['批次已通过校验，可以执行整批热备清理。'];
    else if (batch.status === 'COMPLETED') coldDiagnostics.value = ['批次写出已完成，尚未校验；请先执行“校验批次”。'];
    else if (batch.status === 'WRITING') coldDiagnostics.value = ['批次正在写出冷备盘，暂不能校验或清理热备。'];
    else coldDiagnostics.value = ['批次当前状态：' + (batch.status || '未知')];
  }

  async function verifyColdBatch(batch = selectedColdBatch.value) {
    if (!batch || String(batch.status) !== 'COMPLETED') {
      notice.value = '只有写出完成的冷备批次才能校验';
      return;
    }
    if (!confirm('确认校验该冷备批次？校验未通过时不会允许删除热备文件。')) return;
    coldBusy.value = true;
    try {
      const result = await api('/admin/cold-batches/' + encodeURIComponent(batch.id) + '/verify', { method: 'POST', body: '{}' });
      selectedColdBatch.value = result.data || batch;
      notice.value = '冷备批次校验通过';
      coldDiagnostics.value = ['批次校验通过，可按整批执行热备清理。'];
      await loadCold();
    } catch (error) {
      notice.value = error.message || '冷备批次校验失败';
      coldDiagnostics.value = ['校验失败：' + (error.message || '未知错误'), '请确认冷备压缩包仍在指定目录且完整标记、校验摘要可用。'];
    } finally {
      coldBusy.value = false;
    }
  }

  async function cleanupColdBatch(batch = selectedColdBatch.value) {
    if (!batch || String(batch.status) !== 'VERIFIED') {
      notice.value = '只有已校验批次才能清理热备文件';
      return;
    }
    const packages = hotCleanupPackages.value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
    if (!packages.length) {
      notice.value = '请填写该批次全部热备订单包相对路径，系统只允许整批清理';
      return;
    }
    if (!confirm('确认清理该冷备批次对应的全部热备文件？此操作不可撤销，系统不会支持单订单删除。')) return;
    coldBusy.value = true;
    try {
      const result = await api('/admin/cold-batches/' + encodeURIComponent(batch.id) + '/hot-cleanup-confirm', {
        method: 'POST',
        body: JSON.stringify({ packageKeys: packages }),
      });
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, '整批热备清理任务已提交，可在当前页继续操作');
      else notice.value = '整批热备清理任务已提交';
      await loadCold();
    } catch (error) {
      notice.value = error.message || '整批热备清理提交失败';
      coldDiagnostics.value = ['热备清理未提交：' + (error.message || '未知错误'), '请确认批次状态仍为 VERIFIED，且提交的是完整订单包清单。'];
    } finally {
      coldBusy.value = false;
    }
  }

  async function createColdBatch() {
    if (!coldDiskId.value || !coldFrom.value || !coldTo.value) {
      notice.value = '请选择目标冷备盘和订单创建时间范围';
      return;
    }
    try {
      const result = await api('/admin/cold-batches', {
        method: 'POST',
        body: JSON.stringify({
          diskId: Number(coldDiskId.value),
          from: coldFrom.value,
          to: coldTo.value,
        }),
      });
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, '冷备任务已提交');
      else notice.value = '冷备批次已创建';
      await loadCold();
    } catch (error) {
      notice.value = error.message || '冷备批次创建失败';
    }
  }

  async function registerColdDisk() {
    if (!diskCode.value.trim() || !diskLabel.value.trim()) {
      notice.value = '请输入冷备盘标识和标签';
      return;
    }
    try {
      await api('/admin/cold-batches/disks', {
        method: 'POST',
        body: JSON.stringify({
          diskCode: diskCode.value.trim(),
          label: diskLabel.value.trim(),
          status: 'REGISTERED',
        }),
      });
      diskCode.value = '';
      diskLabel.value = '';
      notice.value = '冷备盘登记成功';
      await loadCold();
    } catch (error) {
      notice.value = error.message || '冷备盘登记失败';
    }
  }

  async function writeCold() {
    if (!selectedBatchId.value || !coldRoot.value.trim() || !writePackages.value.trim()) {
      notice.value = '请选择批次并填写冷备盘目录、订单包相对路径';
      return;
    }
    try {
      const result = await api('/admin/cold-batches/' + encodeURIComponent(selectedBatchId.value) + '/write', {
        method: 'POST',
        body: JSON.stringify({
          coldRoot: coldRoot.value.trim(),
          orderPackages: writePackages.value
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean),
        }),
      });
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, '冷备写出任务已提交');
      else notice.value = '冷备写出完成';
      await loadCold();
    } catch (error) {
      notice.value = error.message;
    }
  }

  async function scanColdImports() {
    try {
      const result = await api('/admin/cold-imports/scan', {
        method: 'POST',
        body: '{}',
      });
      coldArchives.value = result.data || [];
      coldDiagnostics.value = [coldImportDiagnostic(coldArchives.value)];
    } catch (error) {
      notice.value = error.message || '冷备导入区扫描失败';
      coldDiagnostics.value = ['扫描失败：' + (error.message || '未知错误'), '请确认导入区路径已配置、磁盘已挂载且本地服务账号有读取权限。'];
    }
  }

  async function restoreColdImport() {
    if (!coldArchive.value) {
      notice.value = '请选择冷备压缩包';
      return;
    }
    if (!coldArchives.value.includes(coldArchive.value)) {
      notice.value = '当前压缩包不在最近一次扫描结果中，请重新扫描';
      return;
    }
    try {
      const ids = coldOrderIds.value
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean);
      const result = await api('/admin/cold-imports/' + encodeURIComponent(coldArchive.value) + '/restore', {
        method: 'POST',
        body: JSON.stringify({ orderIds: ids }),
      });
      const jobId = extractJobId(result);
      if (jobId) await openJob(jobId, '冷备恢复任务已提交');
      else notice.value = '冷备文件已恢复到热备区';
    } catch (error) {
      notice.value = error.message || '冷备恢复提交失败';
      coldDiagnostics.value = ['冷备导入未提交：' + (error.message || '未知错误'), '系统会校验完成标记、路径安全和解压容量，失败时不会留下中间文件。'];
    }
  }

  async function loadConfigs() {
    try {
      const [categoryResult, configResult] = await Promise.all([api('/admin/configs/categories'), api('/admin/configs')]);
      configCategories.value = categoryResult.data || [];
      configs.value = configResult.data || [];
      if (!selectedConfigCategory.value || !configCategories.value.some((item) => item.code === selectedConfigCategory.value)) {
        selectedConfigCategory.value = configCategories.value[0]?.code || '';
      }
    } catch (error) {
      notice.value = error.message;
    }
  }

  async function loadOverview() {
    try {
      const result = await api('/admin/overview');
      overview.value = result.data || null;
    } catch (error) {
      notice.value = error.message || '概览加载失败';
    }
  }

  async function loadAccounts(pageNumber = accountPage.value) {
    try {
      const [accountsResult, syncResult] = await Promise.all([api(`/admin/accounts?current=${encodeURIComponent(pageNumber)}&size=${encodeURIComponent(accountSize.value)}`), api('/admin/accounts/sync/status')]);
      const pageData = normalizePage(accountsResult.data);
      accounts.value = pageData.records;
      accountPage.value = pageData.current;
      accountSize.value = pageData.size;
      accountTotal.value = pageData.total;
      accountPages.value = pageData.pages;
      accountSyncStatus.value = syncResult.data || null;
    } catch (error) {
      notice.value = error.message || '账号列表加载失败';
    }
  }

  function changeAccountPage(pageNumber) {
    if (pageNumber < 1 || (accountPages.value > 0 && pageNumber > accountPages.value)) return;
    loadAccounts(pageNumber);
  }

  function changeAccountSize(size) {
    accountSize.value = size;
    accountPage.value = 1;
    loadAccounts(1);
  }

  async function syncAccountsNow() {
    if (accountSyncRunning.value) return;
    accountSyncRunning.value = true;
    try {
      const result = await api('/admin/accounts/sync', { method: 'POST' });
      const status = result.data?.status;
      notice.value = status === 'SUCCEEDED' ? `账号同步完成，共处理 ${result.data?.itemCount || 0} 条` : result.data?.message || `账号同步状态：${status || '未知'}`;
      await loadAccounts();
    } catch (error) {
      notice.value = error.message || '账号同步失败';
      await loadAccounts();
    } finally {
      accountSyncRunning.value = false;
    }
  }

  async function toggleAccount(account) {
    try {
      await api('/admin/accounts/' + encodeURIComponent(account.id) + '/status', {
        method: 'PUT',
        body: JSON.stringify({
          enabledStatus: account.enabledStatus === 'ENABLED' ? 'DISABLED' : 'ENABLED',
        }),
      });
      await loadAccounts();
      notice.value = '本地账号状态已更新';
    } catch (error) {
      notice.value = error.message || '账号状态更新失败';
    }
  }

  async function resetAccountPassword(account) {
    const password = accountPassword.value[account.id];
    if (!password || password.length < 8) {
      notice.value = '本地密码至少八位';
      return;
    }
    try {
      await api('/admin/accounts/' + encodeURIComponent(account.id) + '/password', {
        method: 'PUT',
        body: JSON.stringify({ password }),
      });
      accountPassword.value[account.id] = '';
      notice.value = '本地密码已重置（不会回写线上）';
    } catch (error) {
      notice.value = error.message || '密码重置失败';
    }
  }

  function editConfig(entry) {
    configKey.value = entry.key;
    configValue.value = entry.value || '';
    configVersion.value = entry.version;
    selectedConfigCategory.value = entry.categoryCode || selectedConfigCategory.value;
  }

  async function saveConfig() {
    try {
      const result = await api('/admin/configs/' + encodeURIComponent(configKey.value), {
        method: 'PUT',
        body: JSON.stringify({
          value: configValue.value,
          expectedVersion: Number(configVersion.value),
        }),
      });
      editConfig(result.data);
      await loadConfigs();
      notice.value = '配置已保存';
    } catch (error) {
      notice.value = error.message;
    }
  }

  function logout() {
    closeJobDetail();
    auth.logout();
    route.navigate('orders');
  }

  function handleAuthExpired() {
    closeJobDetail();
    auth.handleAuthExpired();
    route.navigate('orders');
    notice.value = '登录已失效，请重新登录';
  }

  onMounted(async () => {
    window.addEventListener('backup-auth-expired', handleAuthExpired);
    window.addEventListener('hashchange', handleHashChange);
    if (!isAdmin.value && page.value !== 'orders') route.navigate('orders');
    if (token.value) await loadCurrentPage();
  });
  onUnmounted(() => {
    closeJobDetail();
    stopSupplementPolling();
    window.removeEventListener('backup-auth-expired', handleAuthExpired);
    window.removeEventListener('hashchange', handleHashChange);
  });

  function handleHashChange() {
    route.onHashChange();
    if (!isAdmin.value && page.value !== 'orders') route.navigate('orders');
  }

  function navigate(target) {
    if (!isAdmin.value && target !== 'orders') {
      route.navigate('orders');
      return;
    }
    if (target !== 'backup' && jobDetailVisible.value) closeJobDetail();
    route.navigate(target);
    if (target === 'orders') {
      Promise.all([loadOrderFilterOptions(), loadOrders(1)]);
      return;
    }
    if (target === 'backup') loadJobs();
    if (target === 'cold-storage') loadColdStorage();
    if (target === 'restore') return;
    if (target === 'overview') {
      loadOverview();
    }
    if (target === 'accounts') loadAccounts();
    if (target === 'configs') loadConfigs();
    if (target === 'storage' || target === 'storage-volumes') {
      loadStorageVolumes();
      return;
    }
  }

  /** 浏览器刷新或直接访问 hash 路由时，加载当前页面而不是固定加载订单。 */
  async function loadCurrentPage() {
    switch (page.value) {
      case 'accounts':
        await loadAccounts();
        break;
      case 'backup':
        await loadJobs();
        if (selectedJobId.value && selectedJob.value && !isTerminalJob(selectedJob.value)) startJobPolling();
        break;
      case 'restore':
        break;
      case 'cold-storage':
        await loadColdStorage();
        break;
      case 'overview':
        await loadOverview();
        break;
      case 'configs':
        await loadConfigs();
        break;
      case 'storage':
      case 'storage-volumes':
        await loadStorageVolumes();
        break;
      default:
        await Promise.all([loadOrderFilterOptions(), loadOrders()]);
    }
  }

  return {
    token,
    role,
    isAdmin,
    page,
    username,
    password,
    orders,
    selectedOrder,
    selectedIds,
    hasUnavailableSelection,
    assets,
    supplements,
    supplementUpload,
    notice,
    keyword,
    createdFrom,
    createdTo,
    businessType,
    status,
    needsPhysicalDelivery,
    cloudRollup,
    hotRollup,
    coldRollup,
    orderFilterOptions,
    orderPage,
    orderSize,
    orderTotal,
    orderPages,
    storagePath,
    storage,
    storageVolumes,
    storageCandidates,
    coldStorageArchives,
    selectedStorageArchive,
    coldStorageLoading,
    days,
    cleanupResult,
    jobs,
    jobPage,
    jobSize,
    jobTotal,
    selectedJobId,
    selectedJob,
    jobDetailVisible,
    jobDetailTab,
    jobOrders,
    jobOrderPage,
    jobOrderSize,
    jobOrderTotal,
    jobFiles,
    jobFilePage,
    jobFileSize,
    jobFileTotal,
    jobFileLoading,
    jobLoading,
    selectedJobOrder,
    jobOrderDetailVisible,
    jobOrderDetailTab,
    jobOrderFiles,
    jobOrderFilePage,
    jobOrderFileSize,
    jobOrderFileTotal,
    jobOrderFileLoading,
    jobProgress,
    overview,
    jobLogRecords,
    jobLogCurrent,
    jobLogSize,
    jobLogTotal,
    jobLogLoading,
    orderLogRecords,
    orderLogCurrent,
    orderLogSize,
    orderLogTotal,
    orderLogLoading,
    scheduledBackupRunning,
    coldBatches,
    coldDisks,
    coldDiskId,
    coldFrom,
    coldTo,
    diskCode,
    diskLabel,
    coldArchives,
    coldArchive,
    coldOrderIds,
    coldRoot,
    writePackages,
    hotCleanupPackages,
    selectedBatchId,
    selectedColdBatch,
    coldBusy,
    coldDiagnostics,
    configs,
    configCategories,
    selectedConfigCategory,
    configKey,
    configValue,
    configVersion,
    accounts,
    accountPage,
    accountSize,
    accountTotal,
    accountPages,
    accountPassword,
    accountSyncStatus,
    accountSyncRunning,
    createColdBatch,
    registerColdDisk,
    loadAccounts,
    changeAccountPage,
    changeAccountSize,
    syncAccountsNow,
    toggleAccount,
    resetAccountPassword,
    selfPassword,
    changeSelfPassword,
    login,
    loadOrders,
    loadOrderFilterOptions,
    resetOrderFilters,
    changeOrderPage,
    changeOrderSize,
    selectOrder,
    toggleSelected,
    downloadOrder,
    uploadSupplement,
    downloadSupplement,
    restoreSelectedOrder,
    restoreBatch,
    probeStorage,
    loadStorageVolumes,
    loadStorageVolumeDetail,
    scanStorageVolumes,
    registerStorageVolume,
    changeStorageVolumeState,
    previewStorageArchive,
    startStorageArchive,
    loadStorageArchiveDetail,
    abandonStorageArchive,
    loadColdStorage,
    openColdStorageArchive,
    scanColdMountCandidates,
    confirmColdVolumeMounted,
    prepareColdVolumeOffline,
    loadOverview,
    dryCleanup,
    executeCleanup,
    loadJobs,
    runScheduledBackupNow,
    changeJobPage,
    changeJobSize,
    loadJobSummary,
    loadJobOrders,
    loadJobDetails,
    loadJobFiles,
    loadOrderFiles,
    loadTaskLogs,
    loadOrderLogs,
    closeJobDetail,
    changeJobDetailTab,
    openJobOrderDetail,
    closeJobOrderDetail,
    changeJobOrderDetailTab,
    changeJobOrderPage,
    changeJobOrderSize,
    changeJobFilePage,
    changeJobFileSize,
    changeJobOrderFilePage,
    changeJobOrderFileSize,
    openBackupTaskLogs,
    openBackupOrderLogs,
    changeBackupLogPage,
    changeBackupLogSize,
    changeOrderLogPage,
    changeOrderLogSize,
    selectJob,
    cancelJob,
    retryJob,
    degradedBackup,
    canRetryFailedOrders,
    loadCold,
    selectColdBatch,
    verifyColdBatch,
    cleanupColdBatch,
    writeCold,
    scanColdImports,
    restoreColdImport,
    loadConfigs,
    editConfig,
    saveConfig,
    logout,
    navigate,
    storageState,
    taskFailureSummary,
  };
}
