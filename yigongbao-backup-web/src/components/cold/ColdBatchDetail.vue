<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { selectedColdBatch, coldBusy, hotCleanupPackages, verifyColdBatch, cleanupColdBatch } = useBackupContext();
</script>
<template>
  <el-card v-if="selectedColdBatch" shadow="never" class="inner-card mb-16"><template #header><div class="section-header"><span>批次预览：{{ selectedColdBatch.batchNo }}</span><status-badge :value="selectedColdBatch.status" /></div></template>
    <el-descriptions :column="3" border><el-descriptions-item label="热备清理"><status-badge v-if="selectedColdBatch.hotCleanupStatus" :value="selectedColdBatch.hotCleanupStatus" /><span v-else>未提交</span></el-descriptions-item><el-descriptions-item label="订单数">{{ selectedColdBatch.orderCount || 0 }}</el-descriptions-item><el-descriptions-item label="容量">{{ selectedColdBatch.totalSize || 0 }} bytes</el-descriptions-item></el-descriptions>
    <div class="detail-actions mt-16"><el-button v-if="selectedColdBatch.status === 'COMPLETED'" type="primary" plain :loading="coldBusy" @click="verifyColdBatch()">校验完整性</el-button></div>
    <el-alert v-if="selectedColdBatch.status === 'VERIFIED' && selectedColdBatch.hotCleanupStatus !== 'CLEANED'" type="warning" :closable="false" show-icon class="mt-16"><template #title>仅允许按整个冷备批次清理热备文件，不能选择单个订单。</template><el-input v-model.trim="hotCleanupPackages" placeholder="该批次全部热备订单包相对路径，逗号分隔" class="mt-8" /><el-button class="mt-8" type="danger" plain :loading="coldBusy" @click="cleanupColdBatch()">确认整批清理热备</el-button></el-alert>
  </el-card>
</template>
