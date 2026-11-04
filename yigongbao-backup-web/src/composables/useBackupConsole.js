import { ref, watch, onMounted, onUnmounted } from 'vue';
import { coldImportDiagnostic, extractJobId, isTerminalJob, jobProgress, normalizePage, storageState, supplementTaskState, taskFailureSummary } from '../taskCenter.js';
import { api, binary, saveBlob, uploadWithProgress } from '../api.js';
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
    const assets = ref([]);
    const supplements = ref([]);
    const supplementUpload = ref({ status: 'IDLE', percent: 0, message: '', fileName: '', jobId: null });
    const notice = ref('');
    const orderCode = ref('');
    const createdFrom = ref('');
    const createdTo = ref('');
    const snapshotStatus = ref('');
    const sourceStatus = ref('');
    const orgName = ref('');
    const hospitalName = ref('');
    const patientName = ref('');
    const doctorName = ref('');
    const businessType = ref('');
    const phase = ref('');
    const status = ref('');
    const cloudRollup = ref('');
    const hotRollup = ref('');
    const coldRollup = ref('');
    const storagePath = ref('');
    const storage = ref(null);
    const days = ref(180);
    const cleanupResult = ref(null);
    const jobs = ref([]);
    const jobPage = ref(1);
    const jobSize = ref(20);
    const jobTotal = ref(0);
    const selectedJobId = ref(null);
    const selectedJob = ref(null);
    const jobOrders = ref([]);
    const jobFiles = ref([]);
    const jobLoading = ref(false);
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
    const auditRows = ref([]);
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

    function stopJobPolling() {
      if (jobPollTimer) {
        clearInterval(jobPollTimer);
        jobPollTimer = null;
      }
    }

    function stopSupplementPolling() {
      if (supplementPollTimer) { clearInterval(supplementPollTimer); supplementPollTimer = null; }
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
        supplementUpload.value = { ...supplementUpload.value, status: 'FAILED', message: error.message || '补传任务状态查询失败' };
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
        if (page.value !== 'backup' || !selectedJobId.value) return;
        await loadJobDetails(selectedJobId.value, true);
      }, 3000);
    }

    async function openJob(jobId, message = '任务已提交') {
      if (!jobId) {
        notice.value = '操作已提交，但服务端未返回任务编号';
        return;
      }
      selectedJobId.value = String(jobId);
      notice.value = message;
      await loadJobDetails(selectedJobId.value);
      if (page.value === 'backup') startJobPolling();
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
        selfPassword.value = ''; notice.value = '本地密码修改成功，不会影响线上账号';
      } catch (error) { notice.value = error.message || '本地密码修改失败'; }
    }

    function selectedOrderKey() {
      // 补传接口的 orderId 使用本地快照 ID，由后端解析到稳定订单号，避免订单号恰好为纯数字时歧义。
      return selectedOrder.value?.id || '';
    }

    async function loadOrders(pageNumber = orderPage.value) {
      try {
        const params = new URLSearchParams({ current: String(pageNumber), size: String(orderSize.value) });
        if (orderCode.value.trim()) params.set('orderCode', orderCode.value.trim());
        if (createdFrom.value) params.set('createdFrom', createdFrom.value);
        if (createdTo.value) params.set('createdTo', createdTo.value);
        if (snapshotStatus.value) params.set('snapshotStatus', snapshotStatus.value);
        if (sourceStatus.value) params.set('sourceStatus', sourceStatus.value);
        if (orgName.value.trim()) params.set('orgName', orgName.value.trim());
        if (hospitalName.value.trim()) params.set('hospitalName', hospitalName.value.trim());
        if (patientName.value.trim()) params.set('patientName', patientName.value.trim());
        if (doctorName.value.trim()) params.set('doctorName', doctorName.value.trim());
        if (businessType.value.trim()) params.set('businessType', businessType.value.trim());
        if (phase.value !== '') params.set('phase', phase.value);
        if (status.value !== '') params.set('status', status.value);
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
      selectedIds.value = selectedIds.value.includes(order.id)
        ? selectedIds.value.filter((id) => id !== order.id)
        : [...selectedIds.value, order.id];
      selectOrder(order);
    }

    async function loadSelectedOrder() {
      if (!selectedOrder.value) return;
      const id = encodeURIComponent(selectedOrder.value.id);
      const key = encodeURIComponent(selectedOrderKey());
      try {
        const [assetResult, supplementResult] = await Promise.all([
          api('/orders/' + id + '/assets'),
          api('/orders/' + key + '/supplements')
        ]);
        assets.value = assetResult.data || [];
        supplements.value = supplementResult.data || [];
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
        supplementUpload.value = { status: 'UPLOADING', percent: 0, message: '正在上传文件', fileName: file.name || '', jobId: null };
        const result = await uploadWithProgress('/orders/' + encodeURIComponent(selectedOrderKey()) + '/supplements', form,
          (percent) => { supplementUpload.value = { ...supplementUpload.value, ...supplementTaskState(null, percent) }; });
        const jobId = extractJobId(result);
        if (jobId) {
          supplementUpload.value = { ...supplementUpload.value, status: 'PROCESSING', percent: 100, message: '文件已上传，后台正在处理', jobId };
          startSupplementPolling(jobId, selectedOrderKey());
        } else {
          supplementUpload.value = { ...supplementUpload.value, status: 'FAILED', message: '服务端未返回补传任务编号' };
        }
      } catch (error) {
        supplementUpload.value = { ...supplementUpload.value, status: 'FAILED', message: error.message || '补传文件失败' };
        notice.value = error.message || '补传文件失败';
      }
    }

    async function downloadSupplement(file) {
      if (!selectedOrder.value) return;
      try {
        const response = await binary('/orders/' + encodeURIComponent(selectedOrderKey())
          + '/supplements/' + encodeURIComponent(file.fileId) + '/download');
        saveBlob(await response.blob(), file.fileName || file.fileId);
      } catch (error) {
        notice.value = error.message || '补传文件下载失败';
      }
    }

    async function restoreSelectedOrder() {
      if (!selectedOrder.value || !isAdmin.value) return;
      if (selectedOrder.value.backupMode === 'METADATA_SIMULATION') {
        notice.value = '模拟备份包仅供流程验证和下载查看，禁止线上恢复'; return;
      }
      try {
        const result = await api('/admin/restores', {
          method: 'POST',
          body: JSON.stringify({ orderSnapshotId: selectedOrder.value.id })
        });
        await openJob(extractJobId(result), '线上恢复任务已提交');
      } catch (error) {
        notice.value = error.message || '线上恢复失败';
      }
    }

    async function restoreBatch() {
      if (!isAdmin.value || (!selectedIds.value.length && !createdFrom.value && !createdTo.value)) {
        notice.value = '请选择多个订单或填写创建时间范围'; return;
      }
      if (!confirm('确认批量线上恢复？线上已删除订单将自动跳过。')) return;
      try {
        const result = await api('/admin/restores/batch', {
          method: 'POST',
          body: JSON.stringify({ orderSnapshotIds: selectedIds.value.length ? selectedIds.value : [], createdFrom: selectedIds.value.length ? null : (createdFrom.value || null), createdTo: selectedIds.value.length ? null : (createdTo.value || null) })
        });
        await openJob(extractJobId(result), '批量线上恢复任务已提交');
      } catch (error) { notice.value = error.message || '批量恢复失败'; }
    }

    async function probeStorage() {
      try {
        const result = await api('/admin/storage/probe', { method: 'POST', body: JSON.stringify({ path: storagePath.value }) });
        storage.value = result.data;
      } catch (error) { notice.value = error.message; }
    }

    async function dryCleanup() {
      try {
        const result = await api('/admin/cleanup/dry-runs', { method: 'POST', body: JSON.stringify({ olderThanDays: Number(days.value) }) });
        cleanupResult.value = result.data;
        const jobId = extractJobId(result);
        if (jobId) await openJob(jobId, 'COS 清理试运行任务已提交');
      } catch (error) { notice.value = error.message; }
    }

    async function executeCleanup() {
      const taskId = cleanupResult.value?.taskId;
      if (!taskId || !confirm('确认提交 COS 清理授权执行？')) return;
      try {
        const result = await api('/admin/cleanup/permit-poll', { method: 'POST', body: JSON.stringify({ taskId }) });
        cleanupResult.value = result.data;
        const jobId = extractJobId(result);
        if (jobId) await openJob(jobId, 'COS 清理任务已提交');
        else notice.value = 'COS 清理执行结果已返回';
      } catch (error) { notice.value = error.message; }
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
      } catch (error) { notice.value = error.message; }
    }

    function changeJobPage(pageNumber) { jobPage.value = pageNumber; loadJobs(pageNumber); }
    function changeJobSize(size) { jobSize.value = size; jobPage.value = 1; loadJobs(1); }

    async function loadJobDetails(jobId = selectedJobId.value, silent = false) {
      if (!jobId) return;
      selectedJobId.value = String(jobId);
      jobLoading.value = true;
      try {
        const [jobResult, orderResult, fileResult] = await Promise.all([
          api('/admin/jobs/' + encodeURIComponent(jobId)),
          api('/admin/jobs/' + encodeURIComponent(jobId) + '/orders'),
          api('/admin/jobs/' + encodeURIComponent(jobId) + '/files')
        ]);
        selectedJob.value = jobResult.data || null;
        if (selectedJob.value?.jobType === 'CLEANUP_DRY_RUN' && selectedJob.value?.resultJson) {
          try { cleanupResult.value = JSON.parse(selectedJob.value.resultJson); }
          catch (_) { notice.value = 'COS 清理试运行结果格式异常'; }
        }
        jobOrders.value = orderResult.data || [];
        jobFiles.value = fileResult.data || [];
        if (selectedJob.value && isTerminalJob(selectedJob.value)) stopJobPolling();
      } catch (error) {
        if (error?.message?.includes('任务不存在')) {
          stopJobPolling();
          selectedJobId.value = null;
          selectedJob.value = null;
          jobOrders.value = [];
          jobFiles.value = [];
          notice.value = '任务已不存在，已清除当前任务选择';
        } else if (!silent) {
          notice.value = error.message || '任务详情加载失败';
        }
      } finally {
        jobLoading.value = false;
      }
    }

    function selectJob(job) {
      selectedJobId.value = String(job.id);
      loadJobDetails(job.id);
      startJobPolling();
    }

    async function cancelJob(job = selectedJob.value) {
      if (!job || isTerminalJob(job) || !confirm('确认取消该任务？')) return;
      try {
        await api('/admin/jobs/' + encodeURIComponent(job.id) + '/cancel', { method: 'POST', body: JSON.stringify({ reason: '管理员取消' }) });
        await loadJobDetails(job.id);
        notice.value = '任务取消请求已提交';
      } catch (error) { notice.value = error.message || '任务取消失败'; }
    }

    async function retryJob(job = selectedJob.value) {
      if (!job || !['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'].includes(String(job.state || '').toUpperCase())) return;
      try {
        const isBackupJob = ['ORDER_BACKUP', 'MANUAL_BACKUP'].includes(String(job.jobType || '').toUpperCase());
        const endpoint = isBackupJob
          ? '/admin/backup/jobs/' + encodeURIComponent(job.id) + '/retry-failed'
          : '/admin/jobs/' + encodeURIComponent(job.id) + '/retry';
        const result = await api(endpoint, {
          method: 'POST',
          headers: { 'Idempotency-Key': `job-retry-${job.id}` },
          body: JSON.stringify({ reason: '管理员重试' })
        });
        await openJob(extractJobId(result) || job.id, '任务重试已提交');
      } catch (error) { notice.value = error.message || '任务重试失败'; }
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
      } finally { coldBusy.value = false; }
    }

    async function cleanupColdBatch(batch = selectedColdBatch.value) {
      if (!batch || String(batch.status) !== 'VERIFIED') {
        notice.value = '只有已校验批次才能清理热备文件';
        return;
      }
      const packages = hotCleanupPackages.value.split(',').map((item) => item.trim()).filter(Boolean);
      if (!packages.length) {
        notice.value = '请填写该批次全部热备订单包相对路径，系统只允许整批清理';
        return;
      }
      if (!confirm('确认清理该冷备批次对应的全部热备文件？此操作不可撤销，系统不会支持单订单删除。')) return;
      coldBusy.value = true;
      try {
        const result = await api('/admin/cold-batches/' + encodeURIComponent(batch.id) + '/hot-cleanup-confirm', {
          method: 'POST', body: JSON.stringify({ packageKeys: packages })
        });
        const jobId = extractJobId(result);
        if (jobId) await openJob(jobId, '整批热备清理任务已提交，可在当前页继续操作');
        else notice.value = '整批热备清理任务已提交';
        await loadCold();
      } catch (error) {
        notice.value = error.message || '整批热备清理提交失败';
        coldDiagnostics.value = ['热备清理未提交：' + (error.message || '未知错误'), '请确认批次状态仍为 VERIFIED，且提交的是完整订单包清单。'];
      } finally { coldBusy.value = false; }
    }

    async function createColdBatch() {
      if (!coldDiskId.value || !coldFrom.value || !coldTo.value) {
        notice.value = '请选择目标冷备盘和订单创建时间范围'; return;
      }
      try {
        const result = await api('/admin/cold-batches', { method: 'POST', body: JSON.stringify({
          diskId: Number(coldDiskId.value), from: coldFrom.value, to: coldTo.value
        }) });
        const jobId = extractJobId(result);
        if (jobId) await openJob(jobId, '冷备任务已提交');
        else notice.value = '冷备批次已创建';
        await loadCold();
      } catch (error) { notice.value = error.message || '冷备批次创建失败'; }
    }

    async function registerColdDisk() {
      if (!diskCode.value.trim() || !diskLabel.value.trim()) { notice.value = '请输入冷备盘标识和标签'; return; }
      try {
        await api('/admin/cold-batches/disks', { method: 'POST', body: JSON.stringify({ diskCode: diskCode.value.trim(), label: diskLabel.value.trim(), status: 'REGISTERED' }) });
        diskCode.value = ''; diskLabel.value = ''; notice.value = '冷备盘登记成功'; await loadCold();
      } catch (error) { notice.value = error.message || '冷备盘登记失败'; }
    }

    async function writeCold() {
      if (!selectedBatchId.value || !coldRoot.value.trim() || !writePackages.value.trim()) {
        notice.value = '请选择批次并填写冷备盘目录、订单包相对路径'; return;
      }
      try {
        const result = await api('/admin/cold-batches/' + encodeURIComponent(selectedBatchId.value) + '/write', {
          method: 'POST', body: JSON.stringify({ coldRoot: coldRoot.value.trim(), orderPackages: writePackages.value.split(',').map((item) => item.trim()).filter(Boolean) })
        });
        const jobId = extractJobId(result);
        if (jobId) await openJob(jobId, '冷备写出任务已提交');
        else notice.value = '冷备写出完成';
        await loadCold();
      } catch (error) { notice.value = error.message; }
    }

    async function scanColdImports() {
      try {
        const result = await api('/admin/cold-imports/scan', { method: 'POST', body: '{}' });
        coldArchives.value = result.data || [];
        coldDiagnostics.value = [coldImportDiagnostic(coldArchives.value)];
      } catch (error) {
        notice.value = error.message || '冷备导入区扫描失败';
        coldDiagnostics.value = ['扫描失败：' + (error.message || '未知错误'), '请确认导入区路径已配置、磁盘已挂载且本地服务账号有读取权限。'];
      }
    }

    async function restoreColdImport() {
      if (!coldArchive.value) { notice.value = '请选择冷备压缩包'; return; }
      if (!coldArchives.value.includes(coldArchive.value)) { notice.value = '当前压缩包不在最近一次扫描结果中，请重新扫描'; return; }
      try {
        const ids = coldOrderIds.value.split(',').map((item) => item.trim()).filter(Boolean);
        const result = await api('/admin/cold-imports/' + encodeURIComponent(coldArchive.value) + '/restore', {
          method: 'POST', body: JSON.stringify({ orderIds: ids })
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
        const [categoryResult, configResult] = await Promise.all([
          api('/admin/configs/categories'), api('/admin/configs')
        ]);
        configCategories.value = categoryResult.data || [];
        configs.value = configResult.data || [];
        if (!selectedConfigCategory.value || !configCategories.value.some((item) => item.code === selectedConfigCategory.value)) {
          selectedConfigCategory.value = configCategories.value[0]?.code || '';
        }
      } catch (error) { notice.value = error.message; }
    }

    async function loadOverview() {
      try { const result = await api('/admin/overview'); overview.value = result.data || null; }
      catch (error) { notice.value = error.message || '概览加载失败'; }
    }

    async function loadAudit() {
      try { const result = await api('/admin/audit?limit=100'); auditRows.value = result.data || []; }
      catch (error) { notice.value = error.message || '审计日志加载失败'; }
    }

    async function loadAccounts(pageNumber = accountPage.value) {
      try {
        const [accountsResult, syncResult] = await Promise.all([
          api(`/admin/accounts?current=${encodeURIComponent(pageNumber)}&size=${encodeURIComponent(accountSize.value)}`),
          api('/admin/accounts/sync/status')
        ]);
        const pageData = normalizePage(accountsResult.data);
        accounts.value = pageData.records;
        accountPage.value = pageData.current;
        accountSize.value = pageData.size;
        accountTotal.value = pageData.total;
        accountPages.value = pageData.pages;
        accountSyncStatus.value = syncResult.data || null;
      }
      catch (error) { notice.value = error.message || '账号列表加载失败'; }
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
        notice.value = status === 'SUCCEEDED'
          ? `账号同步完成，共处理 ${result.data?.itemCount || 0} 条`
          : (result.data?.message || `账号同步状态：${status || '未知'}`);
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
          method: 'PUT', body: JSON.stringify({ enabledStatus: account.enabledStatus === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
        });
        await loadAccounts(); notice.value = '本地账号状态已更新';
      } catch (error) { notice.value = error.message || '账号状态更新失败'; }
    }

    async function resetAccountPassword(account) {
      const password = accountPassword.value[account.id];
      if (!password || password.length < 8) { notice.value = '本地密码至少八位'; return; }
      try {
        await api('/admin/accounts/' + encodeURIComponent(account.id) + '/password', {
          method: 'PUT', body: JSON.stringify({ password })
        });
        accountPassword.value[account.id] = ''; notice.value = '本地密码已重置（不会回写线上）';
      } catch (error) { notice.value = error.message || '密码重置失败'; }
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
          body: JSON.stringify({ value: configValue.value, expectedVersion: Number(configVersion.value) })
        });
        editConfig(result.data);
        await loadConfigs();
        notice.value = '配置已保存';
      } catch (error) { notice.value = error.message; }
    }

    function logout() {
      stopJobPolling();
      auth.logout();
      route.navigate('orders');
    }

    function handleAuthExpired() {
      stopJobPolling();
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
      stopJobPolling();
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
      route.navigate(target);
      if (target === 'backup') loadJobs();
      if (target === 'overview') { loadOverview(); loadAudit(); }
      if (target === 'audit') loadAudit();
      if (target === 'accounts') loadAccounts();
      if (target === 'cold') { loadCold(); scanColdImports(); }
      if (target === 'configs') loadConfigs();
    }

    /** 浏览器刷新或直接访问 hash 路由时，加载当前页面而不是固定加载订单。 */
    async function loadCurrentPage() {
      switch (page.value) {
        case 'accounts': await loadAccounts(); break;
        case 'backup':
          await loadJobs();
          if (selectedJobId.value && selectedJob.value && !isTerminalJob(selectedJob.value)) startJobPolling();
          break;
        case 'overview': await Promise.all([loadOverview(), loadAudit()]); break;
        case 'audit': await loadAudit(); break;
        case 'cold': await Promise.all([loadCold(), scanColdImports()]); break;
        case 'configs': await loadConfigs(); break;
        default: await loadOrders();
      }
    }

    return {
      token, role, isAdmin, page, username, password, orders, selectedOrder, selectedIds, assets, supplements, supplementUpload, notice,
      orderCode, createdFrom, createdTo, snapshotStatus, sourceStatus, orgName, hospitalName, patientName,
      doctorName, businessType, phase, status, cloudRollup, hotRollup, coldRollup,
      orderPage, orderSize, orderTotal, orderPages, storagePath, storage, days, cleanupResult, jobs, jobPage, jobSize, jobTotal,
      selectedJobId, selectedJob, jobOrders, jobFiles, jobLoading, jobProgress, overview, auditRows,
      coldBatches, coldDisks, coldDiskId, coldFrom, coldTo, diskCode, diskLabel, coldArchives, coldArchive, coldOrderIds, coldRoot, writePackages, hotCleanupPackages, selectedBatchId, selectedColdBatch, coldBusy, coldDiagnostics, configs, configCategories, selectedConfigCategory, configKey, configValue, configVersion, accounts, accountPage, accountSize, accountTotal, accountPages, accountPassword, accountSyncStatus, accountSyncRunning,
      createColdBatch, registerColdDisk,
      loadAccounts, changeAccountPage, changeAccountSize, syncAccountsNow, toggleAccount, resetAccountPassword, selfPassword, changeSelfPassword,
      login, loadOrders, changeOrderPage, changeOrderSize, selectOrder, toggleSelected, downloadOrder, uploadSupplement, downloadSupplement, restoreSelectedOrder, restoreBatch,
      probeStorage, loadOverview, loadAudit, dryCleanup, executeCleanup, loadJobs, changeJobPage, changeJobSize, loadJobDetails, selectJob, cancelJob, retryJob, loadCold, selectColdBatch, verifyColdBatch, cleanupColdBatch, writeCold, scanColdImports, restoreColdImport, loadConfigs, editConfig,
      saveConfig, logout, navigate, storageState, taskFailureSummary
    };

}
