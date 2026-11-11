<script setup>
import { statusLabel } from '../StatusBadge.js';

defineProps({ detail: { type: Object, default: null } });
const emit = defineEmits(['close', 'abandon', 'page-change', 'size-change']);
const bytes = (value) => {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1048576) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1073741824) return `${(size / 1048576).toFixed(1)} MB`;
  return `${(size / 1073741824).toFixed(2)} GB`;
};
const failed = (phase) => ['PREPARE_FAILED', 'VERIFY_FAILED', 'COMMIT_FAILED', 'CANCELLED'].includes(phase);
</script>

<template>
  <el-drawer :model-value="!!detail" title="冷备封存任务详情" size="78%" @update:model-value="(visible) => { if (!visible) emit('close'); }">
    <template v-if="detail">
      <el-descriptions :column="4" border>
        <el-descriptions-item label="封存编号">{{ detail.archive.id }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ detail.archive.attemptNo || 1 }}</el-descriptions-item>
        <el-descriptions-item label="当前阶段"><status-badge :value="detail.archive.phase" /></el-descriptions-item>
        <el-descriptions-item label="订单周期">{{ detail.archive.periodLabel || '—' }}</el-descriptions-item>
        <el-descriptions-item label="封存备注" :span="2">{{ detail.archive.archiveNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="线下标签">{{ detail.archive.offlineLabelNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="存放位置">{{ detail.archive.physicalLocation || '—' }}</el-descriptions-item>
        <el-descriptions-item label="订单 / 文件">{{ detail.archive.orderCount || 0 }} / {{ detail.archive.managedFileCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="受管容量">{{ bytes(detail.archive.totalBytes) }}</el-descriptions-item>
        <el-descriptions-item label="开始校验">{{ detail.archive.verificationStartedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ detail.archive.archivedAt || detail.archive.verificationCompletedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="失败代码">{{ detail.archive.failureCode || '无' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="3">{{ detail.archive.failureMessage || '无' }}</el-descriptions-item>
      </el-descriptions>
      <div class="card-title archive-detail-title"><span>逐文件校验结果</span><el-tag>{{ detail.items.total }} 个文件</el-tag></div>
      <el-table :data="detail.items.records" max-height="560" stripe>
        <el-table-column label="订单编号" width="180"><template #default="{ row }">{{ row.orderCode || '—' }}</template></el-table-column>
        <el-table-column label="制品类型" width="150"><template #default="{ row }">{{ statusLabel(row.artifactType) }}</template></el-table-column>
        <el-table-column prop="storageKey" label="卷内位置" min-width="300" show-overflow-tooltip />
        <el-table-column label="预期 / 实际容量" width="180"><template #default="{ row }">{{ bytes(row.expectedSize) }} / {{ row.actualSize == null ? '—' : bytes(row.actualSize) }}</template></el-table-column>
        <el-table-column label="校验状态" width="120"><template #default="{ row }"><status-badge :value="row.verificationStatus" /></template></el-table-column>
        <el-table-column prop="failureCode" label="失败代码" width="180" />
        <el-table-column prop="failureMessage" label="失败原因" min-width="220" show-overflow-tooltip />
      </el-table>
      <div class="archive-detail-pagination">
        <el-pagination background layout="total, sizes, prev, pager, next, jumper"
          :current-page="detail.items.current" :page-size="detail.items.size" :page-sizes="[20, 50, 100]"
          :total="detail.items.total" @current-change="emit('page-change', $event)" @size-change="emit('size-change', $event)" />
      </div>
    </template>
    <template #footer>
      <el-button v-if="detail && failed(detail.archive.phase)" type="danger" plain @click="emit('abandon', detail.archive)">放弃本次尝试</el-button>
      <el-button @click="emit('close')">关闭</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.archive-detail-title { margin: 22px 0 12px; }
.archive-detail-pagination { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
