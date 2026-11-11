<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
import { canRetryFailedOrders, jobCapacitySummaryRows, jobFileSummaryRows, jobOrderSummaryRows, storageVolumeView } from '../../taskCenter.js';
const { jobs, jobPage, jobSize, jobTotal, jobProgress, selectJob, cancelJob, retryJob, changeJobPage, changeJobSize } = useBackupContext();
const terminalStates = ['SUCCEEDED','FAILED','PARTIAL_FAILED','CANCELLED','CANCELED'];
const jobState = (job) => job.state || job.status || '';
const displayTime = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';
const simulation = (job) => job.executionMode === 'METADATA_SIMULATION';
const statusText = (job) => simulation(job) && jobState(job) === 'SUCCEEDED' ? '模拟完成' : undefined;
const volume = row => storageVolumeView(row, row.targetStorageVolumeId ? `卷 #${row.targetStorageVolumeId}` : null);
</script>

<template>
  <el-table :data="jobs" class="data-table job-table" highlight-current-row row-key="id">
    <el-table-column label="任务编号" min-width="190"><template #default="{ row }"><div class="mono">{{ row.jobNo || ('#' + row.id) }}</div><small class="muted">ID: {{ row.id }}</small></template></el-table-column>
    <el-table-column label="创建时间" width="160"><template #default="{ row }">{{ displayTime(row.requestedAt) }}</template></el-table-column>
    <el-table-column label="存储卷" width="180">
      <template #default="{ row }">
        <div class="job-volume-stack">
          <div class="job-volume-name">{{ volume(row).name }}</div>
          <div v-if="volume(row).current" class="job-volume-line">当前：{{ volume(row).current }}</div>
          <div v-if="volume(row).historical" class="job-volume-line">{{ volume(row).historical }}</div>
          <div v-if="row.targetStorageVolumeId" class="job-volume-line job-volume-id">ID: {{ row.targetStorageVolumeId }}</div>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="模式/状态" width="108"><template #default="{ row }"><div class="job-status-stack"><el-tag v-if="simulation(row)" type="warning" size="small">元数据模拟</el-tag><status-badge :value="row.cancelRequested ? 'CANCELLED' : jobState(row)" :label="row.cancelRequested ? '取消中' : statusText(row)" /></div></template></el-table-column>
    <el-table-column label="进度" width="128"><template #default="{ row }"><el-progress :percentage="jobProgress(row)" :stroke-width="7" /></template></el-table-column>
    <el-table-column label="订单" width="86"><template #default="{ row }"><div class="job-summary-stack"><div v-for="item in jobOrderSummaryRows(row)" :key="item.text" class="job-summary-row" :class="`is-${item.tone}`">{{ item.text }}</div></div></template></el-table-column>
    <el-table-column label="文件" width="112"><template #default="{ row }"><div class="job-summary-stack"><div v-for="item in jobFileSummaryRows(row)" :key="item.text" class="job-summary-row" :class="`is-${item.tone}`">{{ item.text }}</div></div></template></el-table-column>
    <el-table-column label="容量（压缩前 / ZIP）" width="168"><template #default="{ row }"><div class="job-capacity-stack"><div v-for="item in jobCapacitySummaryRows(row)" :key="item">{{ item }}</div></div></template></el-table-column>
    <el-table-column label="失败原因" min-width="220" show-overflow-tooltip><template #default="{ row }"><span class="error-text">{{ row.failureCode || '-' }} {{ row.failureEvidence || '' }}</span></template></el-table-column>
    <el-table-column label="操作" width="205" fixed="right"><template #default="{ row }"><el-button size="small" @click="selectJob(row)">详情</el-button><el-button v-if="!terminalStates.includes(jobState(row)) && !row.cancelRequested" size="small" type="warning" plain @click="cancelJob(row)">取消</el-button><el-button v-if="canRetryFailedOrders(row)" size="small" type="primary" plain @click="retryJob(row)">重试失败订单</el-button></template></el-table-column>
    <template #empty><el-empty description="暂无备份任务" /></template>
  </el-table>
  <el-pagination v-if="jobTotal > 0" class="table-pagination" background layout="total, sizes, prev, pager, next"
    :current-page="jobPage" :page-size="jobSize" :total="jobTotal" :page-sizes="[10, 20, 50, 100]"
    @current-change="changeJobPage" @size-change="changeJobSize" />
</template>

<style scoped>
.job-volume-stack,
.job-status-stack,
.job-summary-stack,
.job-capacity-stack {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.job-volume-name {
  color: var(--navy-2);
  font-weight: 700;
  line-height: 1.4;
}

.job-volume-line {
  width: 100%;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.job-volume-id {
  font-family: ui-monospace, Consolas, monospace;
  font-variant-numeric: tabular-nums;
}

.job-status-stack {
  gap: 6px;
}

.job-summary-stack {
  width: 100%;
  gap: 2px;
}

.job-summary-row {
  color: var(--ink);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  line-height: 1.5;
  white-space: nowrap;
}

.job-summary-row.is-success {
  color: var(--el-color-success-dark-2);
}

.job-summary-row.is-danger {
  color: var(--el-color-danger);
}

.job-summary-row.is-muted {
  color: var(--muted);
  white-space: normal;
}

.job-capacity-stack {
  color: var(--ink);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  line-height: 1.65;
}
</style>
