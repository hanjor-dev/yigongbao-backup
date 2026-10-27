<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { selectedColdBatch, coldBusy, hotCleanupPackages, verifyColdBatch, cleanupColdBatch } = useBackupContext();
</script>
<template>
  <div v-if="selectedColdBatch" class="detail"><div class="detail-header"><h3>批次预览：{{selectedColdBatch.batchNo}}</h3><status-badge :value="selectedColdBatch.status" /></div><div class="batch-summary"><span>热备清理：{{selectedColdBatch.hotCleanupStatus || '未提交'}}</span><span>订单数：{{selectedColdBatch.orderCount || 0}}</span><span>容量：{{selectedColdBatch.totalSize || 0}} bytes</span></div><button v-if="selectedColdBatch.status==='COMPLETED'" @click="verifyColdBatch()" :disabled="coldBusy">校验完整性</button><div v-if="selectedColdBatch.status==='VERIFIED' && selectedColdBatch.hotCleanupStatus!=='CLEANED'" class="cleanup-confirm"><p class="warning">仅允许按整个冷备批次清理热备文件，不能选择单个订单。</p><input v-model.trim="hotCleanupPackages" placeholder="该批次全部热备订单包相对路径，逗号分隔" /><button @click="cleanupColdBatch()" :disabled="coldBusy">确认整批清理热备</button></div></div>
</template>
