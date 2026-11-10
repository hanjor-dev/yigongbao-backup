<script setup>
import { computed } from 'vue';
import { useBackupContext } from '../../composables/useBackupContext.js';
import { canRetryFailedOrders, orderBusinessFields, storageVolumeView } from '../../taskCenter.js';
import OrderBusinessFields from '../orders/OrderBusinessFields.vue';
import { statusLabel, workflowLabel } from '../StatusBadge.js';

const { selectedJob, jobOrders, jobOrderPage, jobOrderSize, jobOrderTotal, jobFiles, jobLoading, jobProgress, changeJobOrderPage, changeJobOrderSize, cancelJob, retryJob, degradedBackup, jobLogVisible, jobLogTitle, jobLogRecords, jobLogCurrent, jobLogSize, jobLogTotal, jobLogLoading, openBackupTaskLogs, openBackupOrderLogs, changeBackupLogPage, changeBackupLogSize } = useBackupContext();
const terminalStates = ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'];
const jobState = (job) => job?.state || job?.status || '';
const progress = computed(() => selectedJob.value ? jobProgress(selectedJob.value) : 0);
const business = (row) => orderBusinessFields(row.orderBusinessJson);
const simulation = computed(() => selectedJob.value?.executionMode === 'METADATA_SIMULATION');
const orderBackup = computed(() => ['ORDER_BACKUP', 'MANUAL_BACKUP', 'DEGRADED_BACKUP'].includes(selectedJob.value?.jobType));
const stateLabel = computed(() => jobState(selectedJob.value) === 'RETRYING_FAILED_ORDERS' ? '失败订单重试中'
  : simulation.value && jobState(selectedJob.value) === 'SUCCEEDED' ? '模拟完成' : statusLabel(jobState(selectedJob.value)));
const bytes = (value) => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
const logTone = level => ({ ERROR: 'is-error', WARN: 'is-warn', INFO: 'is-info' })[String(level || '').toUpperCase()] || 'is-info';
const formatTime = value => value ? String(value).replace('T', ' ').replace(/\.\d+$/, '') : '-';
const detail = value => { try { return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : (value || {}), null, 2); } catch { return value || ''; } };
const volume = (row, fallback = null) => storageVolumeView(row, fallback);
</script>

<template>
  <el-card v-if="selectedJob" class="job-detail detail" shadow="never">
    <template #header>
      <div class="detail-heading">
        <div>
          <span class="eyebrow">TASK DETAIL</span>
          <h3>任务 {{ selectedJob.id }}</h3>
        </div>
        <div class="detail-actions">
          <el-tag v-if="simulation" type="warning">元数据模拟</el-tag>
          <status-badge :value="jobState(selectedJob)" :label="stateLabel || '未知'" />
          <el-tag v-if="selectedJob.cancelRequested" type="warning">取消中</el-tag>
          <el-button v-if="orderBackup" plain @click="openBackupTaskLogs()">查看任务日志</el-button>
          <el-button v-if="!terminalStates.includes(jobState(selectedJob)) && !selectedJob.cancelRequested" type="danger" plain @click="cancelJob()">取消任务</el-button>
          <el-button v-if="canRetryFailedOrders(selectedJob)" type="primary" plain @click="retryJob()">重试失败订单</el-button>
        </div>
      </div>
    </template>

    <div class="job-progress-panel">
      <div class="progress-meta"><span>整体进度</span><strong>{{ progress }}%</strong><span v-if="jobLoading" class="muted">正在刷新…</span></div>
      <el-progress :percentage="progress" :status="jobState(selectedJob) === 'FAILED' ? 'exception' : jobState(selectedJob) === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="10" />
    </div>
    <el-descriptions v-if="orderBackup" :column="4" border class="mb-16">
      <el-descriptions-item label="执行结果"><status-badge :value="jobState(selectedJob)" :label="simulation && jobState(selectedJob) === 'SUCCEEDED' ? '模拟完成' : ''" /></el-descriptions-item>
      <el-descriptions-item label="源文件总容量（压缩前）">{{ bytes(selectedJob.expectedBytes) }}</el-descriptions-item>
      <el-descriptions-item label="ZIP实际占用容量">{{ bytes(selectedJob.packageBytes) }}</el-descriptions-item>
        <el-descriptions-item label="目标存储卷"><strong>{{ volume(selectedJob, selectedJob.targetStorageVolumeId).name }}</strong><span v-if="volume(selectedJob).current"> · 当前 {{ volume(selectedJob).current }}</span><span v-if="volume(selectedJob).historical"> · {{ volume(selectedJob).historical }}</span></el-descriptions-item>
      <template v-if="simulation">
        <el-descriptions-item label="Manifest">成功 {{ selectedJob.manifestSucceeded || 0 }} / 失败 {{ selectedJob.manifestFailed || 0 }}</el-descriptions-item>
        <el-descriptions-item label="预计文件">{{ selectedJob.expectedFileTotal || 0 }}</el-descriptions-item>
      </template>
    </el-descriptions>
    <el-alert v-if="selectedJob.failureCode" type="error" :closable="false" show-icon class="mb-16">
      失败原因：{{ selectedJob.failureCode }} {{ selectedJob.failureEvidence || '' }}
    </el-alert>

    <h4>订单明细</h4>
    <el-table :data="jobOrders" stripe border class="mb-24" empty-text="暂无订单明细">
      <el-table-column type="expand"><template #default="{ row }"><OrderBusinessFields :value="row.orderBusinessJson" /></template></el-table-column>
      <el-table-column label="订单号" min-width="180"><template #default="{ row }">{{ row.orderCode || row.sourceOrderId }}</template></el-table-column>
      <el-table-column prop="orderCreatedAt" label="创建时间" min-width="170" />
      <el-table-column label="所属机构" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ business(row).orgName || '-' }}</template></el-table-column>
      <el-table-column label="地区" min-width="130"><template #default="{ row }">{{ business(row).areaName || '-' }}</template></el-table-column>
      <el-table-column label="业务状态" width="120"><template #default="{ row }"><status-badge :value="business(row).statusName || row.sourceStatus" /></template></el-table-column>
      <el-table-column label="状态" width="140"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column label="存储卷 / 相对位置" min-width="320"><template #default="{ row }"><div>{{ volume(row, row.storageVolumeId || row.sourceStorageVolumeId || selectedJob.targetStorageVolumeId).name }}<span v-if="volume(row).current"> · 当前：{{ volume(row).current }}</span></div><small v-if="volume(row).historical" class="table-muted">{{ volume(row).historical }}</small><small v-if="row.storageKey"> · {{ row.storageKey }}</small><small v-else-if="row.sourceStorageKey"> · {{ row.sourceStorageKey }}</small><small v-else-if="row.simulationPath"> · {{ row.simulationPath }}</small></template></el-table-column>
      <el-table-column v-if="simulation" prop="expectedFileCount" label="预计文件" width="100" />
      <el-table-column v-if="simulation" label="源文件容量（压缩前）" width="160"><template #default="{ row }">{{ bytes(row.expectedBytes) }}</template></el-table-column>
      <el-table-column v-if="simulation" label="ZIP实际容量" width="130"><template #default="{ row }">{{ bytes(row.packageBytes) }}</template></el-table-column>
      <el-table-column v-if="simulation" prop="simulationPath" label="模拟压缩包" min-width="260" show-overflow-tooltip />
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
      <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button v-if="row.degradedBackupEligible" link type="warning" @click="degradedBackup(row)">降级备份</el-button></template></el-table-column>
      <el-table-column v-if="orderBackup" label="日志" width="130" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openBackupOrderLogs(row)">查看订单日志</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="jobOrderTotal > 0" class="table-pagination" background layout="total, sizes, prev, pager, next"
      :current-page="jobOrderPage" :page-size="jobOrderSize" :total="jobOrderTotal" :page-sizes="[10, 20, 50, 100]"
      @current-change="changeJobOrderPage" @size-change="changeJobOrderSize" />

    <h4>文件明细</h4>
    <el-table :data="jobFiles" stripe border empty-text="暂无文件明细">
      <el-table-column prop="sourceOrderId" label="订单" min-width="160" />
      <el-table-column prop="assetIdentity" label="资产" min-width="220" />
      <el-table-column label="步骤" width="140"><template #default="{ row }">{{ workflowLabel(row.step) }}</template></el-table-column>
      <el-table-column label="存储卷 / 相对位置" min-width="300"><template #default="{ row }"><div>{{ volume(row, row.storageVolumeId).name }}<span v-if="volume(row).current"> · 当前：{{ volume(row).current }}</span></div><small v-if="volume(row).historical" class="table-muted">{{ volume(row).historical }}</small><small v-if="row.storageKey"> · {{ row.storageKey }}</small></template></el-table-column>
      <el-table-column label="状态" width="130"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column label="进度" width="180"><template #default="{ row }">{{ row.bytesDone || 0 }} / {{ row.bytesTotal || 0 }}</template></el-table-column>
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
    </el-table>
    <el-dialog v-model="jobLogVisible" :title="jobLogTitle" width="min(980px, 92vw)" top="7vh" destroy-on-close>
      <div v-loading="jobLogLoading" class="log-console"><div v-if="!jobLogRecords.length" class="console-empty">暂无已持久化的执行日志。历史任务不会补写旧日志。</div><details v-for="entry in jobLogRecords" :key="entry.id" class="console-entry" :class="logTone(entry.logLevel)"><summary><time>{{ formatTime(entry.occurredAt) }}</time><b>[{{ statusLabel(entry.logLevel || 'INFO') }}]</b><span>[{{ workflowLabel(entry.stage || '-') }}]</span><span>{{ entry.message }}</span></summary><pre v-if="entry.detailJson">{{ detail(entry.detailJson) }}</pre></details></div>
      <template #footer><el-pagination v-if="jobLogTotal" small background layout="total, sizes, prev, pager, next" :current-page="jobLogCurrent" :page-size="jobLogSize" :total="jobLogTotal" :page-sizes="[20,50,100]" @current-change="changeBackupLogPage" @size-change="changeBackupLogSize" /></template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.log-console { min-height: 280px; max-height: 62vh; overflow: auto; padding: 14px; border-radius: 8px; background: #111827; color: #d1d5db; font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.console-entry { border-bottom: 1px solid #273244; padding: 8px 0; }.console-entry summary { cursor: pointer; display: flex; gap: 8px; align-items: baseline; }.console-entry time { color: #94a3b8; white-space: nowrap; }.console-entry b { color: #60a5fa; }.console-entry.is-warn b { color: #fbbf24; }.console-entry.is-error b { color: #f87171; }.console-entry span:last-child { color: #e5e7eb; overflow-wrap: anywhere; }.console-entry pre { margin: 8px 0 0 20px; padding: 8px; white-space: pre-wrap; overflow-wrap: anywhere; color: #a5b4fc; background: #0b1120; border-radius: 4px; }.console-empty { color: #94a3b8; padding: 32px 0; text-align: center; }
</style>
