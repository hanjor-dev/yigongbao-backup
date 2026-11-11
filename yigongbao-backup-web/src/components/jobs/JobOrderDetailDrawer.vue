<script setup>
import { formatJobOrderCreatedAt, jobOrderPatientName, storageVolumeView } from '../../taskCenter.js';
import { useBackupContext } from '../../composables/useBackupContext.js';
import OrderBusinessFields from '../orders/OrderBusinessFields.vue';
import JobFileTable from './JobFileTable.vue';
import ExecutionLogPanel from './ExecutionLogPanel.vue';

const {
  selectedJob, selectedJobOrder, jobOrderDetailVisible, jobOrderDetailTab,
  jobOrderFiles, jobOrderFilePage, jobOrderFileSize, jobOrderFileTotal, jobOrderFileLoading,
  orderLogRecords, orderLogCurrent, orderLogSize, orderLogTotal, orderLogLoading,
  closeJobOrderDetail, changeJobOrderDetailTab, changeJobOrderFilePage, changeJobOrderFileSize,
  changeOrderLogPage, changeOrderLogSize, degradedBackup,
} = useBackupContext();

const volume = (row) => storageVolumeView(
  row,
  row?.storageVolumeId || row?.sourceStorageVolumeId || selectedJob.value?.targetStorageVolumeId,
);
</script>

<template>
  <el-drawer
    v-model="jobOrderDetailVisible"
    class="job-order-drawer"
    direction="rtl"
    size="min(860px, 100vw)"
    append-to-body
    :close-on-click-modal="false"
    :destroy-on-close="false"
    @closed="closeJobOrderDetail"
  >
    <template #header>
      <div v-if="selectedJobOrder" class="drawer-heading">
        <span>订单备份明细</span>
        <h3>{{ selectedJobOrder.orderCode || selectedJobOrder.sourceOrderId }}</h3>
      </div>
    </template>

    <div v-if="selectedJobOrder" class="drawer-body">
      <el-tabs v-model="jobOrderDetailTab" @tab-change="changeJobOrderDetailTab">
        <el-tab-pane label="订单概览" name="overview">
          <div class="drawer-section-stack">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="订单号">{{ selectedJobOrder.orderCode || selectedJobOrder.sourceOrderId }}</el-descriptions-item>
              <el-descriptions-item label="患者">{{ jobOrderPatientName(selectedJobOrder) }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatJobOrderCreatedAt(selectedJobOrder.orderCreatedAt) }}</el-descriptions-item>
              <el-descriptions-item label="备份状态"><status-badge :value="selectedJobOrder.status" /></el-descriptions-item>
              <el-descriptions-item label="预计文件">{{ selectedJobOrder.expectedFileCount ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="来源订单ID">{{ selectedJobOrder.sourceOrderId }}</el-descriptions-item>
            </el-descriptions>

            <section class="evidence-card">
              <div class="section-title"><span>存储信息</span><status-badge v-if="volume(selectedJobOrder).current" :value="selectedJobOrder.currentMountStatus" :label="volume(selectedJobOrder).current" /></div>
              <dl class="storage-evidence">
                <div><dt>存储卷</dt><dd>{{ volume(selectedJobOrder).name }}</dd></div>
                <div v-if="volume(selectedJobOrder).historical"><dt>任务时</dt><dd>{{ volume(selectedJobOrder).historical }}</dd></div>
                <div><dt>相对位置</dt><dd>{{ selectedJobOrder.storageKey || selectedJobOrder.sourceStorageKey || selectedJobOrder.simulationPath || '-' }}</dd></div>
              </dl>
            </section>

            <el-alert v-if="selectedJobOrder.failureCode || selectedJobOrder.failureMessage" type="error" :closable="false" show-icon>
              {{ selectedJobOrder.failureCode || '' }} {{ selectedJobOrder.failureMessage || '' }}
            </el-alert>

            <el-collapse class="business-snapshot">
              <el-collapse-item title="订单信息（备份时）" name="business">
                <OrderBusinessFields :value="selectedJobOrder.orderBusinessJson" title="" />
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文件明细" name="files">
          <JobFileTable
            compact-identity
            :records="jobOrderFiles"
            :loading="jobOrderFileLoading"
            :current="jobOrderFilePage"
            :size="jobOrderFileSize"
            :total="jobOrderFileTotal"
            @page-change="changeJobOrderFilePage"
            @size-change="changeJobOrderFileSize"
          />
        </el-tab-pane>

        <el-tab-pane label="执行日志" name="logs">
          <ExecutionLogPanel
            :records="orderLogRecords"
            :loading="orderLogLoading"
            :current="orderLogCurrent"
            :size="orderLogSize"
            :total="orderLogTotal"
            @page-change="changeOrderLogPage"
            @size-change="changeOrderLogSize"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="closeJobOrderDetail">关闭</el-button>
        <el-button
          v-if="selectedJobOrder?.degradedBackupEligible"
          type="warning"
          plain
          @click="degradedBackup(selectedJobOrder)"
        >降级备份</el-button>
      </div>
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
.business-snapshot { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 0 14px; }
.drawer-footer { display: flex; justify-content: flex-end; gap: 10px; }
.drawer-footer .el-button + .el-button { margin-left: 0; }
@media (max-width: 640px) {
  .storage-evidence div { grid-template-columns: 1fr; gap: 3px; }
}
</style>
