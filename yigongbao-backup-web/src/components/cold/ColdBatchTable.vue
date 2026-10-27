<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { coldBatches, selectedColdBatch, coldBusy, selectColdBatch, verifyColdBatch } = useBackupContext();
</script>
<template>
  <el-table :data="coldBatches" stripe border highlight-current-row empty-text="暂无冷备批次" @row-click="selectColdBatch">
    <el-table-column prop="batchNo" label="批次" min-width="170" />
    <el-table-column label="冷备状态" width="130"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
    <el-table-column prop="hotCleanupStatus" label="热备清理" width="130" />
    <el-table-column label="时间范围" min-width="220"><template #default="{ row }">{{ row.orderTimeFrom || '-' }}<br>至 {{ row.orderTimeTo || '-' }}</template></el-table-column>
    <el-table-column label="订单 / 文件 / 容量" width="190"><template #default="{ row }">{{ row.orderCount || 0 }} 单 / {{ row.artifactCount || 0 }} 文件 / {{ row.totalSize || 0 }} bytes</template></el-table-column>
    <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'COMPLETED'" link type="primary" :disabled="coldBusy" @click.stop="verifyColdBatch(row)">校验</el-button><el-button v-if="row.status === 'VERIFIED'" link type="primary" @click.stop="selectColdBatch(row)">选择清理</el-button></template></el-table-column>
  </el-table>
</template>
