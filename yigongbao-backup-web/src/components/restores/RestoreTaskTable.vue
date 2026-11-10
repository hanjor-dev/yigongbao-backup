<script setup>
import { jobProgress, storageVolumeView } from '../../taskCenter.js';
defineProps({ tasks: { type: Array, default: () => [] }, current: Number, size: Number, total: Number });
const emit = defineEmits(['select', 'page-change', 'size-change']);
const terminal = value => ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(String(value || '').toUpperCase());
const bytes = value => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
const time = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';
const volume = row => storageVolumeView(row, row.targetStorageVolumeId);
</script>

<template>
  <el-table :data="tasks" class="data-table" row-key="id" highlight-current-row @row-click="emit('select', $event)">
    <el-table-column label="任务编号" min-width="205"><template #default="{ row }"><div class="mono">{{ row.jobNo || `#${row.id}` }}</div><small class="muted">任务 ID：{{ row.id }}</small></template></el-table-column>
    <el-table-column label="提交时间" min-width="175"><template #default="{ row }">{{ time(row.requestedAt) }}</template></el-table-column>
    <el-table-column label="源存储卷" min-width="220"><template #default="{ row }"><div>{{ volume(row).name }}</div><small v-if="volume(row).current" class="muted">当前：{{ volume(row).current }}</small><small v-if="volume(row).historical" class="muted">{{ volume(row).historical }}</small></template></el-table-column>
    <el-table-column label="状态" width="150"><template #default="{ row }"><status-badge :value="row.cancelRequested ? 'CANCELLED' : row.state" :label="row.cancelRequested ? '取消中' : undefined" /></template></el-table-column>
    <el-table-column label="恢复进度" width="175"><template #default="{ row }"><el-progress :percentage="jobProgress(row)" :stroke-width="7" /></template></el-table-column>
    <el-table-column label="订单结果" min-width="230"><template #default="{ row }"><span class="success-text">成功 {{ row.orderCompleted || 0 }}</span> / <span class="error-text">失败 {{ row.orderFailed || 0 }}</span> / 跳过 {{ row.orderSkipped || 0 }} / 共 {{ row.orderTotal || 0 }}</template></el-table-column>
    <el-table-column label="上传包容量" width="145"><template #default="{ row }">{{ bytes(row.bytesCompleted) }}</template></el-table-column>
    <el-table-column label="失败原因" min-width="240" show-overflow-tooltip><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureEvidence || '' }}</span></template></el-table-column>
    <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button size="small" @click.stop="emit('select', row)">{{ terminal(row.state) ? '查看' : '跟踪' }}</el-button></template></el-table-column>
    <template #empty><el-empty description="暂无线上恢复任务" /></template>
  </el-table>
  <el-pagination v-if="total" class="table-pagination" background layout="total, sizes, prev, pager, next" :current-page="current" :page-size="size" :total="total" :page-sizes="[10,20,50,100]" @current-change="emit('page-change', $event)" @size-change="emit('size-change', $event)" />
</template>
