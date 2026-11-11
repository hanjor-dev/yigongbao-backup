<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
import {
  formatJobOrderCreatedAt,
  incompleteBackupLabel,
  offlineStorageSummary,
  storageMountState,
} from '../../taskCenter.js';
import { statusLabel } from '../StatusBadge.js';
import OrderBusinessFields from './OrderBusinessFields.vue';

const {
  isAdmin,
  selectedOrder,
  assets,
  supplements,
  supplementUpload,
  closeOrderDetail,
  downloadOrder,
  uploadSupplement,
  downloadSupplement,
  restoreSelectedOrder,
  storageState,
} = useBackupContext();

function uploadFile(file) {
  uploadSupplement({ target: { files: [file.raw] } });
}
</script>

<template>
  <el-drawer
    :model-value="Boolean(selectedOrder)"
    class="order-file-detail-drawer"
    direction="rtl"
    size="min(1180px, 100vw)"
    append-to-body
    :close-on-click-modal="false"
    :destroy-on-close="false"
    @close="closeOrderDetail"
    @closed="closeOrderDetail"
  >
    <template #header>
      <div v-if="selectedOrder" class="drawer-heading">
        <span>订单文件详情</span>
        <div class="drawer-heading__title">
          <h3>{{ selectedOrder.orderCode || selectedOrder.sourceOrderId }}</h3>
          <el-tag v-if="selectedOrder.backupMode === 'METADATA_SIMULATION'" type="warning" effect="plain">模拟备份</el-tag>
          <el-tag v-if="incompleteBackupLabel(selectedOrder)" type="warning" effect="dark">文件可能不完整</el-tag>
        </div>
        <small>创建时间：{{ formatJobOrderCreatedAt(selectedOrder.orderCreatedAt) }}</small>
      </div>
    </template>

    <div v-if="selectedOrder" class="order-detail-drawer__body">
      <div class="order-detail-alerts">
        <el-alert
          v-if="selectedOrder.backupMode === 'METADATA_SIMULATION'"
          title="当前为模拟备份：ZIP 内保留 manifest 和零字节文件占位符，可下载验证，不可用于线上恢复。"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-else-if="incompleteBackupLabel(selectedOrder)"
          :title="`${incompleteBackupLabel(selectedOrder)}；已备份 ${selectedOrder.assetCount ?? assets.length} 个真实文件。`"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="!storageState(selectedOrder).canDownload"
          :title="storageState(selectedOrder).message"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-else-if="selectedOrder.storageDiagnosticReason"
          :title="`副本诊断：${selectedOrder.storageDiagnosticReason}`"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-else-if="selectedOrder.storageLocation"
          :title="`订单包位置：${selectedOrder.storageLocation.storageVolumeName || '未命名卷'} · ${statusLabel(selectedOrder.storageLocation.storageTier)} · ${statusLabel(selectedOrder.storageLocation.mountStatus)}${selectedOrder.storageLocation.archiveNote ? ' · 封存：' + selectedOrder.storageLocation.archiveNote : ''}`"
          type="info"
          show-icon
          :closable="false"
        />
      </div>

      <OrderBusinessFields :value="selectedOrder.displayJson || selectedOrder.orderRawJson" />

      <div class="order-detail-grid">
        <section class="order-detail-section">
          <h4>关联文件 <el-tag size="small" effect="plain">{{ assets.length }} 个</el-tag></h4>
          <el-empty v-if="!assets.length" description="暂无关联文件" :image-size="64" />
          <el-table v-else :data="assets" size="small">
            <el-table-column prop="originalFilename" label="文件名" min-width="180" />
            <el-table-column prop="assetType" label="类型" width="100" />
            <el-table-column label="存储卷 / 位置" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.storageVolumeName || row.storageVolumeId || '-' }}<span v-if="row.storageTier"> · {{ statusLabel(row.storageTier) }}</span></div>
                <small>{{ row.storageKey || '-' }}</small>
                <small v-if="row.storageAccessible === false">{{ row.storageUnavailableReason || '存储卷不可访问' }}</small>
                <small v-if="row.storageDiagnosticReason">副本诊断：{{ row.storageDiagnosticReason }}</small>
                <small v-if="row.storageAccessible === false && offlineStorageSummary(row)">冷备定位：{{ offlineStorageSummary(row) }}</small>
              </template>
            </el-table-column>
            <el-table-column label="存储状态" width="110">
              <template #default="{ row }"><status-badge :value="storageMountState(row)" /></template>
            </el-table-column>
          </el-table>
        </section>

        <section class="order-detail-section">
          <h4>补传工程文件 <el-tag size="small" type="info" effect="plain">仅本地</el-tag></h4>
          <el-upload :auto-upload="false" :show-file-list="false" :on-change="uploadFile">
            <el-button plain :loading="supplementUpload.status === 'UPLOADING'"><el-icon><FolderAdd /></el-icon>选择文件上传</el-button>
          </el-upload>
          <div v-if="supplementUpload.status !== 'IDLE'" class="supplement-progress">
            <div class="progress-line"><span>{{ supplementUpload.message }}</span><strong>{{ supplementUpload.percent }}%</strong></div>
            <el-progress
              :percentage="supplementUpload.percent"
              :status="supplementUpload.status === 'FAILED' ? 'exception' : supplementUpload.status === 'SUCCEEDED' ? 'success' : undefined"
              :stroke-width="8"
            />
            <small v-if="supplementUpload.jobId">任务 {{ supplementUpload.jobId }} · <status-badge :value="supplementUpload.status" /></small>
          </div>
          <div v-if="!supplements.length" class="empty-mini">尚无补传文件</div>
          <div v-for="file in supplements" :key="file.fileId" class="supplement-row">
            <div>
              <strong>{{ file.fileName }}</strong>
              <small>{{ file.size }} bytes</small>
              <small>{{ file.storageVolumeName || file.storageVolumeId || '-' }}<span v-if="file.storageTier"> · {{ statusLabel(file.storageTier) }}</span><span v-if="file.storageMountStatus"> · {{ statusLabel(file.storageMountStatus) }}</span> · {{ file.storageKey || '未记录位置' }}</small>
              <small v-if="file.storageAccessible === false">{{ file.storageUnavailableReason || '存储卷不可访问' }}</small>
            </div>
            <el-button link type="primary" :disabled="file.storageAccessible === false" @click="downloadSupplement(file)">下载</el-button>
          </div>
        </section>
      </div>
    </div>

    <template #footer>
      <div v-if="selectedOrder" class="drawer-footer">
        <el-button @click="closeOrderDetail">关闭</el-button>
        <el-button :disabled="!storageState(selectedOrder).canDownload" @click="downloadOrder()"><el-icon><Download /></el-icon>下载订单包</el-button>
        <el-button
          v-if="isAdmin"
          type="primary"
          :disabled="selectedOrder.backupMode === 'METADATA_SIMULATION' || !storageState(selectedOrder).canDownload"
          @click="restoreSelectedOrder"
        ><el-icon><Upload /></el-icon>线上恢复</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
/* finesse · component: order-file-detail-drawer · register=product
 * states: closed · content · empty · unavailable
 * tokens: inherited (Element Plus CSS variables) */
.drawer-heading { min-width: 0; }
.drawer-heading > span { color: var(--el-text-color-secondary); font-size: 12px; }
.drawer-heading__title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 4px; }
.drawer-heading h3 { margin: 0; color: var(--el-text-color-primary); font-size: 20px; }
.drawer-heading small { display: block; margin-top: 5px; color: var(--el-text-color-secondary); }
.order-detail-drawer__body { display: grid; gap: 20px; }
.order-detail-alerts { display: grid; gap: 10px; }
.order-detail-grid { display: grid; grid-template-columns: minmax(0, 3fr) minmax(300px, 2fr); gap: 24px; align-items: start; }
.order-detail-section { min-width: 0; }
.order-detail-section h4 { margin: 0 0 14px; color: var(--el-text-color-primary); }
.drawer-footer { display: flex; justify-content: flex-end; gap: 10px; }
.drawer-footer .el-button + .el-button { margin-left: 0; }
@media (max-width: 820px) {
  .order-detail-grid { grid-template-columns: 1fr; }
}
</style>
