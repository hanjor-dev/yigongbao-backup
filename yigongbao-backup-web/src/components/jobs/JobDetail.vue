<script setup>
import { computed } from 'vue';
import { useBackupContext } from '../../composables/useBackupContext.js';
import { canRetryFailedOrders, formatJobOrderCreatedAt, jobOrderPatientName, storageVolumeView } from '../../taskCenter.js';
import { statusLabel } from '../StatusBadge.js';
import JobFileTable from './JobFileTable.vue';
import ExecutionLogPanel from './ExecutionLogPanel.vue';
import JobOrderDetailDrawer from './JobOrderDetailDrawer.vue';

const {
  selectedJob, jobDetailVisible, jobDetailTab, jobOrders, jobOrderPage, jobOrderSize, jobOrderTotal,
  jobFiles, jobFilePage, jobFileSize, jobFileTotal, jobFileLoading, jobLoading, jobProgress,
  jobLogRecords, jobLogCurrent, jobLogSize, jobLogTotal, jobLogLoading,
  changeJobDetailTab, closeJobDetail, changeJobOrderPage, changeJobOrderSize,
  changeJobFilePage, changeJobFileSize, changeBackupLogPage, changeBackupLogSize,
  openJobOrderDetail, cancelJob, retryJob,
} = useBackupContext();

const terminalStates = ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'];
const jobState = (job) => job?.state || job?.status || '';
const progress = computed(() => selectedJob.value ? jobProgress(selectedJob.value) : 0);
const simulation = computed(() => selectedJob.value?.executionMode === 'METADATA_SIMULATION');
const orderBackup = computed(() => ['ORDER_BACKUP', 'MANUAL_BACKUP', 'DEGRADED_BACKUP'].includes(selectedJob.value?.jobType));
const stateLabel = computed(() => jobState(selectedJob.value) === 'RETRYING_FAILED_ORDERS' ? '失败订单重试中'
  : simulation.value && jobState(selectedJob.value) === 'SUCCEEDED' ? '模拟完成' : statusLabel(jobState(selectedJob.value)));
const bytes = (value) => {
  const n = Number(value || 0);
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
};
const volume = (row, fallback = null) => storageVolumeView(row, fallback);
</script>

<template>
  <el-dialog
    v-model="jobDetailVisible"
    class="job-detail-dialog"
    width="96vw"
    top="3vh"
    append-to-body
    :close-on-click-modal="false"
    :destroy-on-close="false"
    @closed="closeJobDetail"
  >
    <template #header>
      <div v-if="selectedJob" class="job-detail-header">
        <div class="job-detail-title">
          <span class="eyebrow">备份任务详情</span>
          <h3>任务 {{ selectedJob.id }}</h3>
        </div>
        <div class="detail-actions">
          <el-tag v-if="simulation" type="warning">元数据模拟</el-tag>
          <status-badge :value="jobState(selectedJob)" :label="stateLabel || '未知'" />
          <el-tag v-if="selectedJob.cancelRequested" type="warning">取消中</el-tag>
          <el-button v-if="orderBackup" plain @click="changeJobDetailTab('logs')">执行日志</el-button>
          <el-button v-if="!terminalStates.includes(jobState(selectedJob)) && !selectedJob.cancelRequested" type="danger" plain @click="cancelJob()">取消任务</el-button>
          <el-button v-if="canRetryFailedOrders(selectedJob)" type="primary" plain @click="retryJob()">重试失败订单</el-button>
        </div>
      </div>
    </template>

    <div v-if="selectedJob" v-loading="jobLoading" class="job-detail-body">
      <section class="job-summary-panel">
        <div class="progress-meta">
          <span>整体进度</span><strong>{{ progress }}%</strong><span v-if="jobLoading" class="muted">正在刷新…</span>
        </div>
        <el-progress :percentage="progress" :status="jobState(selectedJob) === 'FAILED' ? 'exception' : jobState(selectedJob) === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="10" />
        <el-descriptions v-if="orderBackup" :column="4" border class="job-summary-grid">
          <el-descriptions-item label="执行结果"><status-badge :value="jobState(selectedJob)" :label="simulation && jobState(selectedJob) === 'SUCCEEDED' ? '模拟完成' : ''" /></el-descriptions-item>
          <el-descriptions-item label="源文件容量">{{ bytes(selectedJob.expectedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="ZIP占用容量">{{ bytes(selectedJob.packageBytes) }}</el-descriptions-item>
          <el-descriptions-item label="目标存储卷">
            <div class="summary-volume"><strong>{{ volume(selectedJob, selectedJob.targetStorageVolumeId).name }}</strong><span v-if="volume(selectedJob).current">当前：{{ volume(selectedJob).current }}</span><small v-if="volume(selectedJob).historical">任务时：{{ volume(selectedJob).historical }}</small></div>
          </el-descriptions-item>
          <template v-if="simulation">
            <el-descriptions-item label="Manifest">成功 {{ selectedJob.manifestSucceeded || 0 }} / 失败 {{ selectedJob.manifestFailed || 0 }}</el-descriptions-item>
            <el-descriptions-item label="预计文件">{{ selectedJob.expectedFileTotal || 0 }}</el-descriptions-item>
          </template>
        </el-descriptions>
        <el-alert v-if="selectedJob.failureCode" type="error" :closable="false" show-icon>
          失败原因：{{ selectedJob.failureCode }} {{ selectedJob.failureEvidence || '' }}
        </el-alert>
      </section>

      <el-tabs v-model="jobDetailTab" class="job-detail-tabs" @tab-change="changeJobDetailTab">
        <el-tab-pane label="订单明细" name="orders">
          <el-table :data="jobOrders" stripe border empty-text="暂无订单明细">
            <el-table-column label="订单号" min-width="170"><template #default="{ row }">{{ row.orderCode || row.sourceOrderId }}</template></el-table-column>
            <el-table-column label="创建时间" width="174"><template #default="{ row }"><time class="table-time" :datetime="row.orderCreatedAt">{{ formatJobOrderCreatedAt(row.orderCreatedAt) }}</time></template></el-table-column>
            <el-table-column label="患者" width="130" show-overflow-tooltip><template #default="{ row }">{{ jobOrderPatientName(row) }}</template></el-table-column>
            <el-table-column label="状态" width="122"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
            <el-table-column label="存储卷 / 相对位置" min-width="300">
              <template #default="{ row }">
                <div class="order-volume"><strong>{{ volume(row, row.storageVolumeId || row.sourceStorageVolumeId || selectedJob.targetStorageVolumeId).name }}</strong><span v-if="volume(row).current">当前：{{ volume(row).current }}</span></div>
                <small v-if="volume(row).historical" class="table-muted">任务时：{{ volume(row).historical }}</small>
                <small v-if="row.storageKey || row.sourceStorageKey || row.simulationPath" class="storage-key">{{ row.storageKey || row.sourceStorageKey || row.simulationPath }}</small>
              </template>
            </el-table-column>
            <el-table-column v-if="simulation" prop="expectedFileCount" label="预计文件" width="100" />
            <el-table-column label="失败原因" min-width="230"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
            <el-table-column label="操作" width="92" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openJobOrderDetail(row)">详情</el-button></template></el-table-column>
          </el-table>
          <el-pagination
            v-if="jobOrderTotal > 0"
            class="table-pagination"
            background
            layout="total, sizes, prev, pager, next"
            :current-page="jobOrderPage"
            :page-size="jobOrderSize"
            :total="jobOrderTotal"
            :page-sizes="[10, 20, 50, 100]"
            @current-change="changeJobOrderPage"
            @size-change="changeJobOrderSize"
          />
        </el-tab-pane>

        <el-tab-pane label="文件明细" name="files">
          <JobFileTable
            :records="jobFiles"
            :loading="jobFileLoading"
            :current="jobFilePage"
            :size="jobFileSize"
            :total="jobFileTotal"
            @page-change="changeJobFilePage"
            @size-change="changeJobFileSize"
          />
        </el-tab-pane>

        <el-tab-pane label="执行日志" name="logs">
          <ExecutionLogPanel
            :records="jobLogRecords"
            :loading="jobLogLoading"
            :current="jobLogCurrent"
            :size="jobLogSize"
            :total="jobLogTotal"
            @page-change="changeBackupLogPage"
            @size-change="changeBackupLogSize"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
  <JobOrderDetailDrawer />
</template>

<style scoped>
.job-detail-header { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding-right: 28px; }
.job-detail-title { min-width: 0; }
.job-detail-title h3 { margin: 3px 0 0; font-size: 20px; line-height: 1.25; }
.eyebrow { color: var(--el-text-color-secondary); font-size: 12px; letter-spacing: .08em; }
.detail-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 8px; }
.detail-actions .el-button + .el-button { margin-left: 0; }
.job-detail-body { min-height: 460px; }
.job-summary-panel { display: grid; gap: 12px; padding: 2px 2px 16px; }
.progress-meta { display: flex; gap: 6px; align-items: baseline; }
.progress-meta strong { font-size: 18px; }
.progress-meta .muted { margin-left: auto; }
.job-summary-grid { margin-top: 2px; }
.summary-volume, .order-volume { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 9px; }
.summary-volume small, .storage-key { display: block; width: 100%; overflow-wrap: anywhere; color: var(--el-text-color-secondary); }
.job-detail-tabs { min-height: 360px; }
.table-time { white-space: nowrap; font-variant-numeric: tabular-nums; }
@media (max-width: 900px) {
  .job-detail-header { align-items: flex-start; flex-direction: column; gap: 12px; }
  .detail-actions { justify-content: flex-start; }
}
</style>
