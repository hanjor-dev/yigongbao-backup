<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
import { incompleteBackupLabel } from '../../taskCenter.js';
import OrderBusinessFields from './OrderBusinessFields.vue';
const { orders, selectedOrder, selectedIds, selectOrder, toggleSelected } = useBackupContext();
const bytes = (value) => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
</script>
<template>
  <el-table :data="orders" class="data-table" row-key="id" highlight-current-row @row-click="selectOrder">
    <el-table-column type="expand"><template #default="{ row }"><OrderBusinessFields :value="row.displayJson || row.orderRawJson" /></template></el-table-column>
    <el-table-column label="选择" width="62" align="center"><template #default="{ row }"><el-tooltip :disabled="row.backupMode !== 'METADATA_SIMULATION' && row.storageAccessible !== false" :content="row.storageAccessible === false ? (row.storageUnavailableReason || '存储卷不可访问') : '模拟备份不可用于线上恢复'"><el-checkbox :disabled="row.backupMode === 'METADATA_SIMULATION' || row.storageAccessible === false" :model-value="selectedIds.includes(row.id)" @click.stop @change="toggleSelected(row)" /></el-tooltip></template></el-table-column>
    <el-table-column label="订单号" min-width="210"><template #default="{ row }"><div class="order-code">{{ row.orderCode || row.sourceOrderId }}</div><small class="muted">快照 #{{ row.id }}</small><el-tag v-if="row.backupMode === 'METADATA_SIMULATION'" size="small" type="warning" effect="plain" class="simulation-tag">模拟备份</el-tag><el-tag v-if="incompleteBackupLabel(row)" size="small" type="warning" effect="dark">文件可能不完整</el-tag><small v-if="incompleteBackupLabel(row)" class="muted">缺失关系 {{ row.relationGapCount || 0 }}</small></template></el-table-column>
    <el-table-column prop="orderCreatedAt" label="创建时间" min-width="168" />
    <el-table-column label="机构 / 医院" min-width="220" show-overflow-tooltip><template #default="{ row }"><div>{{ row.orgName || '-' }}</div><small class="muted">{{ row.hospitalName || '-' }}</small></template></el-table-column>
    <el-table-column label="患者 / 医生" min-width="170" show-overflow-tooltip><template #default="{ row }"><div>{{ row.patientName || '-' }}</div><small class="muted">医生：{{ row.doctorName || '-' }}</small></template></el-table-column>
    <el-table-column label="业务类型" min-width="120"><template #default="{ row }">{{ row.businessTypeName || row.businessType || '-' }}</template></el-table-column>
    <el-table-column label="业务状态" min-width="150"><template #default="{ row }"><status-badge :value="row.statusName || row.sourceStatus" /><small v-if="row.phaseName" class="muted"> · {{ row.phaseName }}</small></template></el-table-column>
    <el-table-column label="文件规模" width="160"><template #default="{ row }"><strong>{{ row.assetCount ?? row.fileCount ?? 0 }}</strong><span class="muted"> 个 · {{ bytes(row.totalBytes ?? row.fileBytes) }}</span></template></el-table-column>
    <el-table-column label="存储卷 / 位置" min-width="280" show-overflow-tooltip><template #default="{ row }"><div>{{ row.storageVolumeName || row.storageVolumeId || '-' }} <el-tag v-if="row.storageTier" size="small" effect="plain">{{ row.storageTier }}</el-tag></div><small class="muted">{{ row.storageKey || row.storageUnavailableReason || '未记录位置' }}</small><small v-if="row.storageArchiveNote" class="muted">封存：{{ row.storageArchiveNote }}<span v-if="row.storagePhysicalLocation"> · {{ row.storagePhysicalLocation }}</span></small></template></el-table-column>
    <el-table-column label="卷状态" width="110"><template #default="{ row }"><status-badge :value="row.storageAccessible === false ? 'OFFLINE' : row.storageMountStatus" /></template></el-table-column>
    <el-table-column label="云端" width="100"><template #default="{ row }"><status-badge :value="row.cloudRollup" /></template></el-table-column>
    <el-table-column label="热备" width="100"><template #default="{ row }"><status-badge :value="row.hotRollup" /></template></el-table-column>
    <el-table-column label="冷备 / 批次" min-width="150"><template #default="{ row }"><status-badge :value="row.coldRollup" /><span v-if="row.coldBatchNo" class="muted"> · {{ row.coldBatchNo }}</span></template></el-table-column>
    <template #empty><empty-state message="暂无订单，请调整筛选条件" /></template>
  </el-table>
</template>
