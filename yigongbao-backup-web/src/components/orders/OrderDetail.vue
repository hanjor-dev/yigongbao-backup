<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { isAdmin, selectedOrder, assets, supplements, downloadOrder, uploadSupplement, downloadSupplement, restoreSelectedOrder, storageState } = useBackupContext();
</script>
<template>
  <div v-if="selectedOrder" class="detail order-detail"><div class="detail-header"><div><div class="eyebrow">SELECTED ORDER</div><h3>{{selectedOrder.orderCode || selectedOrder.sourceOrderId}}</h3></div><div><button @click="downloadOrder()" :disabled="!storageState(selectedOrder).canDownload">{{storageState(selectedOrder).canDownload ? '下载订单包' : '热备文件不可用'}}</button><button v-if="isAdmin" class="primary" @click="restoreSelectedOrder">线上恢复</button></div></div>
    <p v-if="!storageState(selectedOrder).canDownload" class="warning">{{storageState(selectedOrder).message}}</p><h4>关联文件</h4><ul class="file-list"><li v-for="a in assets" :key="a.id">{{a.originalFilename}} <span>{{a.assetType}} · {{a.backupStatus}}</span></li></ul><h4>补传工程文件（仅本地）</h4><input type="file" @change="uploadSupplement" /><ul class="file-list"><li v-for="f in supplements" :key="f.fileId">{{f.fileName}} <span>{{f.size}} bytes</span><button @click="downloadSupplement(f)">下载</button></li></ul>
  </div>
</template>
