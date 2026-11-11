<script setup>
import { storageVolumeView } from '../../taskCenter.js';
import { jobFileOrderCode, jobFileOriginalFilename } from '../../jobDetailState.js';
import { workflowLabel } from '../StatusBadge.js';

defineProps({
  records: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  current: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  total: { type: Number, default: 0 },
  compactIdentity: { type: Boolean, default: false },
});

const emit = defineEmits(['page-change', 'size-change']);
const volume = (row) => storageVolumeView(row, row?.storageVolumeId);
const bytes = (value) => {
  const n = Number(value || 0);
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
};
const changePage = (value) => emit('page-change', value);
const changeSize = (value) => emit('size-change', value);
</script>

<template>
  <div class="job-file-table">
    <el-table v-loading="loading" :data="records" stripe border empty-text="暂无文件明细">
      <el-table-column v-if="!compactIdentity" label="订单号" width="150"><template #default="{ row }">{{ jobFileOrderCode(row) }}</template></el-table-column>
      <el-table-column label="文件名" min-width="200">
        <template #default="{ row }">
          <div class="file-identity">
            <span>{{ jobFileOriginalFilename(row) }}</span>
            <small v-if="compactIdentity" class="table-muted">资产：{{ row.assetIdentity || '-' }}</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="!compactIdentity" prop="assetIdentity" label="资产" min-width="190" show-overflow-tooltip />
      <el-table-column v-if="!compactIdentity" label="步骤" width="132">
        <template #default="{ row }">{{ workflowLabel(row.step) }}</template>
      </el-table-column>
      <el-table-column label="存储卷 / 相对位置" min-width="310">
        <template #default="{ row }">
          <div class="volume-line"><strong>{{ volume(row).name }}</strong><span v-if="volume(row).current">当前：{{ volume(row).current }}</span></div>
          <small v-if="volume(row).historical" class="table-muted">任务时：{{ volume(row).historical }}</small>
          <small v-if="row.storageKey" class="storage-key">{{ row.storageKey }}</small>
        </template>
      </el-table-column>
      <el-table-column v-if="!compactIdentity" label="状态" width="118"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column v-if="!compactIdentity" label="进度" width="166"><template #default="{ row }">{{ bytes(row.bytesDone) }} / {{ bytes(row.bytesTotal) }}</template></el-table-column>
      <el-table-column v-else label="状态 / 进度" width="170">
        <template #default="{ row }">
          <div class="file-state-progress">
            <status-badge :value="row.status" />
            <small class="table-muted">{{ bytes(row.bytesDone) }} / {{ bytes(row.bytesTotal) }}</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="240"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
      <el-table-column v-if="compactIdentity" label="步骤" width="132">
        <template #default="{ row }">{{ workflowLabel(row.step) }}</template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="total > 0"
      class="table-pagination"
      background
      layout="total, sizes, prev, pager, next"
      :current-page="current"
      :page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="changePage"
      @size-change="changeSize"
    />
  </div>
</template>

<style scoped>
/* finesse · component: job-file-table · register=product
 * states: loading · empty · populated · paginated · compact-identity
 * tokens: inherited (Element Plus CSS variables) */
.file-identity { display: grid; gap: 4px; }
.file-identity small { overflow-wrap: anywhere; }
.file-state-progress { display: grid; gap: 8px; align-content: center; }
.file-state-progress small { white-space: nowrap; }
.volume-line { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; }
.volume-line span { color: var(--el-text-color-regular); }
.storage-key { display: block; margin-top: 4px; overflow-wrap: anywhere; color: var(--el-text-color-secondary); }
</style>
