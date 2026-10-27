<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { isAdmin, selectedOrder, assets, supplements, downloadOrder, uploadSupplement, downloadSupplement, restoreSelectedOrder, storageState } = useBackupContext();
function uploadFile(file) { uploadSupplement({ target: { files: [file.raw] } }); }
</script>
<template>
  <el-card v-if="selectedOrder" class="order-detail" shadow="never">
    <template #header><div class="detail-header"><div><div class="eyebrow">SELECTED ORDER</div><h3>{{ selectedOrder.orderCode || selectedOrder.sourceOrderId }}</h3><span class="muted">创建于 {{ selectedOrder.orderCreatedAt || '-' }}</span></div><div class="toolbar-actions"><el-button :disabled="!storageState(selectedOrder).canDownload" @click="downloadOrder()"><el-icon><Download /></el-icon>下载订单包</el-button><el-button v-if="isAdmin" type="primary" @click="restoreSelectedOrder"><el-icon><Upload /></el-icon>线上恢复</el-button></div></div></template>
    <el-alert v-if="!storageState(selectedOrder).canDownload" :title="storageState(selectedOrder).message" type="warning" show-icon :closable="false" />
    <el-row :gutter="24"><el-col :span="14"><h4>关联文件 <el-tag size="small" effect="plain">{{ assets.length }} 个</el-tag></h4><el-empty v-if="!assets.length" description="暂无关联文件" :image-size="64" /><el-table v-else :data="assets" size="small"><el-table-column prop="originalFilename" label="文件名" min-width="210" /><el-table-column prop="assetType" label="类型" width="120" /><el-table-column label="热备状态" width="120"><template #default="{ row }"><status-badge :value="row.backupStatus" /></template></el-table-column></el-table></el-col><el-col :span="10"><h4>补传工程文件 <el-tag size="small" type="info" effect="plain">仅本地</el-tag></h4><el-upload :auto-upload="false" :show-file-list="false" :on-change="uploadFile"><el-button plain><el-icon><FolderAdd /></el-icon>选择文件上传</el-button></el-upload><div v-if="!supplements.length" class="empty-mini">尚无补传文件</div><div v-for="file in supplements" :key="file.fileId" class="supplement-row"><div><strong>{{ file.fileName }}</strong><small>{{ file.size }} bytes</small></div><el-button link type="primary" @click="downloadSupplement(file)">下载</el-button></div></el-col></el-row>
  </el-card>
</template>
