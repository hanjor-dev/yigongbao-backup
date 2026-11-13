<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
import {
  formatJobOrderCreatedAt,
  incompleteBackupLabel,
  incompleteBackupTooltip,
  offlineStorageSummary,
  storageMountState,
  supplementStoragePresentation,
} from '../../taskCenter.js';
import { formatBytes } from '../../overviewModel.js';
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

function supplementState(file) {
  return supplementStoragePresentation(file);
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
          <el-tooltip
            v-if="incompleteBackupTooltip(selectedOrder)"
            :content="incompleteBackupTooltip(selectedOrder)"
            placement="top"
            :show-after="250"
          >
            <el-tag type="warning" effect="dark">文件可能不完整</el-tag>
          </el-tooltip>
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

        <section class="order-detail-section supplement-section">
          <div class="supplement-heading">
            <h4>补传工程文件</h4>
            <el-tag size="small" type="info" effect="plain">{{ supplements.length }} 个文件</el-tag>
          </div>
          <el-upload
            class="supplement-upload"
            drag
            :auto-upload="false"
            :show-file-list="false"
            :disabled="supplementUpload.status === 'UPLOADING' || supplementUpload.status === 'PROCESSING'"
            :on-change="uploadFile"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              <strong>点击或拖拽工程文件到此处</strong>
              <span>选择后立即上传并记录到当前订单</span>
            </div>
            <template #tip><div class="el-upload__tip">请保持页面开启，上传完成后可在下方查看文件状态。</div></template>
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
          <el-empty v-if="!supplements.length" description="尚无补传文件" :image-size="64" />
          <el-space v-else direction="vertical" fill :size="12" class="supplement-list">
            <el-card v-for="file in supplements" :key="file.fileId" shadow="never" class="supplement-file-card">
              <template #header>
                <el-row :gutter="12" align="middle" justify="space-between">
                  <el-col :span="16">
                    <el-space class="supplement-file-name">
                      <el-icon><Document /></el-icon>
                      <el-text tag="strong" truncated>{{ file.fileName }}</el-text>
                    </el-space>
                  </el-col>
                  <el-col :span="8" class="supplement-file-status">
                    <el-tag size="small" :type="supplementState(file).tagType" effect="light">
                      {{ supplementState(file).label }}
                    </el-tag>
                  </el-col>
                </el-row>
              </template>

              <el-descriptions :column="1" size="small" border>
                <el-descriptions-item label="文件大小">{{ formatBytes(file.size) }}</el-descriptions-item>
                <el-descriptions-item label="存储卷">{{ file.storageVolumeName || file.storageVolumeId || '未记录' }}</el-descriptions-item>
                <el-descriptions-item label="相对位置">
                  <el-text class="supplement-storage-key" truncated>{{ file.storageKey || '未记录位置' }}</el-text>
                </el-descriptions-item>
              </el-descriptions>

              <el-row class="supplement-file-actions" align="middle" justify="space-between" :gutter="12">
                <el-col :span="18"><el-text size="small" type="info">{{ supplementState(file).message }}</el-text></el-col>
                <el-col :span="6" class="supplement-download-action">
                  <el-tooltip
                    :disabled="supplementState(file).canDownload"
                    :content="supplementState(file).message"
                    placement="top"
                  >
                    <span>
                      <el-button
                        link
                        type="primary"
                        :disabled="!supplementState(file).canDownload"
                        @click="downloadSupplement(file)"
                      ><el-icon><Download /></el-icon>下载</el-button>
                    </span>
                  </el-tooltip>
                </el-col>
              </el-row>
            </el-card>
          </el-space>
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
.supplement-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.supplement-upload { width: 100%; }
.supplement-upload :deep(.el-upload), .supplement-upload :deep(.el-upload-dragger) { width: 100%; }
.supplement-upload :deep(.el-upload-dragger) { padding: 22px 16px 18px; }
.supplement-upload .el-icon--upload { margin-bottom: 8px; font-size: 38px; }
.supplement-upload .el-upload__text { display: grid; gap: 5px; }
.supplement-upload .el-upload__text strong { color: var(--el-text-color-primary); font-size: 14px; }
.supplement-upload .el-upload__text span { color: var(--el-text-color-secondary); font-size: 12px; }
.supplement-progress { margin-top: 12px; }
.supplement-list { width: 100%; margin-top: 14px; }
.supplement-file-card :deep(.el-card__header) { padding: 12px 14px; }
.supplement-file-card :deep(.el-card__body) { padding: 14px; }
.supplement-file-name { width: 100%; min-width: 0; }
.supplement-file-name .el-text { max-width: 100%; }
.supplement-file-status, .supplement-download-action { text-align: right; }
.supplement-storage-key { display: block; max-width: 260px; }
.supplement-file-actions { margin-top: 12px; }
.drawer-footer { display: flex; justify-content: flex-end; gap: 10px; }
.drawer-footer .el-button + .el-button { margin-left: 0; }
@media (max-width: 820px) {
  .order-detail-grid { grid-template-columns: 1fr; }
}
</style>
