<script setup>
import { formatJobOrderCreatedAt, jobOrderPatientName, orderBusinessFields, storageVolumeView } from '../../taskCenter.js';
import ExecutionLogPanel from '../jobs/ExecutionLogPanel.vue';
import RestoreOrderDetailDrawer from './RestoreOrderDetailDrawer.vue';
import { statusLabel } from '../StatusBadge.js';

defineProps({
  modelValue: Boolean,
  task: Object,
  orders: { type: Array, default: () => [] },
  orderCurrent: { type: Number, default: 1 },
  orderSize: { type: Number, default: 20 },
  orderTotal: { type: Number, default: 0 },
  progress: { type: Number, default: 0 },
  loading: Boolean,
  detailTab: { type: String, default: 'orders' },
  taskLogs: { type: Array, default: () => [] },
  taskLogCurrent: { type: Number, default: 1 },
  taskLogSize: { type: Number, default: 50 },
  taskLogTotal: { type: Number, default: 0 },
  taskLogLoading: Boolean,
  selectedOrder: Object,
  orderDetailVisible: Boolean,
  orderDetailTab: { type: String, default: 'overview' },
  orderFiles: { type: Array, default: () => [] },
  orderFileCurrent: { type: Number, default: 1 },
  orderFileSize: { type: Number, default: 20 },
  orderFileTotal: { type: Number, default: 0 },
  orderFileLoading: Boolean,
  orderLogs: { type: Array, default: () => [] },
  orderLogCurrent: { type: Number, default: 1 },
  orderLogSize: { type: Number, default: 50 },
  orderLogTotal: { type: Number, default: 0 },
  orderLogLoading: Boolean
});

const emit = defineEmits([
  'update:modelValue', 'close', 'cancel', 'retry', 'tab-change',
  'page-change', 'size-change', 'open-order-detail',
  'update:order-detail-visible', 'close-order-detail', 'order-tab-change',
  'file-page-change', 'file-size-change', 'task-log-page-change', 'task-log-size-change',
  'order-log-page-change', 'order-log-size-change'
]);

const bytes = value => {
  const n = Number(value || 0);
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
};
const terminal = value => ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(String(value || '').toUpperCase());
const business = row => orderBusinessFields(row.orderBusinessJson);
const volume = (row, fallback = null) => storageVolumeView(row, fallback);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="job-detail-dialog"
    width="96vw"
    top="3vh"
    append-to-body
    :close-on-click-modal="false"
    :destroy-on-close="false"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="emit('close')"
  >
    <template #header>
      <div v-if="task" class="job-detail-header">
        <div class="job-detail-title"><span class="eyebrow">线上恢复任务详情</span><h3>任务 {{ task.id }}</h3></div>
        <div class="detail-actions">
          <status-badge :value="task.cancelRequested ? 'CANCELLED' : task.state" :label="task.cancelRequested ? '取消中' : undefined" />
          <el-button v-if="!terminal(task.state) && !task.cancelRequested" type="danger" plain @click="emit('cancel')">取消任务</el-button>
          <el-button v-if="['FAILED','PARTIAL_FAILED','CANCELLED'].includes(task.state)" type="primary" plain @click="emit('retry')">重试任务</el-button>
        </div>
      </div>
    </template>

    <div v-if="task" v-loading="loading" class="job-detail-body">
      <section class="job-summary-panel">
        <div class="progress-meta"><span>整体恢复进度</span><strong>{{ progress }}%</strong><span v-if="loading" class="muted">正在刷新…</span></div>
        <el-progress :percentage="progress" :stroke-width="10" :status="task.state === 'SUCCEEDED' ? 'success' : task.state === 'FAILED' ? 'exception' : undefined" />
        <el-descriptions :column="4" border class="job-summary-grid">
          <el-descriptions-item label="任务状态"><status-badge :value="task.state" /></el-descriptions-item>
          <el-descriptions-item label="目标订单数">{{ task.orderTotal || 0 }}</el-descriptions-item>
          <el-descriptions-item label="已上传包容量">{{ bytes(task.bytesCompleted) }}</el-descriptions-item>
          <el-descriptions-item label="冲突策略">{{ statusLabel(task.conflictPolicy) }}</el-descriptions-item>
          <el-descriptions-item label="源存储卷">
            <div class="summary-volume"><strong>{{ volume(task, task.targetStorageVolumeId).name }}</strong><span v-if="volume(task).current">当前：{{ volume(task).current }}</span><small v-if="volume(task).historical">任务时：{{ volume(task).historical }}</small></div>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="task.failureCode || task.failureEvidence" type="error" :closable="false" show-icon :title="`失败原因：${task.failureCode || ''} ${task.failureEvidence || ''}`" />
      </section>

      <el-tabs :model-value="detailTab" class="job-detail-tabs" @tab-change="emit('tab-change', $event)">
        <el-tab-pane label="订单明细" name="orders">
          <el-table :data="orders" row-key="sourceOrderId" stripe border empty-text="暂无订单恢复明细">
            <el-table-column label="订单号" width="160"><template #default="{ row }">{{ row.orderCode || row.sourceOrderId }}</template></el-table-column>
            <el-table-column label="创建时间" width="174"><template #default="{ row }">{{ formatJobOrderCreatedAt(row.orderCreatedAt) }}</template></el-table-column>
            <el-table-column label="患者" width="130" show-overflow-tooltip><template #default="{ row }">{{ jobOrderPatientName(row) }}</template></el-table-column>
            <el-table-column label="源存储卷 / 相对位置" min-width="300"><template #default="{ row }"><div class="order-volume"><strong>{{ volume(row, row.storageVolumeId || row.sourceStorageVolumeId).name }}</strong><span v-if="volume(row).current">当前：{{ volume(row).current }}</span></div><small v-if="volume(row).historical" class="table-muted">任务时：{{ volume(row).historical }}</small><small class="storage-key">{{ row.storageKey || row.sourceStorageKey || '-' }}</small></template></el-table-column>
            <el-table-column label="业务状态" width="130"><template #default="{ row }"><status-badge :value="business(row).statusName || row.sourceStatus" /></template></el-table-column>
            <el-table-column label="恢复状态" width="130"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
            <el-table-column label="失败原因" min-width="230" show-overflow-tooltip><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
            <el-table-column label="操作" width="92" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="emit('open-order-detail', row)">详情</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-if="orderTotal > 0" class="table-pagination" background layout="total, sizes, prev, pager, next" :current-page="orderCurrent" :page-size="orderSize" :total="orderTotal" :page-sizes="[10,20,50,100]" @current-change="emit('page-change', $event)" @size-change="emit('size-change', $event)" />
        </el-tab-pane>

        <el-tab-pane label="任务日志" name="logs">
          <ExecutionLogPanel
            :records="taskLogs"
            :loading="taskLogLoading"
            :current="taskLogCurrent"
            :size="taskLogSize"
            :total="taskLogTotal"
            @page-change="emit('task-log-page-change', $event)"
            @size-change="emit('task-log-size-change', $event)"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>

  <RestoreOrderDetailDrawer
    :model-value="orderDetailVisible"
    :order="selectedOrder"
    :detail-tab="orderDetailTab"
    :files="orderFiles"
    :file-current="orderFileCurrent"
    :file-size="orderFileSize"
    :file-total="orderFileTotal"
    :file-loading="orderFileLoading"
    :logs="orderLogs"
    :log-current="orderLogCurrent"
    :log-size="orderLogSize"
    :log-total="orderLogTotal"
    :log-loading="orderLogLoading"
    @update:model-value="emit('update:order-detail-visible', $event)"
    @close="emit('close-order-detail')"
    @tab-change="emit('order-tab-change', $event)"
    @file-page-change="emit('file-page-change', $event)"
    @file-size-change="emit('file-size-change', $event)"
    @log-page-change="emit('order-log-page-change', $event)"
    @log-size-change="emit('order-log-size-change', $event)"
  />
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
.table-pagination { margin-top: 16px; }
@media (max-width: 900px) {
  .job-detail-header { align-items: flex-start; flex-direction: column; gap: 12px; }
  .detail-actions { justify-content: flex-start; }
}
</style>
