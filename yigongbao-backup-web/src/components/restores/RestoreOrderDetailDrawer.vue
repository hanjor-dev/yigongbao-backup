<script setup>
import { formatJobOrderCreatedAt, jobOrderPatientName, orderBusinessFields, storageVolumeView } from '../../taskCenter.js';
import ExecutionLogPanel from '../jobs/ExecutionLogPanel.vue';
import OrderBusinessFields from '../orders/OrderBusinessFields.vue';

const props = defineProps({
  modelValue: Boolean,
  order: Object,
  files: { type: Array, default: () => [] },
  fileCurrent: { type: Number, default: 1 },
  fileSize: { type: Number, default: 20 },
  fileTotal: { type: Number, default: 0 },
  fileLoading: Boolean,
  logs: { type: Array, default: () => [] },
  logCurrent: { type: Number, default: 1 },
  logSize: { type: Number, default: 50 },
  logTotal: { type: Number, default: 0 },
  logLoading: Boolean,
  detailTab: { type: String, default: 'overview' }
});

const emit = defineEmits([
  'update:modelValue', 'close', 'tab-change',
  'file-page-change', 'file-size-change', 'log-page-change', 'log-size-change'
]);

const bytes = value => {
  const n = Number(value || 0);
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
};
const business = row => orderBusinessFields(row?.orderBusinessJson);
const displayFileName = row => {
  const leaf = String(row.objectKey || '').split('/').filter(Boolean).pop() || '';
  const prefix = `${row.assetIdentity || ''}-`;
  return leaf.startsWith(prefix) ? leaf.slice(prefix.length) : (leaf || row.assetIdentity || '-');
};
const volume = (row, fallback = null) => storageVolumeView(row, fallback);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    class="restore-order-drawer"
    direction="rtl"
    size="min(860px, 100vw)"
    append-to-body
    :close-on-click-modal="false"
    :destroy-on-close="false"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="emit('close')"
  >
    <template #header>
      <div v-if="order" class="drawer-heading">
        <span>订单恢复明细</span>
        <h3>{{ order.orderCode || order.sourceOrderId }}</h3>
      </div>
    </template>

    <div v-if="order" class="drawer-body">
      <el-tabs :model-value="detailTab" @tab-change="emit('tab-change', $event)">
        <el-tab-pane label="订单概览" name="overview">
          <div class="drawer-section-stack">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="订单号">{{ order.orderCode || order.sourceOrderId }}</el-descriptions-item>
              <el-descriptions-item label="患者">{{ jobOrderPatientName(order) }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatJobOrderCreatedAt(order.orderCreatedAt) }}</el-descriptions-item>
              <el-descriptions-item label="业务状态"><status-badge :value="business(order).statusName || order.sourceStatus" /></el-descriptions-item>
              <el-descriptions-item label="恢复状态"><status-badge :value="order.status" /></el-descriptions-item>
              <el-descriptions-item label="来源订单 ID">{{ order.sourceOrderId }}</el-descriptions-item>
            </el-descriptions>

            <section class="evidence-card">
              <div class="section-title"><span>源文件位置</span><status-badge v-if="volume(order).current" :value="order.currentMountStatus" :label="volume(order).current" /></div>
              <dl class="storage-evidence">
                <div><dt>存储卷</dt><dd>{{ volume(order, order.storageVolumeId || order.sourceStorageVolumeId).name }}</dd></div>
                <div v-if="volume(order).historical"><dt>任务时</dt><dd>{{ volume(order).historical }}</dd></div>
                <div><dt>相对位置</dt><dd>{{ order.storageKey || order.sourceStorageKey || '-' }}</dd></div>
              </dl>
            </section>

            <el-alert v-if="order.failureCode || order.failureMessage" type="error" :closable="false" show-icon>
              {{ order.failureCode || '' }} {{ order.failureMessage || '' }}
            </el-alert>

            <el-collapse class="business-snapshot">
              <el-collapse-item title="订单信息（恢复时快照）" name="business">
                <OrderBusinessFields :value="order.orderBusinessJson" title="" />
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文件明细" name="files">
          <el-table v-loading="fileLoading" :data="files" stripe border empty-text="该历史任务未记录文件级上传明细">
            <el-table-column label="文件名" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ displayFileName(row) }}</template></el-table-column>
            <el-table-column prop="assetIdentity" label="资产 ID" min-width="170" show-overflow-tooltip />
            <el-table-column label="源存储卷 / 相对位置" min-width="260"><template #default="{ row }"><div>{{ volume(row, row.storageVolumeId).name }}<span v-if="volume(row).current"> · 当前 {{ volume(row).current }}</span></div><small v-if="volume(row).historical" class="muted">{{ volume(row).historical }}</small><small v-if="row.storageKey" class="storage-key">{{ row.storageKey }}</small></template></el-table-column>
            <el-table-column label="文件容量" width="130"><template #default="{ row }">{{ bytes(row.bytesTotal) }}</template></el-table-column>
            <el-table-column label="已上传" width="130"><template #default="{ row }">{{ bytes(row.bytesDone) }}</template></el-table-column>
            <el-table-column label="上传状态" width="120"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
            <el-table-column label="失败原因" min-width="220" show-overflow-tooltip><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
          </el-table>
          <el-pagination v-if="fileTotal > 0" class="table-pagination" small background layout="total, sizes, prev, pager, next" :current-page="fileCurrent" :page-size="fileSize" :total="fileTotal" :page-sizes="[10,20,50,100]" @current-change="emit('file-page-change', $event)" @size-change="emit('file-size-change', $event)" />
        </el-tab-pane>

        <el-tab-pane label="执行日志" name="logs">
          <ExecutionLogPanel
            :records="logs"
            :loading="logLoading"
            :current="logCurrent"
            :size="logSize"
            :total="logTotal"
            @page-change="emit('log-page-change', $event)"
            @size-change="emit('log-size-change', $event)"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <div class="drawer-footer"><el-button @click="emit('update:modelValue', false)">关闭</el-button></div>
    </template>
  </el-drawer>
</template>

<style scoped>
.drawer-heading span { color: var(--el-text-color-secondary); font-size: 12px; }
.drawer-heading h3 { margin: 4px 0 0; color: var(--el-text-color-primary); font-size: 20px; }
.drawer-body { min-height: 100%; }
.drawer-section-stack { display: grid; gap: 18px; }
.evidence-card { padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-extra-light); }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; font-weight: 700; }
.storage-evidence { display: grid; gap: 10px; margin: 0; }
.storage-evidence div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 12px; }
.storage-evidence dt { color: var(--el-text-color-secondary); }
.storage-evidence dd { margin: 0; overflow-wrap: anywhere; color: var(--el-text-color-regular); }
.storage-key { display: block; overflow-wrap: anywhere; color: var(--el-text-color-secondary); }
.business-snapshot { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 0 14px; }
.table-pagination { margin-top: 16px; }
.drawer-footer { display: flex; justify-content: flex-end; }
@media (max-width: 640px) {
  .storage-evidence div { grid-template-columns: 1fr; gap: 3px; }
}
</style>
