<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
import OrderBusinessFields from './OrderBusinessFields.vue';
const { orders, selectedOrder, selectedIds, selectOrder, toggleSelected } = useBackupContext();
</script>
<template>
  <el-table :data="orders" class="data-table" row-key="id" highlight-current-row @row-click="selectOrder">
    <el-table-column type="expand"><template #default="{ row }"><OrderBusinessFields :value="row.displayJson || row.orderRawJson" /></template></el-table-column>
    <el-table-column label="选择" width="62" align="center"><template #default="{ row }"><el-tooltip :disabled="row.backupMode !== 'METADATA_SIMULATION'" content="模拟备份不可用于线上恢复"><el-checkbox :disabled="row.backupMode === 'METADATA_SIMULATION'" :model-value="selectedIds.includes(row.id)" @click.stop @change="toggleSelected(row)" /></el-tooltip></template></el-table-column>
    <el-table-column label="订单号" min-width="190"><template #default="{ row }"><div class="order-code">{{ row.orderCode || row.sourceOrderId }}</div><small class="muted">快照 #{{ row.id }}</small><el-tag v-if="row.backupMode === 'METADATA_SIMULATION'" size="small" type="warning" effect="plain" class="simulation-tag">模拟备份</el-tag></template></el-table-column>
    <el-table-column prop="orderCreatedAt" label="创建时间" min-width="168" />
    <el-table-column label="机构 / 医院" min-width="220" show-overflow-tooltip><template #default="{ row }"><div>{{ row.orgName || '-' }}</div><small class="muted">{{ row.hospitalName || '-' }}</small></template></el-table-column>
    <el-table-column label="患者 / 医生" min-width="170" show-overflow-tooltip><template #default="{ row }"><div>{{ row.patientName || '-' }}</div><small class="muted">医生：{{ row.doctorName || '-' }}</small></template></el-table-column>
    <el-table-column label="业务类型" min-width="120"><template #default="{ row }">{{ row.businessTypeName || row.businessType || '-' }}</template></el-table-column>
    <el-table-column label="业务状态" min-width="150"><template #default="{ row }"><status-badge :value="row.statusName || row.sourceStatus" /><small v-if="row.phaseName" class="muted"> · {{ row.phaseName }}</small></template></el-table-column>
    <el-table-column label="文件规模" width="130"><template #default="{ row }"><strong>{{ row.assetCount ?? row.fileCount ?? '-' }}</strong><span class="muted"> 个 · {{ row.totalBytes ?? row.fileBytes ?? '-' }}</span></template></el-table-column>
    <el-table-column label="云端" width="100"><template #default="{ row }"><status-badge :value="row.cloudRollup" /></template></el-table-column>
    <el-table-column label="热备" width="100"><template #default="{ row }"><status-badge :value="row.hotRollup" /></template></el-table-column>
    <el-table-column label="冷备 / 批次" min-width="150"><template #default="{ row }"><status-badge :value="row.coldRollup" /><span v-if="row.coldBatchNo" class="muted"> · {{ row.coldBatchNo }}</span></template></el-table-column>
    <template #empty><empty-state message="暂无订单，请调整筛选条件" /></template>
  </el-table>
</template>
